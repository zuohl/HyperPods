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

    override fun onHook() {
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

                // Derive the status-bar icon from the actually-connected profiles rather
                // than flipping it blindly: a transient A2DP teardown (idle, call mode) or
                // an already-connected-at-hook-install device should not hide the icon
                // while the headset profile is still up.
                updateHeadsetIcon(context)
                if (currState == BluetoothHeadset.STATE_CONNECTED) {
                    PodController.connectPod(context, device, prefs)
                } else if (currState == BluetoothHeadset.STATE_DISCONNECTING || currState == BluetoothHeadset.STATE_DISCONNECTED) {
                    PodController.disconnectedPod(context, device)
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
                // Don't hide the icon just because one link dropped — re-derive from the
                // actually-connected profiles instead.
                updateHeadsetIcon(context)
                if (state == BluetoothProfile.STATE_DISCONNECTED) {
                    PodController.disconnectedPod(context, device)
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
}
