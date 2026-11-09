package io.github.zuohl.hyperpods.hook

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.zuohl.hyperpods.pods.PodController
import io.github.zuohl.hyperpods.utils.miuiStrongToast.data.BatteryParams
import io.github.zuohl.hyperpods.utils.miuiStrongToast.data.OppoPodsAction
import io.github.zuohl.hyperpods.utils.miuiStrongToast.data.PodParams
import io.github.zuohl.hyperpods.utils.miuiStrongToast.data.batteryStatusCompat
import java.util.WeakHashMap

/**
 * 新版 HyperOS 设置详情页 Hook（路径 B：BluetoothDeviceDetailsFragment 数据驱动页）。
 *
 * 让第三方耳机（OPPO / QCY / vivo 等）在标准的蓝牙设备详情页显示「三路独立环形电量 / 官方动态降噪控制卡片」。
 * 配合设置中的切换开关，用户可在“原生数据驱动页（推荐）”与“经典小米专属页（MiuiHeadset）”之间自由选择。
 */
@SuppressLint("MissingPermission")
object SettingsDetailsHook : HookContext() {
    private const val TAG = "HyperPods-Details"

    // 自定义 ANC 控件 settingId（避免与系统原生 provider 冲突）
    private const val HYPERPODS_ANC_SETTING_ID = 990001
    private const val PREFS_NAME = "hyperpods_details_state"
    private const val REFRESH_INTERVAL_MS = 3_000L

    // 三个降噪档位：关闭 / 通透 / 降噪
    private val ANC_TOGGLE_LABELS = arrayOf("关闭", "通透", "降噪")

    private val knownPodAddresses = linkedSetOf<String>()
    private val fragments = WeakHashMap<Any, Boolean>()
    private var context: Context? = null
    private var receiverRegistered = false
    private var statusReceiver: BroadcastReceiver? = null
    private var currentAddress: String? = null
    private var currentName: String? = null
    private var currentBattery: BatteryParams = BatteryParams()
    private var currentAnc = 1
    private var currentCachedDevice: Any? = null

    private val refreshHandler = Handler(Looper.getMainLooper())
    private var refreshLoopStarted = false
    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (fragments.keys.any { isSupportedFragment(it) }) {
                requestBluetoothStatus("details-periodic")
                refreshHandler.postDelayed(this, REFRESH_INTERVAL_MS)
            } else {
                refreshLoopStarted = false
                Log.d(TAG, "details periodic refresh stopped: no active fragment")
            }
        }
    }

    override fun onHook() {
        hookAdvancedHeaderGate()
        hookBatteryMetadata()
        hookBatteryLevels()
        hookConfigInjection()
        hookFragmentLifecycle()
    }

    override fun onHotReloading() {
        refreshHandler.removeCallbacksAndMessages(null)
        refreshLoopStarted = false
        statusReceiver?.let { receiver -> runCatching { context?.unregisterReceiver(receiver) } }
        statusReceiver = null
        receiverRegistered = false
        context = null
    }

    // -----------------------------------------------------------------------------------------
    // 1. 强制高级头部可见（原生耳机图与三路电量环的入口）
    // -----------------------------------------------------------------------------------------
    private fun hookAdvancedHeaderGate() {
        runCatching {
            // BluetoothUtils.isAdvancedDetailsHeader(BluetoothDevice): int -> 0 表示开启高级头部
            hookAfter(findMethod("com.android.settingslib.bluetooth.BluetoothUtils", "isAdvancedDetailsHeader", BluetoothDevice::class.java)) {
                val device = args[0] as? BluetoothDevice
                if (isSupportedPod(device)) {
                    Log.d(TAG, "isAdvancedDetailsHeader forced 0 for device=${device?.address}")
                    result = 0
                }
            }
        }.onFailure { Log.w(TAG, "hook isAdvancedDetailsHeader skipped", it) }
    }

    // -----------------------------------------------------------------------------------------
    // 2. 注入电池 metadata（驱动原生高级头部的左/右/盒电量与充电状态）
    // -----------------------------------------------------------------------------------------
    private fun hookBatteryMetadata() {
        // BluetoothUtils.getIntMetaData(BluetoothDevice, int)
        runCatching {
            hookAfter(findMethod("com.android.settingslib.bluetooth.BluetoothUtils", "getIntMetaData", BluetoothDevice::class.java, Integer.TYPE)) {
                val device = args[0] as? BluetoothDevice
                val key = args[1] as? Int ?: return@hookAfter
                if (!isSupportedPod(device)) return@hookAfter
                val level = when (key) {
                    10 -> podBatteryLevel(currentBattery.left)
                    11 -> podBatteryLevel(currentBattery.right)
                    12 -> podBatteryLevel(currentBattery.case).takeIf { it >= 0 } ?: 50
                    else -> return@hookAfter
                }
                Log.d(TAG, "getIntMetaData($key) -> $level")
                result = level
            }
        }.onFailure { Log.w(TAG, "hook getIntMetaData skipped", it) }

        // BluetoothUtils.getBooleanMetaData(BluetoothDevice, int) -> 充电状态与 TWS 标识
        runCatching {
            hookAfter(findMethod("com.android.settingslib.bluetooth.BluetoothUtils", "getBooleanMetaData", BluetoothDevice::class.java, Integer.TYPE)) {
                val device = args[0] as? BluetoothDevice
                val key = args[1] as? Int ?: return@hookAfter
                if (!isSupportedPod(device)) return@hookAfter
                val value = when (key) {
                    6 -> true // isUntetheredHeadset
                    13 -> currentBattery.left?.isCharging == true
                    14 -> currentBattery.right?.isCharging == true
                    15 -> currentBattery.case?.isCharging == true
                    else -> return@hookAfter
                }
                Log.d(TAG, "getBooleanMetaData($key) -> $value")
                result = value
            }
        }.onFailure { Log.w(TAG, "hook getBooleanMetaData skipped", it) }
    }

    // -----------------------------------------------------------------------------------------
    // 3. 注入 BatteryLevelsInfo（原生高级头部直接读取的对象）
    // -----------------------------------------------------------------------------------------
    private fun hookBatteryLevels() {
        runCatching {
            hookAfter(findMethod("com.android.settingslib.bluetooth.CachedBluetoothDevice", "getBatteryLevelsInfo")) {
                val cached = instance
                val device = runCatching { callMethod(cached, "getDevice") as? BluetoothDevice }.getOrNull()
                if (isSupportedPod(device)) {
                    val info = makeBatteryLevelsInfo()
                    Log.d(TAG, "getBatteryLevelsInfo -> $info")
                    result = info
                }
            }
        }.onFailure { Log.w(TAG, "hook getBatteryLevelsInfo skipped", it) }
    }

    // -----------------------------------------------------------------------------------------
    // 4. 注入降噪控件（向 DeviceSettingsConfig 注入原生样式的 MultiTogglePreference）
    // -----------------------------------------------------------------------------------------
    private fun hookConfigInjection() {
        // DeviceSettingServiceConnection.getDeviceSettingsConfig(Continuation) -> DeviceSettingsConfig
        runCatching {
            val targetClass = "com.android.settingslib.bluetooth.devicesettings.data.repository.DeviceSettingServiceConnection"
            val method = findMethodByParamCount(targetClass, "getDeviceSettingsConfig", 1)
            hookAfter(method) {
                val original = result
                val augmented = runCatching { buildAugmentedConfig(original) }.getOrNull()
                if (augmented != null) {
                    Log.d(TAG, "getDeviceSettingsConfig augmented with ANC card (original=$original)")
                    result = augmented
                }
            }
        }.onFailure { Log.w(TAG, "hook getDeviceSettingsConfig skipped", it) }

        // DeviceSettingServiceConnection.updateDeviceSettings(int settingId, DeviceSettingPreferenceState, Continuation)
        runCatching {
            val targetClass = "com.android.settingslib.bluetooth.devicesettings.data.repository.DeviceSettingServiceConnection"
            hookBefore(findMethodByParamCount(targetClass, "updateDeviceSettings", 3)) {
                val settingId = args[0] as? Int ?: return@hookBefore
                if (settingId != HYPERPODS_ANC_SETTING_ID) return@hookBefore
                val state = args[1]
                val toggleState = runCatching<Int?> {
                    callMethod(state, "getState") as? Int
                }.getOrNull() ?: runCatching<Int?> { getObjectField(state, "mState") as? Int }.getOrNull() ?: 0
                // toggleState: 0=关闭, 1=通透, 2=降噪
                val targetMode = when (toggleState) {
                    1 -> 3 // 通透
                    2 -> 2 // 降噪
                    else -> 1 // 关闭
                }
                Log.d(TAG, "updateDeviceSettings ANC toggleState=$toggleState -> targetMode=$targetMode")
                currentAnc = targetMode
                sendAncSelectBroadcast(targetMode)
                result = null // 拦截原生流程，已由模块下发指令
            }
        }.onFailure { Log.w(TAG, "hook updateDeviceSettings skipped", it) }
    }

    private fun buildAugmentedConfig(original: Any?): Any? {
        val configClass = findClass("com.android.settingslib.bluetooth.devicesettings.DeviceSettingsConfig")
        val itemClass = findClass("com.android.settingslib.bluetooth.devicesettings.DeviceSettingItem")

        val originalMain: List<*> = runCatching<List<Any>> { callMethod(original, "getMainContentItems") as List<Any> }.getOrNull() ?: emptyList<Any>()
        val originalMore: List<*> = runCatching<List<Any>> { callMethod(original, "getMoreSettingsItems") as List<Any> }.getOrNull() ?: emptyList<Any>()
        val originalHelp = runCatching<Any?> { callMethod(original, "getMoreSettingsHelpItem") }.getOrNull()
        val originalGroups: List<*>? = runCatching<List<Any>> { callMethod(original, "getSettingGroups") as List<Any> }.getOrNull()
        val originalExtras = runCatching<Bundle> { callMethod(original, "getExtras") as Bundle }.getOrNull() ?: Bundle.EMPTY

        val ancItem = buildAncPreference() ?: return original
        val newMain = ArrayList<Any>(originalMain.size + 1)
        for (e in originalMain) if (e != null) newMain.add(e)
        newMain.add(ancItem)
        val newMore = ArrayList<Any>(originalMore.size)
        for (e in originalMore) if (e != null) newMore.add(e)

        val ctor = configClass.getDeclaredConstructor(
            List::class.java, List::class.java, itemClass, List::class.java, Bundle::class.java
        ).apply { isAccessible = true }
        return ctor.newInstance(newMain, newMore, originalHelp, originalGroups, originalExtras)
    }

    private fun buildAncPreference(): Any? {
        val prefClass = findClass("com.android.settingslib.bluetooth.devicesettings.MultiTogglePreference")
        val toggleClass = findClass("com.android.settingslib.bluetooth.devicesettings.ToggleInfo")

        val toggles = ArrayList<Any>(ANC_TOGGLE_LABELS.size)
        for (label in ANC_TOGGLE_LABELS) {
            val icon = makeToggleIcon(label)
            val tc = toggleClass.getDeclaredConstructor(String::class.java, Bitmap::class.java, Bundle::class.java)
                .apply { isAccessible = true }
            toggles.add(tc.newInstance(label, icon, Bundle.EMPTY))
        }

        // currentAnc: 1=关(0), 3=通透(1), 2=降噪(2)
        val stateIndex = when (currentAnc) {
            3 -> 1
            2 -> 2
            else -> 0
        }
        val ctor = prefClass.getDeclaredConstructor(
            String::class.java, List::class.java, Integer.TYPE,
            java.lang.Boolean.TYPE, java.lang.Boolean.TYPE, Bundle::class.java
        ).apply { isAccessible = true }
        return ctor.newInstance("降噪控制", toggles, stateIndex, true, true, Bundle.EMPTY)
    }

    private fun makeToggleIcon(label: String): Bitmap {
        val bmp = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.DKGRAY
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        canvas.drawCircle(24f, 24f, 18f, paint)
        return bmp
    }

    // -----------------------------------------------------------------------------------------
    // 5. 页面生命周期与广播监听
    // -----------------------------------------------------------------------------------------
    private fun hookFragmentLifecycle() {
        val fragmentClassNames = listOf(
            "com.android.settings.bluetooth.BluetoothDeviceDetailsFragment",
            "com.android.settings.bluetooth.BluetoothDetailsConfigurableFragment"
        )
        fragmentClassNames.forEach { className ->
            val cls = findClassOrNull(className) ?: return@forEach
            runCatching {
                hookAfter(findMethod(cls.name, "onStart")) {
                    val frag = instance ?: return@hookAfter
                    if (!isSupportedFragment(frag)) return@hookAfter
                    fragments[frag] = true
                    val ctx = runCatching { callMethod(frag, "getContext") as? Context }.getOrNull()
                    if (ctx != null) {
                        context = ctx.applicationContext ?: ctx
                        registerStatusReceiver(ctx)
                        loadState()
                    }
                    requestBluetoothStatus("onStart")
                    startRefreshLoop()
                }
                hookAfter(findMethod(cls.name, "onStop")) {
                    val frag = instance ?: return@hookAfter
                    fragments.remove(frag)
                    if (fragments.isEmpty()) {
                        stopRefreshLoop()
                    }
                }
            }.onFailure { Log.w(TAG, "hook $className lifecycle skipped", it) }
        }
    }

    private fun startRefreshLoop() {
        if (refreshLoopStarted) return
        refreshLoopStarted = true
        refreshHandler.postDelayed(refreshRunnable, REFRESH_INTERVAL_MS)
    }

    private fun stopRefreshLoop() {
        refreshHandler.removeCallbacksAndMessages(null)
        refreshLoopStarted = false
    }

    private fun registerStatusReceiver(ctx: Context) {
        if (receiverRegistered) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent == null) return
                val action = intent.action ?: return
                when (action) {
                    OppoPodsAction.ACTION_PODS_CONNECTED -> {
                        val address = intent.getStringExtra("address")
                        val name = intent.getStringExtra("device_name")
                        if (address != null) {
                            currentAddress = address
                            knownPodAddresses.add(address.uppercase())
                        }
                        if (name != null) currentName = name
                        saveState(context)
                        notifyHeaderRefresh()
                    }
                    OppoPodsAction.ACTION_PODS_DISCONNECTED -> {
                        saveState(context)
                        notifyHeaderRefresh()
                    }
                    OppoPodsAction.ACTION_PODS_BATTERY_CHANGED -> {
                        val address = intent.getStringExtra("address")
                        if (address != null) {
                            currentAddress = address
                            knownPodAddresses.add(address.uppercase())
                        }
                        currentBattery = intent.batteryStatusCompat() ?: currentBattery
                        saveState(context)
                        notifyHeaderRefresh()
                    }
                    OppoPodsAction.ACTION_PODS_ANC_CHANGED -> {
                        currentAnc = intent.getIntExtra("status", currentAnc)
                        saveState(context)
                        notifyHeaderRefresh()
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(OppoPodsAction.ACTION_PODS_CONNECTED)
            addAction(OppoPodsAction.ACTION_PODS_DISCONNECTED)
            addAction(OppoPodsAction.ACTION_PODS_BATTERY_CHANGED)
            addAction(OppoPodsAction.ACTION_PODS_ANC_CHANGED)
        }
        ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        statusReceiver = receiver
        receiverRegistered = true
        Log.d(TAG, "SettingsDetailsHook receiver registered")
    }

    private fun requestBluetoothStatus(reason: String) {
        val ctx = context ?: return
        val address = currentAddress ?: return
        val intent = Intent(OppoPodsAction.ACTION_REFRESH_STATUS).apply {
            setPackage("com.android.bluetooth")
            putExtra("address", address)
            putExtra("reason", reason)
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        }
        ctx.sendBroadcast(intent)
    }

    private fun sendAncSelectBroadcast(status: Int) {
        val ctx = context ?: return
        val intent = Intent(OppoPodsAction.ACTION_ANC_SELECT).apply {
            setPackage("com.android.bluetooth")
            putExtra("status", status)
            currentAddress?.let { putExtra("address", it) }
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        }
        ctx.sendBroadcast(intent)
        Log.d(TAG, "Sent ANC_SELECT status=$status address=$currentAddress")
    }

    private fun notifyHeaderRefresh() {
        runCatching {
            val cached = currentCachedDevice ?: return
            callMethod(cached, "refresh")
            Log.d(TAG, "notified CachedBluetoothDevice.refresh()")
        }
    }

    // -----------------------------------------------------------------------------------------
    // 设备支持与电量构造辅助
    // -----------------------------------------------------------------------------------------
    private fun isSupportedFragment(fragment: Any?): Boolean {
        val cached = runCatching { getObjectField(fragment, "cachedDevice") }.getOrNull()
            ?: runCatching { callMethod(fragment, "getCachedDevice") }.getOrNull()
        currentCachedDevice = cached
        val device = runCatching { callMethod(cached, "getDevice") as? BluetoothDevice }.getOrNull()
        return isSupportedPod(device)
    }

    private fun isSupportedPod(device: BluetoothDevice?): Boolean {
        if (device == null) return false
        val address = runCatching { device.address }.getOrNull()
        if (address != null && isKnownAddress(address)) return true
        val supports = PodController.supports(device)
        if (supports && address != null) {
            knownPodAddresses.add(address.uppercase())
            currentAddress = address
            currentName = runCatching { device.name ?: device.alias }.getOrNull()
        }
        return supports
    }

    private fun isKnownAddress(address: String): Boolean {
        val normalized = address.uppercase()
        return normalized == currentAddress?.uppercase() || normalized in knownPodAddresses
    }

    private fun podBatteryLevel(params: PodParams?): Int {
        if (params?.isConnected != true) return -1
        return params.battery.coerceIn(0, 100)
    }

    private fun makeBatteryLevelsInfo(): Any? {
        val left = podBatteryLevel(currentBattery.left)
        val right = podBatteryLevel(currentBattery.right)
        val case = podBatteryLevel(currentBattery.case)
        val overall = listOf(left, right, case).filter { it >= 0 }.maxOrNull() ?: -1
        val cls = findClass("com.android.settingslib.bluetooth.BatteryLevelsInfo")
        val ctor = cls.getDeclaredConstructor(Integer.TYPE, Integer.TYPE, Integer.TYPE, Integer.TYPE)
            .apply { isAccessible = true }
        return ctor.newInstance(left, right, case, overall)
    }

    private fun saveState(ctx: Context?) {
        val p = (ctx ?: context)?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return
        p.edit()
            .putString("address", currentAddress)
            .putString("name", currentName)
            .putInt("anc", currentAnc)
            .putInt("left_battery", currentBattery.left?.battery ?: 0)
            .putBoolean("left_charging", currentBattery.left?.isCharging == true)
            .putBoolean("left_connected", currentBattery.left?.isConnected == true)
            .putInt("right_battery", currentBattery.right?.battery ?: 0)
            .putBoolean("right_charging", currentBattery.right?.isCharging == true)
            .putBoolean("right_connected", currentBattery.right?.isConnected == true)
            .putInt("case_battery", currentBattery.case?.battery ?: 0)
            .putBoolean("case_charging", currentBattery.case?.isCharging == true)
            .putBoolean("case_connected", currentBattery.case?.isConnected == true)
            .apply()
    }

    private fun loadState() {
        val p = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return
        currentAddress = p.getString("address", currentAddress)
        currentName = p.getString("name", currentName)
        currentAnc = p.getInt("anc", currentAnc)
        currentAddress?.let { knownPodAddresses.add(it.uppercase()) }
        val hasSavedBattery = p.getBoolean("left_connected", false) ||
            p.getBoolean("right_connected", false) ||
            p.getBoolean("case_connected", false)
        if (hasSavedBattery) {
            currentBattery = BatteryParams(
                left = PodParams(p.getInt("left_battery", 0), p.getBoolean("left_charging", false), p.getBoolean("left_connected", false), 0),
                right = PodParams(p.getInt("right_battery", 0), p.getBoolean("right_charging", false), p.getBoolean("right_connected", false), 0),
                case = PodParams(p.getInt("case_battery", 0), p.getBoolean("case_charging", false), p.getBoolean("case_connected", false), 0)
            )
        }
    }
}
