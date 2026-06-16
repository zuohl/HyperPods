package moe.chenxy.oppopods.hook

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
import moe.chenxy.oppopods.BuildConfig
import moe.chenxy.oppopods.pods.PodController
import moe.chenxy.oppopods.pods.PodDetector
import moe.chenxy.oppopods.utils.SystemApisUtils.setIconVisibility
import moe.chenxy.oppopods.utils.miuiStrongToast.data.OppoPodsAction

object HeadsetStateDispatcher : HookContext() {
    private var appRequestReceiverRegistered = false
    private var bluetoothStateReceiverRegistered = false

    override fun onHook() {
        runCatching {
            hookAfter(findMethod("com.android.bluetooth.btservice.AdapterService", "onCreate")) {
                registerAppRequestReceiver(instance as? Context)
            }
        }.onFailure {
            Log.w("OppoPods", "AdapterService.onCreate hook skipped", it)
        }

        hookAfter(findMethodByParamCount("com.android.bluetooth.a2dp.A2dpService", "handleConnectionStateChanged", 3)) {
            val currState = args[2] as Int
            val fromState = args[1] as Int
            val device = args[0] as BluetoothDevice?
            val handler = getObjectField(instance, "mHandler") as Handler
            if (device == null || currState == fromState) {
                return@hookAfter
            }
            handler.post {
                Log.d("OppoPods", "A2DP Connection State: $currState, isOppoPod ${isOppoPod(device)}")
                val context = instance as ContextWrapper
                registerAppRequestReceiver(context)
                registerBluetoothStateReceiver(context)
                if (!isOppoPod(device)) return@post

                val statusBarManager = context.getSystemService("statusbar") as StatusBarManager
                if (currState == BluetoothHeadset.STATE_CONNECTED) {
                    statusBarManager.setIconVisibility("wireless_headset", true)
                    PodController.connectPod(context, device, prefs)
                } else if (currState == BluetoothHeadset.STATE_DISCONNECTING || currState == BluetoothHeadset.STATE_DISCONNECTED) {
                    statusBarManager.setIconVisibility("wireless_headset", false)
                    PodController.disconnectedPod(context, device)
                }
            }
        }
    }

    private fun registerAppRequestReceiver(context: Context?) {
        if (context == null || appRequestReceiverRegistered) return
        context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (context == null) return
                when (intent?.action) {
                    OppoPodsAction.ACTION_PODS_UI_INIT,
                    OppoPodsAction.ACTION_REFRESH_STATUS -> {
                        context.sendBroadcast(Intent(OppoPodsAction.ACTION_MODULE_BLUETOOTH_SERVICE_ALIVE).apply {
                            setPackage(BuildConfig.APPLICATION_ID)
                            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                        })
                        ensureConnectedPod(context)
                    }
                    OppoPodsAction.ACTION_CONNECT_POD_REQUEST -> {
                        val device = intent.getParcelableExtra("device", BluetoothDevice::class.java) ?: return
                        Log.d("OppoPods", "connect request from app device=${device.name}/${device.address}")
                        PodController.connectPod(context, device, prefs, appRequested = true)
                    }
                    OppoPodsAction.ACTION_DISCONNECT_POD_REQUEST -> {
                        val device = intent.getParcelableExtra("device", BluetoothDevice::class.java) ?: return
                        Log.d("OppoPods", "disconnect request from app device=${device.name}/${device.address}")
                        PodController.disconnectedPod(context, device)
                    }
                }
            }
        }, IntentFilter().apply {
            addAction(OppoPodsAction.ACTION_PODS_UI_INIT)
            addAction(OppoPodsAction.ACTION_REFRESH_STATUS)
            addAction(OppoPodsAction.ACTION_CONNECT_POD_REQUEST)
            addAction(OppoPodsAction.ACTION_DISCONNECT_POD_REQUEST)
        }, Context.RECEIVER_EXPORTED)
        appRequestReceiverRegistered = true
        registerBluetoothStateReceiver(context)
    }

    private fun registerBluetoothStateReceiver(context: Context?) {
        if (context == null || bluetoothStateReceiverRegistered) return
        context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (context == null || intent == null) return
                val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
                if (!isOppoPod(device)) return
                when (intent.action) {
                    BluetoothDevice.ACTION_ACL_DISCONNECTED,
                    BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED -> {
                        Log.d("OppoPods", "ACL disconnected device=${device.name}/${device.address}")
                        runCatching {
                            val statusBarManager = context.getSystemService("statusbar") as StatusBarManager
                            statusBarManager.setIconVisibility("wireless_headset", false)
                        }
                        PodController.disconnectedPod(context, device)
                    }
                    BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED,
                    BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> {
                        val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED)
                        if (state == BluetoothProfile.STATE_DISCONNECTED || state == BluetoothProfile.STATE_DISCONNECTING) {
                            Log.d("OppoPods", "profile disconnected state=$state device=${device.name}/${device.address}")
                            runCatching {
                                val statusBarManager = context.getSystemService("statusbar") as StatusBarManager
                                statusBarManager.setIconVisibility("wireless_headset", false)
                            }
                            PodController.disconnectedPod(context, device)
                        }
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
    }

    @SuppressLint("MissingPermission")
    private fun ensureConnectedPod(context: Context) {
        val snapshot = runCatching { PodController.currentStatusSnapshot() }.getOrNull()
        if (snapshot?.connected == true || snapshot?.connecting == true) {
            Log.d("OppoPods", "ensureConnectedPod skipped snapshot connected=${snapshot.connected} connecting=${snapshot.connecting}")
            return
        }

        val bluetoothManager = context.getSystemService(BluetoothManager::class.java) ?: return
        val connectedDevice = buildList {
            addAll(runCatching { bluetoothManager.getConnectedDevices(BluetoothProfile.HEADSET) }.getOrDefault(emptyList()))
            addAll(runCatching { bluetoothManager.getConnectedDevices(BluetoothProfile.A2DP) }.getOrDefault(emptyList()))
        }.distinctBy { it.address }
            .firstOrNull { isOppoPod(it) }
            ?: return

        Log.d("OppoPods", "take over connected device=${connectedDevice.name}/${connectedDevice.address}")
        PodController.connectPod(context, connectedDevice, prefs, appRequested = true)
    }

    /**
     * Detect OPPO earphones by checking if the device name contains "oppo" (case insensitive).
     */
    @SuppressLint("MissingPermission")
    fun isOppoPod(device: BluetoothDevice): Boolean {
        return PodDetector.isSupportedPod(device)
    }
}
