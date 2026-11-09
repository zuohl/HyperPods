package io.github.zuohl.hyperpods.hook

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.os.Bundle
import android.util.Log
import com.xzakota.hyper.notification.focus.FocusNotification
import io.github.zuohl.hyperpods.pods.DeviceProfileStore
import io.github.zuohl.hyperpods.utils.FocusIslandUtil
import io.github.zuohl.hyperpods.utils.ModuleImageUtil
import io.github.zuohl.hyperpods.utils.SystemApisUtils
import io.github.zuohl.hyperpods.utils.SystemApisUtils.cancelAsUser
import io.github.zuohl.hyperpods.utils.SystemApisUtils.notifyAsUser
import io.github.zuohl.hyperpods.utils.miuiStrongToast.data.BatteryParams
import io.github.zuohl.hyperpods.utils.miuiStrongToast.data.NotificationSettings
import io.github.zuohl.hyperpods.utils.miuiStrongToast.data.OppoPodsAction
import io.github.zuohl.hyperpods.utils.miuiStrongToast.data.OppoPodsPrefsKey
import io.github.zuohl.hyperpods.R
import io.github.zuohl.hyperpods.config.FakeDeviceConfig


@SuppressLint("MissingPermission")
object MiBluetoothToastHook : HookContext() {
    private const val NOTIFICATION_ID = 10003
    private const val NOTIFICATION_TAG_PREFIX = "BTHeadset"
    private const val LEGACY_ISLAND_NOTIFICATION_TAG_PREFIX = "BTHeadsetIsland"
    private const val CONNECTION_CHANNEL_ID = "oppopods_connection_notification"
    private const val CONNECTION_CHANNEL_NAME = "HyperPods"
    private const val PENDING_INTENT_FLAGS =
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    // ANC 模式本地缓存，用于循环切换和状态同步（1=关 2=降噪 3=通透 4=自适应）
    // 通过接收 ACTION_PODS_ANC_CHANGED 广播与 RfcommController 保持同步
    private var localAncMode = 1
    private var notificationReceiver: BroadcastReceiver? = null
    private var notificationReceiverContext: Context? = null
    private val activePodAddresses = linkedSetOf<String>()

    override fun onHook() {
        var notificationSettings = NotificationSettings.fromPrefs(prefs)
        val lastNotificationIslandStyle = mutableMapOf<String, Boolean>()
        var lastNotificationDevice: BluetoothDevice? = null
        var lastNotificationBatteryParams: BatteryParams? = null
        var lastNotificationRfcommConnected: Boolean = true

        fun notificationTag(address: String): String {
            return "$NOTIFICATION_TAG_PREFIX$address"
        }

        fun legacyIslandNotificationTag(address: String): String {
            return "$LEGACY_ISLAND_NOTIFICATION_TAG_PREFIX$address"
        }

        fun cancelNotificationByTag(notificationManager: NotificationManager, tag: String) {
            notificationManager.cancelAsUser(
                tag,
                NOTIFICATION_ID,
                SystemApisUtils.getUserAllUserHandle()
            )
        }

        fun cancelAllPodsNotifications(context: Context) {
            try {
                val notificationManager = context.getSystemService("notification") as NotificationManager
                notificationManager.activeNotifications
                    .filter {
                        it.id == NOTIFICATION_ID &&
                            (it.tag?.startsWith(NOTIFICATION_TAG_PREFIX) == true ||
                                it.tag?.startsWith(LEGACY_ISLAND_NOTIFICATION_TAG_PREFIX) == true)
                    }
                    .mapNotNull { it.tag }
                    .forEach { cancelNotificationByTag(notificationManager, it) }
                lastNotificationIslandStyle.clear()
                lastNotificationDevice = null
                lastNotificationBatteryParams = null
            } catch (e: Exception) {
                Log.e("OppoPods", "Failed to cancel active Pod Notifications!", e)
            }
        }

        fun cachedNotificationSettings(context: Context): NotificationSettings? {
            return context.getSharedPreferences(
                OppoPodsPrefsKey.NOTIFICATION_SETTINGS_CACHE_PREFS_NAME,
                Context.MODE_PRIVATE
            ).let { NotificationSettings.fromPrefsOrNull(it) }
        }

        fun cacheNotificationSettings(context: Context, settings: NotificationSettings) {
            settings.withUpdatedAtIfMissing().writeToPrefs(
                context.getSharedPreferences(
                    OppoPodsPrefsKey.NOTIFICATION_SETTINGS_CACHE_PREFS_NAME,
                    Context.MODE_PRIVATE
                )
            )
        }

        fun loadNotificationSettings(context: Context): NotificationSettings {
            reloadRemotePrefs()
            val remoteSettings = NotificationSettings.fromPrefs(prefs)
            if (
                remoteSettings.updatedAt == 0L &&
                prefs.contains(OppoPodsPrefsKey.SHOW_CONNECTION_NOTIFICATION) &&
                !remoteSettings.showConnectionNotification
            ) {
                return remoteSettings
            }
            return NotificationSettings.newerOf(remoteSettings, cachedNotificationSettings(context))
        }

        fun effectiveNotificationSettings(
            intent: Intent? = null,
            context: Context? = null
        ): NotificationSettings {
            val intentSettings = NotificationSettings.fromIntent(intent, notificationSettings)
            return NotificationSettings.newerOf(
                intentSettings,
                context?.let { cachedNotificationSettings(it) }
            )
        }

        fun syncNotificationSettings(context: Context, intent: Intent) {
            notificationSettings = NotificationSettings.fromIntent(intent, notificationSettings)
                .withUpdatedAtIfMissing()
            cacheNotificationSettings(context, notificationSettings)
            Log.d(
                "OppoPods",
                "Notification settings synced in MiBluetooth: batteryIsland=${notificationSettings.showConnectionBatteryIsland}, popup=${notificationSettings.showConnectionPopup}, popupDismiss=${notificationSettings.connectionPopupDismissSeconds}s, show=${notificationSettings.showConnectionNotification}, island=${notificationSettings.notificationIslandStyle}, updatedAt=${notificationSettings.updatedAt}"
            )
        }

        fun deleteIntent(context: Context, bluetoothDevice: BluetoothDevice): PendingIntent? {
            val intent = Intent("com.android.bluetooth.headset.notification.cancle").apply {
                setPackage("com.android.bluetooth")
                putExtra("android.bluetooth.device.extra.DEVICE", bluetoothDevice)
            }
            return PendingIntent.getBroadcast(context, 0, intent, PENDING_INTENT_FLAGS)
        }

        fun createPodsNotification(
            bluetoothDevice: BluetoothDevice?,
            context: Context,
            batteryParams: BatteryParams,
            showNotificationAsIsland: Boolean = notificationSettings.showNotificationAsIsland,
            rfcommConnected: Boolean = true
        ) {
            val miheadset_notification_Box = context.resources.getIdentifier("miheadset_notification_Box", "string", "com.xiaomi.bluetooth")
            val miheadset_notification_LeftEar = context.resources.getIdentifier("miheadset_notification_LeftEar", "string", "com.xiaomi.bluetooth")
            val miheadset_notification_RightEar = context.resources.getIdentifier("miheadset_notification_RightEar", "string", "com.xiaomi.bluetooth")
            val miheadset_notification_Disconnect = context.resources.getIdentifier("miheadset_notification_Disconnect", "string", "com.xiaomi.bluetooth")
            val system_notification_accent_color = context.resources.getIdentifier("system_notification_accent_color", "color", "android")
            if (bluetoothDevice == null) {
                Log.e("OppoPods", "createPodsNotification: btDevice null")
                return
            }
            try {
                val address: String = bluetoothDevice.address
                var alias: String? = bluetoothDevice.alias
                if (alias?.isEmpty() == true) {
                    alias = bluetoothDevice.name
                }
                val notificationTitle = if (rfcommConnected) {
                    alias ?: ""
                } else {
                    "${alias ?: ""}（已断开）"
                }

                val caseBattStr = if (batteryParams.case != null && batteryParams.case!!.isConnected)
                    "${context.resources.getString(miheadset_notification_Box)}：${batteryParams.case!!.battery} %" +
                            "${if (batteryParams.case!!.isCharging) " ⚡" else ""}\n"
                else ""
                val leftEar = if (batteryParams.left != null && batteryParams.left!!.isConnected)
                    "${context.resources.getString(miheadset_notification_LeftEar)}：${batteryParams.left!!.battery} %" +
                        (if (batteryParams.left!!.isCharging) " ⚡" else "")
                else ""
                val leftToRight = if (batteryParams.left?.isConnected == true && batteryParams.right?.isConnected == true) " | " else ""
                val rightEar = if (batteryParams.right != null && batteryParams.right!!.isConnected)
                    "$leftToRight${context.resources.getString(miheadset_notification_RightEar)}：${batteryParams.right!!.battery} %" +
                        (if (batteryParams.right!!.isCharging) " ⚡" else "")
                else ""

                val contentText: String = caseBattStr + leftEar + rightEar
                val notificationManager = context.getSystemService("notification") as NotificationManager
                val activeNotificationTag = notificationTag(address)
                val previousIslandStyle = lastNotificationIslandStyle[address]
                cancelNotificationByTag(notificationManager, legacyIslandNotificationTag(address))
                if (previousIslandStyle == true && !showNotificationAsIsland) {
                    cancelNotificationByTag(notificationManager, activeNotificationTag)
                }
                notificationManager.createNotificationChannel(
                    NotificationChannel(
                        CONNECTION_CHANNEL_ID,
                        CONNECTION_CHANNEL_NAME,
                        NotificationManager.IMPORTANCE_DEFAULT
                    ).apply {
                        setSound(null, null)
                        setAllowBubbles(true)
                    }
                )
                val bundle = Bundle()
                bundle.putParcelable("Device", bluetoothDevice)
                val intent = Intent("com.android.bluetooth.headset.notification")
                intent.setPackage("com.android.bluetooth")
                intent.putExtra("btData", bundle)
                intent.putExtra("disconnect", "1")
                intent.setIdentifier("BTHeadset$address")
                val disconnectAction = Notification.Action(
                    285737079,
                    context.resources.getString(miheadset_notification_Disconnect),
                    PendingIntent.getBroadcast(context, 0, intent, PENDING_INTENT_FLAGS)
                )
                // 循环切换降噪模式：降噪 → 自适应 → 通透 → 关，指定 package 确保广播路由到 com.android.bluetooth 进程
                val ancCycleIntent = Intent(OppoPodsAction.ACTION_CYCLE_ANC)
                ancCycleIntent.setPackage("com.android.bluetooth")
                ancCycleIntent.setIdentifier("BTHeadset$address")
                // 耳机图标从模块 assets 加载：release 下 resopt 折叠资源名，getIdentifier 返回 0
                // 取不到图；assets 按名加载不受影响。
                val boxBitmap = ModuleImageUtil.bitmap(context, "img_box.png")
                    ?: BitmapFactory.decodeResource(context.resources, android.R.drawable.stat_sys_data_bluetooth)
                val headsetIcon = Icon.createWithBitmap(boxBitmap)
                // 按钮文案走 R 常量 ID（resopt 保留 ID），仅需一个模块 context。
                val moduleContext = context.createPackageContext(
                    "io.github.zuohl.hyperpods", Context.CONTEXT_IGNORE_SECURITY
                )
                val pendingIntent = PendingIntent.getActivity(
                    context,
                    0,
                    Intent("chen.action.oppopods.show_pods_ui").apply {
                        setClassName("io.github.zuohl.hyperpods", "io.github.zuohl.hyperpods.PopupActivity")
                    },
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                val focusExtras = if (showNotificationAsIsland) FocusNotification.buildV3 {
                    val logo = createPicture("key_headset", headsetIcon)
                    enableFloat = true
                    ticker = notificationTitle
                    updatable = true
//                    tickerPic = logo

                    iconTextInfo {
                        animIconInfo{
                            type = 0
                            src = logo
                        }
                        title = notificationTitle
                        content = contentText
                    }

                    island {
                        islandProperty = 1
                        bigIslandArea {
                            imageTextInfoLeft {
                                type = 1
                                picInfo {
                                    type = 1
                                    pic = logo
                                }
                            }
                            imageTextInfoRight {
                                type = 2
                                textInfo {
                                    title = notificationTitle
                                    content = contentText
                                }
                            }
                        }
                    }


                    textButton {
                        addActionInfo {
                            val ancLabel = moduleContext.getString(R.string.cycle_anc)
                            val ancAction = Notification.Action.Builder(
                                Icon.createWithResource(context, android.R.drawable.ic_lock_silent_mode),
                                ancLabel,
                                PendingIntent.getBroadcast(context, 1, ancCycleIntent, PENDING_INTENT_FLAGS)
                            ).build()
                            action = createAction("key_anc_cycle", ancAction)
                            actionTitle = ancLabel
                        }
                        addActionInfo {
                            val disconnectLabel = moduleContext.getString(R.string.notification_btn_disconnect)
                            val disconnectIntent = Intent("com.android.bluetooth.headset.notification").apply {
                                setPackage("com.android.bluetooth")
                                putExtra("btData", bundle)
                                putExtra("disconnect", "1")
                                setIdentifier("BTHeadset$address")
                            }
                            val disconnectAction = Notification.Action.Builder(
                                Icon.createWithResource(context, android.R.drawable.ic_delete),
                                disconnectLabel,
                                PendingIntent.getBroadcast(context, 2, disconnectIntent, PENDING_INTENT_FLAGS)
                            ).build()
                            action = createAction("key_disconnect", disconnectAction)
                            actionTitle = disconnectLabel
                        }
                    }
                } else null
                // AOD 息屏显示：左右耳电量拼合后注入 aodTitle
                if (focusExtras != null) {
                    val aodParts = mutableListOf<String>()
                    if (batteryParams.left?.isConnected == true)
                        aodParts.add("L ${batteryParams.left!!.battery}%")
                    if (batteryParams.right?.isConnected == true)
                        aodParts.add("R ${batteryParams.right!!.battery}%")
                    val aodTitle = aodParts.joinToString(" | ")
                    try {
                        val json = org.json.JSONObject(focusExtras.getString("miui.focus.param") ?: "{}")
                        val pv2 = json.optJSONObject("param_v2") ?: org.json.JSONObject()
                        pv2.put("aodTitle", aodTitle)
                        pv2.put("aodPic", "key_headset")
                        json.put("param_v2", pv2)
                        focusExtras.putString("miui.focus.param", json.toString())
                    } catch (_: Exception) {}
                }
                notificationManager.notifyAsUser(
                    activeNotificationTag,
                    NOTIFICATION_ID,
                    Notification.Builder(context, CONNECTION_CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                        .setWhen(0L)
                        .setTicker(notificationTitle)
                        .setDefaults(-1)
                        .setContentTitle(notificationTitle)
                        .setContentText(contentText)
                        .setContentIntent(pendingIntent)
                        .setDeleteIntent(deleteIntent(context, bluetoothDevice))
                        .setColor(context.getColor(system_notification_accent_color))
                        .addAction(disconnectAction)
                        .apply { focusExtras?.let { addExtras(it) } }
                        .setVisibility(Notification.VISIBILITY_PUBLIC)
                        .build(),
                    SystemApisUtils.getUserAllUserHandle()
                )
                lastNotificationIslandStyle[address] = showNotificationAsIsland
                lastNotificationDevice = bluetoothDevice
                lastNotificationBatteryParams = batteryParams
                lastNotificationRfcommConnected = rfcommConnected
            } catch (e: Exception) {
                Log.e("OppoPods", "Failed to create Pod Notification", e)
            }
        }

        fun cancelNotification(bluetoothDevice: BluetoothDevice, context: Context) {
            try {
                val address = bluetoothDevice.address
                if (address.isNotEmpty()) {
                    val notificationManager = context.getSystemService("notification") as NotificationManager
                    cancelNotificationByTag(notificationManager, notificationTag(address))
                    cancelNotificationByTag(notificationManager, legacyIslandNotificationTag(address))
                    lastNotificationIslandStyle.remove(address)
                    lastNotificationDevice = null
                    lastNotificationBatteryParams = null
                }
            } catch (e: Exception) {
                Log.e("OppoPods", "Failed to cancel Pod Notification!", e)
            }
        }


        fun setupNotificationReceiver(context: Context) {
            if (notificationReceiver != null) return
            notificationSettings = loadNotificationSettings(context)
            cacheNotificationSettings(context, notificationSettings)
            if (!notificationSettings.showConnectionNotification) {
                cancelAllPodsNotifications(context)
            }

            val broadcastReceiver = object : BroadcastReceiver() {
                override fun onReceive(p0: Context?, p1: Intent?) {
                    val action = p1?.action ?: return
                    when (action) {
                        "chen.action.oppopods.sendstrongtoast" -> {
                            val address = p1.getStringExtra("address")
                            if (!address.isNullOrBlank()) activePodAddresses.add(address.uppercase())
                            val settings = effectiveNotificationSettings(p1, context)
                            if (!settings.showConnectionBatteryIsland) {
                                Log.d("OppoPods", "Temporary battery island suppressed by settings")
                                return
                            }
                            val batteryParams = p1.getParcelableExtra("batteryParams", BatteryParams::class.java) ?: return
                            val deviceName = p1.getStringExtra("device_name")
                            FocusIslandUtil.showBatteryIsland(context, batteryParams, deviceName, address)
                        }
                        "chen.action.oppopods.updatepodsnotification" -> {
                            val batteryParams = p1.getParcelableExtra("batteryParams", BatteryParams::class.java)
                            val device = p1.getParcelableExtra("device", BluetoothDevice::class.java)
                            device?.address?.let { activePodAddresses.add(it.uppercase()) }
                            val settings = effectiveNotificationSettings(p1, context)
                            if (settings.showConnectionNotification && batteryParams != null) {
                                createPodsNotification(
                                    device,
                                    context,
                                    batteryParams,
                                    settings.showNotificationAsIsland,
                                    p1.getBooleanExtra(OppoPodsAction.EXTRA_RFCOMM_CONNECTED, true)
                                )
                            } else if (device != null) {
                                cancelNotification(device, context)
                            } else {
                                cancelAllPodsNotifications(context)
                            }
                        }
                        "chen.action.oppopods.cancelpodsnotification" -> {
                            val device = p1.getParcelableExtra("device", BluetoothDevice::class.java) ?: return
                            activePodAddresses.remove(device.address.uppercase())
                            cancelNotification(device, context)
                        }
                        OppoPodsAction.ACTION_PODS_CONNECTED -> {
                            val address = p1.getStringExtra("address")
                            if (!address.isNullOrBlank()) activePodAddresses.add(address.uppercase())
                        }
                        OppoPodsAction.ACTION_PODS_DISCONNECTED -> {
                            val address = p1.getStringExtra("address")
                            if (!address.isNullOrBlank()) activePodAddresses.remove(address.uppercase())
                        }
                        OppoPodsAction.ACTION_PODS_ANC_CHANGED -> {
                            localAncMode = p1.getIntExtra("status", 1)
                        }
                        OppoPodsAction.ACTION_NOTIFICATION_SETTINGS_CHANGED -> {
                            syncNotificationSettings(context, p1)
                            val lastDevice = lastNotificationDevice
                            val lastBatteryParams = lastNotificationBatteryParams
                            if (!notificationSettings.showConnectionNotification) {
                                if (lastDevice != null) cancelNotification(lastDevice, context) else cancelAllPodsNotifications(context)
                            } else if (lastDevice != null && lastBatteryParams != null) {
                                createPodsNotification(
                                    lastDevice,
                                    context,
                                    lastBatteryParams,
                                    notificationSettings.showNotificationAsIsland,
                                    lastNotificationRfcommConnected
                                )
                            }
                        }
                        OppoPodsAction.ACTION_CYCLE_ANC -> {
                            val profile = runCatching { DeviceProfileStore.resolveProfile(context, prefs) }.getOrNull()
                            val adaptiveEnabled = profile?.adaptiveVisible == true
                            localAncMode = when (localAncMode) {
                                2 -> if (adaptiveEnabled) 4 else 3
                                4 -> 3
                                3 -> 1
                                else -> 2
                            }
                            Intent(OppoPodsAction.ACTION_ANC_SELECT).apply {
                                setPackage("com.android.bluetooth")
                                putExtra("status", localAncMode)
                                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                                p0?.sendBroadcast(this)
                            }
                        }
                    }
                }
            }

            val intentFilter = IntentFilter("chen.action.oppopods.sendstrongtoast").apply {
                addAction("chen.action.oppopods.updatepodsnotification")
                addAction("chen.action.oppopods.cancelpodsnotification")
                addAction(OppoPodsAction.ACTION_PODS_CONNECTED)
                addAction(OppoPodsAction.ACTION_PODS_DISCONNECTED)
                addAction(OppoPodsAction.ACTION_CYCLE_ANC)
                addAction(OppoPodsAction.ACTION_PODS_ANC_CHANGED)
                addAction(OppoPodsAction.ACTION_NOTIFICATION_SETTINGS_CHANGED)
            }
            context.registerReceiver(broadcastReceiver, intentFilter, Context.RECEIVER_EXPORTED)
            notificationReceiver = broadcastReceiver
            notificationReceiverContext = context.applicationContext ?: context
            Log.d("HyperPods", "MiBluetoothToastHook notification receiver registered successfully")
        }

        // 1. Hook MiuiBluetoothNotification constructors (all variants across MIUI/HyperOS)
        findClassOrNull("com.android.bluetooth.ble.app.MiuiBluetoothNotification")?.declaredConstructors?.forEach { ctor ->
            runCatching {
                hookConstructorAfter(ctor) {
                    val context = runCatching {
                        getObjectField(instance, "mContext") as? Context
                    }.getOrNull() ?: args.firstOrNull { it is Context } as? Context
                    if (context != null) setupNotificationReceiver(context)
                }
            }
        }

        // 2. Hook BluetoothHeadsetService.onCreate as reliable fallback for Context acquisition
        findClassOrNull("com.android.bluetooth.ble.app.headset.BluetoothHeadsetService")?.let { serviceClass ->
            runCatching {
                hookAfter(findMethod(serviceClass.name, "onCreate")) {
                    val ctx = instance as? Context ?: return@hookAfter
                    setupNotificationReceiver(ctx)
                }
            }
        }

        // 3. HyperOS 4.0 status-bar icon protection:
        // Hook HeadsetIconShowManager (com.android.bluetooth.ble.app.i) getCachedDeviceId (method s)
        findClassOrNull("com.android.bluetooth.ble.app.i")?.let { iconManagerClass ->
            iconManagerClass.declaredMethods.filter {
                it.name == "s" && it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == String::class.java &&
                it.returnType == String::class.java
            }.forEach { method ->
                runCatching {
                    hookBefore(method) {
                        val address = args[0] as? String
                        if (address != null && (address.uppercase() in activePodAddresses || activePodAddresses.isNotEmpty())) {
                            val fakeId = FakeDeviceConfig.deviceId(prefs)
                            result = fakeId
                            Log.d("HyperPods", "HeadsetIconShowManager.s(deviceId) forced address=$address fakeId=$fakeId")
                        }
                    }
                    Log.d("HyperPods", "Hooked HeadsetIconShowManager.s in com.xiaomi.bluetooth")
                }
            }
        }

        // 4. Hook StatusBarManager.setIconVisibility in com.xiaomi.bluetooth:
        // Suppress HeadsetIconShowManager from hiding wireless_headset while pod is connected
        findClassOrNull("android.app.StatusBarManager")?.let { sbmClass ->
            sbmClass.declaredMethods.filter {
                it.name == "setIconVisibility" && it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == String::class.java &&
                it.parameterTypes[1] == Boolean::class.javaPrimitiveType
            }.forEach { method ->
                runCatching {
                    hookBefore(method) {
                        val slot = args[0] as? String
                        val visible = args[1] as? Boolean ?: true
                        if (slot == "wireless_headset" && !visible) {
                            if (activePodAddresses.isNotEmpty()) {
                                Log.d("HyperPods", "Suppressed wireless_headset hiding in com.xiaomi.bluetooth (active pod connected)")
                                result = null
                            }
                        }
                    }
                    Log.d("HyperPods", "Hooked StatusBarManager.setIconVisibility in com.xiaomi.bluetooth")
                }
            }
        }
    }

    override fun onHotReloading() {
        notificationReceiver?.let { receiver ->
            runCatching { notificationReceiverContext?.unregisterReceiver(receiver) }
        }
        notificationReceiver = null
        notificationReceiverContext = null
    }
}
