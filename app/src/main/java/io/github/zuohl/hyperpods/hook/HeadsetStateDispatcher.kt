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
import io.github.zuohl.hyperpods.BuildConfig
import io.github.zuohl.hyperpods.pods.PodController
import io.github.zuohl.hyperpods.pods.PodDetector
import io.github.zuohl.hyperpods.utils.SystemApisUtils.setIconVisibility
import io.github.zuohl.hyperpods.utils.miuiStrongToast.data.PodAction

object HeadsetStateDispatcher : HookContext() {
    private var appRequestReceiverRegistered = false
    private var bluetoothStateReceiverRegistered = false

    override fun onHook() {
        runCatching {
            hookAfter(findMethod("com.android.bluetooth.btservice.AdapterService", "onCreate")) {
                registerAppRequestReceiver(instance as? Context)
            }
        }.onFailure {
            Log.w("HyperPods", "AdapterService.onCreate hook skipped", it)
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
                Log.d("HyperPods", "A2DP Connection State: $currState, isSupportedPod ${isSupportedPod(device)}")
                val context = instance as ContextWrapper
                registerAppRequestReceiver(context)
                registerBluetoothStateReceiver(context)
                if (!isSupportedPod(device)) return@post

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
                    PodAction.ACTION_PODS_UI_INIT,
                    PodAction.ACTION_REFRESH_STATUS -> {
                        context.sendBroadcast(Intent(PodAction.ACTION_MODULE_BLUETOOTH_SERVICE_ALIVE).apply {
                            setPackage(BuildConfig.APPLICATION_ID)
                            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                        })
                        ensureConnectedPod(context)
                    }
                    PodAction.ACTION_CONNECT_POD_REQUEST -> {
                        val device = intent.getParcelableExtra("device", BluetoothDevice::class.java) ?: return
                        Log.d("HyperPods", "connect request from app device=${device.name}/${device.address}")
                        PodController.connectPod(context, device, prefs, appRequested = true)
                    }
                    PodAction.ACTION_DISCONNECT_POD_REQUEST -> {
                        val device = intent.getParcelableExtra("device", BluetoothDevice::class.java) ?: return
                        Log.d("HyperPods", "disconnect request from app device=${device.name}/${device.address}")
                        PodController.disconnectedPod(context, device)
                    }
                }
            }
        }, IntentFilter().apply {
            addAction(PodAction.ACTION_PODS_UI_INIT)
            addAction(PodAction.ACTION_REFRESH_STATUS)
            addAction(PodAction.ACTION_CONNECT_POD_REQUEST)
            addAction(PodAction.ACTION_DISCONNECT_POD_REQUEST)
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
                if (!isSupportedPod(device)) return
               when (intent.action) {
                    BluetoothDevice.ACTION_ACL_DISCONNECTED,
                    BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED -> {
                        Log.d("HyperPods", "ACL disconnected device=${device.name}/${device.address}")
                        runCatching {
                            val statusBarManager = context.getSystemService("statusbar") as StatusBarManager
                            statusBarManager.setIconVisibility("wireless_headset", false)
                        }
                        PodController.disconnectedPod(context, device)
                    }
                    BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED,
                    BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> {
                        val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED)
                        Log.d("HyperPods", "profile state changed action=${intent.action} state=$state device=${device.name}/${device.address} isSupportedPod=${isSupportedPod(device)}")
                        if (state == BluetoothProfile.STATE_DISCONNECTED || state == BluetoothProfile.STATE_DISCONNECTING) {
                            Log.d("HyperPods", "profile disconnected state=$state device=${device.name}/${device.address}")
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
            Log.d("HyperPods", "ensureConnectedPod skipped snapshot connected=${snapshot.connected} connecting=${snapshot.connecting}")
            return
        }

        val bluetoothManager = context.getSystemService(BluetoothManager::class.java) ?: return
        val connectedDevice = buildList {
            addAll(runCatching { bluetoothManager.getConnectedDevices(BluetoothProfile.HEADSET) }.getOrDefault(emptyList()))
            addAll(runCatching { bluetoothManager.getConnectedDevices(BluetoothProfile.A2DP) }.getOrDefault(emptyList()))
        }.distinctBy { it.address }
            .firstOrNull { isSupportedPod(it) }
            ?: return

        Log.d("HyperPods", "take over connected device=${connectedDevice.name}/${connectedDevice.address}")
        PodController.connectPod(context, connectedDevice, prefs, appRequested = true)
    }

    /**
     * Detect OPPO earphones by checking if the device name contains "oppo" (case insensitive).
     */
    @SuppressLint("MissingPermission")
    fun isSupportedPod(device: BluetoothDevice): Boolean {
        return PodDetector.isSupportedPod(device)
    }
}
