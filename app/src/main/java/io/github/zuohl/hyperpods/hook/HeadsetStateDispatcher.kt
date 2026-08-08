package io.github.zuohl.hyperpods.hook

import android.annotation.SuppressLint
import android.app.StatusBarManager
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.util.Log
import io.github.zuohl.hyperpods.config.FakeDeviceConfig
import io.github.zuohl.hyperpods.pods.PodController
import io.github.zuohl.hyperpods.pods.PodDetector
import io.github.zuohl.hyperpods.pods.RfcommController
import io.github.zuohl.hyperpods.utils.SystemApisUtils.setIconVisibility
import io.github.zuohl.hyperpods.utils.miuiStrongToast.data.OppoPodsAction

object HeadsetStateDispatcher : HookContext() {
    private var notificationSettingsReceiverRegistered = false
    private var notificationSettingsContext: Context? = null
    private var notificationSettingsReceiver: BroadcastReceiver? = null
    private var bluetoothStateReceiverRegistered = false
    private val knownPodAddresses = linkedSetOf<String>()
    private val hookedBinderClasses = linkedSetOf<String>()
    private val syncHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var syncRunnable: Runnable? = null

    private val fakeDeviceId: String get() = FakeDeviceConfig.deviceId(prefs)
    private val fakeSupport: String get() = FakeDeviceConfig.support(prefs)

    override fun onHook() {
        hookHeadsetServiceBinder()
        hookAfter(findMethodByParamCount("com.android.bluetooth.a2dp.A2dpService", "handleConnectionStateChanged", 3)) {
            val currState = args[2] as Int
            val fromState = args[1] as Int
            val device = args[0] as BluetoothDevice?
            val handler = getObjectField(instance, "mHandler") as Handler
            if (device == null || currState == fromState) {
                return@hookAfter
            }
            handler.post {
                val supported = device != null && PodController.supports(device)
                Log.d("HyperPods", "A2DP Connection State: $currState, supported=$supported brand=${device?.let { PodDetector.detectBrand(it) }}")
                val context = instance as ContextWrapper
                registerNotificationSettingsReceiver(context)
                registerBluetoothStateReceiver(context)
                if (!supported) return@post

                if (currState == BluetoothHeadset.STATE_CONNECTED) {
                    // Show directly: getConnectedDevices() lags a beat after the CONNECTED
                    // event, so deriving the icon here races and hides it again.
                    showHeadsetIcon(context)
                    PodController.connectPod(context, device, prefs)
                } else if (currState == BluetoothHeadset.STATE_DISCONNECTING || currState == BluetoothHeadset.STATE_DISCONNECTED) {
                    // Re-evaluate: hide only when no supported pod remains in any profile,
                    // so a transient A2DP teardown (idle, call mode) keeps the icon while
                    // the headset profile is still up. Don't clear the pod for a transient
                    // drop that leaves the device connected on another profile.
                    if (!hasAnyConnectedProfile(context, device)) {
                        updateHeadsetIcon(context)
                        PodController.disconnectedPod(context, device)
                    } else {
                        Log.d("HyperPods", "A2DP transient drop ignored (still connected) ${device.address}")
                    }
                }
            }
        }
    }

    override fun onHotReloading() {
        notificationSettingsReceiver?.let { receiver ->
            runCatching { notificationSettingsContext?.unregisterReceiver(receiver) }
        }
        notificationSettingsReceiver = null
        notificationSettingsContext = null
        notificationSettingsReceiverRegistered = false
        bluetoothStateReceiverRegistered = false
        stopSyncLoop()
        RfcommController.shutdownForHotReload()
    }

    private fun registerNotificationSettingsReceiver(context: Context) {
        if (notificationSettingsReceiverRegistered) return
        val receiver = object : BroadcastReceiver() {
                override fun onReceive(receiverContext: Context?, intent: Intent?) {
                    if (intent?.action != OppoPodsAction.ACTION_NOTIFICATION_SETTINGS_CHANGED) return
                    RfcommController.syncNotificationSettings(
                        receiverContext ?: context,
                        intent,
                        refreshNotification = false
                    )
                }
        }
        context.registerReceiver(
            receiver,
            IntentFilter(OppoPodsAction.ACTION_NOTIFICATION_SETTINGS_CHANGED),
            Context.RECEIVER_EXPORTED
        )
        notificationSettingsContext = context.applicationContext ?: context
        notificationSettingsReceiver = receiver
        notificationSettingsReceiverRegistered = true
    }

    /**
     * Re-evaluates the status-bar "wireless_headset" icon from the set of actually
     * connected HFP/A2DP profiles, so a brief ACL/A2DP drop or an already-connected
     * pod (hook installed after A2DP connected) does not leave the icon wrong.
     */
    @SuppressLint("MissingPermission")
    private fun registerBluetoothStateReceiver(context: Context) {
        if (bluetoothStateReceiverRegistered) return
        context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (context == null || intent == null) return
                val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
                if (!PodController.supports(device)) return
                val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED)
                Log.d("HyperPods", "bt state action=${intent.action} state=$state device=${device.address}")
                // Only re-evaluate on disconnects: deriving on CONNECTED races with
                // getConnectedDevices() lagging behind and actively hides the icon.
                if (state == BluetoothProfile.STATE_DISCONNECTED || state == BluetoothProfile.STATE_DISCONNECTING) {
                    // ACL_DISCONNECTED is NOT a real disconnect: the earphone (esp. dual-mode
                    // ones like vivo) briefly drops/re-establishes its ACL during initial
                    // connect or power-save while A2DP/HFP stay up. Treating it as a disconnect
                    // cleared the pod state and hid the icon even though the system list still
                    // shows the earphone connected. Only HEADSET/A2DP profile disconnects can
                    // be real, and even then only when no profile remains connected.
                    if (intent.action == BluetoothDevice.ACTION_ACL_DISCONNECTED ||
                        intent.action == BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED) {
                        Log.d("HyperPods", "ACL drop ignored (not a real disconnect) ${device.address}")
                        return@onReceive
                    }
                    if (!hasAnyConnectedProfile(context, device)) {
                        updateHeadsetIcon(context)
                        PodController.disconnectedPod(context, device)
                    } else {
                        Log.d("HyperPods", "transient drop ignored (still connected) ${device.address}")
                    }
                }
            }
        }, IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
        }, Context.RECEIVER_EXPORTED)
        bluetoothStateReceiverRegistered = true
        // Re-assert on registration so a pod already connected before the hook was
        // installed (BT toggle, process restart) still gets the icon.
        updateHeadsetIcon(context)
        // Ensure any already-connected supported pod has a live controller. If the module
        // loaded after the earphone connected (or a transient drop cleared activePod but the
        // earphone stayed connected), the A2DP CONNECTED event won't fire again — so we adopt
        // it here. Otherwise the module shows "no device" while the system list shows connected.
        syncConnectedPods(context)
        // Periodically re-sync so a transient ACL drop that briefly clears activePod gets
        // re-adopted within a few seconds (the earphone stays connected but emits no new
        // A2DP CONNECTED, so nothing else would restore the module/icon state).
        stopSyncLoop()
        val runnable = object : Runnable {
            override fun run() {
                if (!bluetoothStateReceiverRegistered) return
                syncConnectedPods(context)
                syncHandler.postDelayed(this, 5_000L)
            }
        }
        syncRunnable = runnable
        syncHandler.postDelayed(runnable, 5_000L)
    }

    private fun stopSyncLoop() {
        syncRunnable?.let { syncHandler.removeCallbacks(it) }
        syncRunnable = null
    }

    @SuppressLint("MissingPermission")
    private fun syncConnectedPods(context: Context) {
        val bluetoothManager = context.getSystemService(BluetoothManager::class.java) ?: return
        val connected = buildList {
            addAll(runCatching { bluetoothManager.getConnectedDevices(BluetoothProfile.HEADSET) }.getOrDefault(emptyList()))
            addAll(runCatching { bluetoothManager.getConnectedDevices(BluetoothProfile.A2DP) }.getOrDefault(emptyList()))
        }.distinctBy { it.address }
        for (device in connected) {
            if (!PodController.supports(device)) continue
            if (PodController.isActivePod(device)) continue
            Log.d("HyperPods", "syncConnectedPods: adopting ${device.address} ${runCatching { device.name }.getOrNull()}")
            showHeadsetIcon(context)
            PodController.connectPod(context, device, prefs)
        }
    }

    /**
     * Whether [device] still has a live HFP/A2DP profile, i.e. this "disconnect" event was
     * just a transient link drop rather than the earphone actually going away.
     */
    @SuppressLint("MissingPermission")
    private fun hasAnyConnectedProfile(context: Context, device: BluetoothDevice): Boolean {
        val bluetoothManager = context.getSystemService(BluetoothManager::class.java) ?: return false
        val address = device.address
        val connected = buildList {
            addAll(runCatching { bluetoothManager.getConnectedDevices(BluetoothProfile.HEADSET) }.getOrDefault(emptyList()))
            addAll(runCatching { bluetoothManager.getConnectedDevices(BluetoothProfile.A2DP) }.getOrDefault(emptyList()))
        }.any { it.address == address }
        Log.d("HyperPods", "hasAnyConnectedProfile addr=$address connected=$connected")
        return connected
    }

    private fun showHeadsetIcon(context: Context) {
        runCatching {
            val statusBarManager = context.getSystemService("statusbar") as StatusBarManager
            statusBarManager.setIconVisibility("wireless_headset", true)
        }
    }

    @SuppressLint("MissingPermission")
    private fun updateHeadsetIcon(context: Context?) {
        if (context == null) return
        val bluetoothManager = context.getSystemService(BluetoothManager::class.java) ?: return
        val connected = buildList {
            addAll(runCatching { bluetoothManager.getConnectedDevices(BluetoothProfile.HEADSET) }.getOrDefault(emptyList()))
            addAll(runCatching { bluetoothManager.getConnectedDevices(BluetoothProfile.A2DP) }.getOrDefault(emptyList()))
        }.distinctBy { it.address }.any { PodController.supports(it) }
        runCatching {
            val statusBarManager = context.getSystemService("statusbar") as StatusBarManager
            statusBarManager.setIconVisibility("wireless_headset", connected)
        }
        Log.d("HyperPods", "updateHeadsetIcon connected=$connected")
    }

    // ------------------------------------------------------------------
    // MIUI headset-service binder faking (com.android.bluetooth).
    // SystemUI queries IMiuiHeadsetService.checkSupport/isMiTWS in this process
    // to decide whether to show the wireless_headset status-bar icon. Without the
    // fake support the device is treated as unrecognized and the icon stays hidden
    // even though setIconVisibility fills the slot.
    // ------------------------------------------------------------------

    private fun hookHeadsetServiceBinder() {
        val serviceClassName = "com.android.bluetooth.ble.app.headset.BluetoothHeadsetService"
        val serviceClass = findClassOrNull(serviceClassName)
        if (serviceClass != null) {
            runCatching {
                hookAfter(findMethod(serviceClassName, "onBind", Intent::class.java)) {
                    val binder = result ?: return@hookAfter
                    installHeadsetBinderHooks(binder.javaClass)
                }
                Log.d("HyperPods", "BluetoothHeadsetService.onBind hook installed")
            }.onFailure { Log.w("HyperPods", "hook BluetoothHeadsetService.onBind failed", it) }
        } else {
            Log.d("HyperPods", "BluetoothHeadsetService class not present")
        }

        listOf(
            "com.android.bluetooth.ble.app.headset.BinderC6776v",
            "com.android.bluetooth.ble.app.headset.v"
        ).forEach { className ->
            findClassOrNull(className)?.let { installHeadsetBinderHooks(it) }
        }
    }

    private fun findClassOrNull(className: String): Class<*>? =
        runCatching { findClass(className) }.getOrNull()

    private fun installHeadsetBinderHooks(binderClass: Class<*>) {
        val className = binderClass.name
        if (!hookedBinderClasses.add(className)) return
        Log.d("HyperPods", "headset binder class=$className")

        runCatching {
            hookBefore(findMethod(className, "checkSupport", BluetoothDevice::class.java)) {
                val device = args[0] as? BluetoothDevice
                if (!isSupportedPod(device)) return@hookBefore
                result = fakeSupport
                Log.d("HyperPods", "checkSupport forced device=${device?.address} support=$fakeSupport")
            }
        }.onFailure { Log.w("HyperPods", "hook checkSupport skipped", it) }

        hookAddressStringResult(className, listOf("getDeviceInfo"), "getDeviceInfo") { fakeSupport }
        hookAddressStringResult(className, listOf("isSupportAudioSwitch", "mo19775z1", "z1"), "isSupportAudioSwitch") { "1" }
        hookAddressBooleanResult(className, listOf("isMiTWS", "mo19771O0", "O0"), "isMiTWS", true)
        hookAddressBooleanResult(className, listOf("checkIsMiTWS", "mo19766B", "B"), "checkIsMiTWS", true)
        hookAddressBooleanResult(className, listOf("getRingFindState", "mo19772m0", "m0"), "getRingFindState", false)

        runCatching {
            hookBefore(findMethod(className, "setCommonCommand", Int::class.java, String::class.java, BluetoothDevice::class.java)) {
                val command = args[0] as? Int
                val device = args[2] as? BluetoothDevice
                if (!isSupportedPod(device)) return@hookBefore
                result = when (command) {
                    102 -> "0"
                    123 -> "4"
                    else -> "1"
                }
                Log.d("HyperPods", "setCommonCommand forced command=$command device=${device?.address} result=$result")
            }
        }.onFailure { Log.w("HyperPods", "hook setCommonCommand skipped", it) }

        hookBinderVoidDevice(className, "connect")
        hookBinderVoidDevice(className, "getDeviceConfig")
        hookBinderVoidDeviceString(className, "getCommonConfig")
    }

    private fun hookBinderVoidDevice(className: String, methodName: String) {
        runCatching {
            hookBefore(findMethod(className, methodName, BluetoothDevice::class.java)) {
                val device = args[0] as? BluetoothDevice
                if (!isSupportedPod(device)) return@hookBefore
                result = null
                Log.d("HyperPods", "$methodName swallowed device=${device?.address}")
            }
        }.onFailure { Log.w("HyperPods", "hook $methodName skipped", it) }
    }

    private fun hookBinderVoidDeviceString(className: String, methodName: String) {
        runCatching {
            hookBefore(findMethod(className, methodName, BluetoothDevice::class.java, String::class.java)) {
                val device = args[0] as? BluetoothDevice
                if (!isSupportedPod(device)) return@hookBefore
                result = null
                Log.d("HyperPods", "$methodName swallowed device=${device?.address}")
            }
        }.onFailure { Log.w("HyperPods", "hook $methodName skipped", it) }
    }

    private fun hookAddressStringResult(className: String, methodNames: List<String>, label: String, forced: () -> String) {
        val methodName = methodNames.firstOrNull { name ->
            runCatching { findMethod(className, name, String::class.java) }.isSuccess
        } ?: run {
            Log.w("HyperPods", "hook $label skipped: no method in $methodNames")
            return
        }
        runCatching {
            hookBefore(findMethod(className, methodName, String::class.java)) {
                val address = args[0] as? String
                if (address == null || !isKnownAddress(address)) return@hookBefore
                result = forced()
                Log.d("HyperPods", "$label forced address=$address result=$result")
            }
        }.onFailure { Log.w("HyperPods", "hook $label skipped", it) }
    }

    private fun hookAddressBooleanResult(className: String, methodNames: List<String>, label: String, forced: Boolean) {
        val methodName = methodNames.firstOrNull { name ->
            runCatching { findMethod(className, name, String::class.java) }.isSuccess
        } ?: run {
            Log.w("HyperPods", "hook $label skipped: no method in $methodNames")
            return
        }
        runCatching {
            hookBefore(findMethod(className, methodName, String::class.java)) {
                val address = args[0] as? String
                if (address == null || !isKnownAddress(address)) return@hookBefore
                result = forced
                Log.d("HyperPods", "$label forced address=$address result=$forced")
            }
        }.onFailure { Log.w("HyperPods", "hook $label skipped", it) }
    }

    @SuppressLint("MissingPermission")
    private fun isSupportedPod(device: BluetoothDevice?): Boolean {
        if (device == null) return false
        val address = runCatching { device.address }.getOrNull()
        val result = PodController.supports(device) || (address != null && isKnownAddress(address))
        if (result && address != null) knownPodAddresses.add(address.uppercase())
        return result
    }

    private fun isKnownAddress(address: String): Boolean {
        return address.uppercase() in knownPodAddresses
    }
}
