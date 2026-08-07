package io.github.zuohl.hyperpods.hook

import android.annotation.SuppressLint
import android.app.StatusBarManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
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
                if (!supported) return@post

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

    override fun onHotReloading() {
        notificationSettingsReceiver?.let { receiver ->
            runCatching { notificationSettingsContext?.unregisterReceiver(receiver) }
        }
        notificationSettingsReceiver = null
        notificationSettingsContext = null
        notificationSettingsReceiverRegistered = false
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

}
