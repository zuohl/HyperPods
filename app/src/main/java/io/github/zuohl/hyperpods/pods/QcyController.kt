package io.github.zuohl.hyperpods.pods

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.zuohl.hyperpods.BuildConfig
import io.github.zuohl.hyperpods.config.QcyEqConfig
import io.github.zuohl.hyperpods.config.QcyEqPrefs
import android.util.Log
import io.github.zuohl.hyperpods.utils.SystemApisUtils
import io.github.zuohl.hyperpods.utils.miuiStrongToast.MiuiStrongToastUtil
import io.github.zuohl.hyperpods.utils.miuiStrongToast.data.BatteryParams
import io.github.zuohl.hyperpods.utils.miuiStrongToast.data.NotificationSettings
import io.github.zuohl.hyperpods.utils.miuiStrongToast.data.OppoPodsAction
import java.util.Locale
import java.util.ArrayDeque

@SuppressLint("MissingPermission", "StaticFieldLeak")
object QcyController {
    private const val TAG = "QcyController"
    private const val APP_UI_ACTIVE_TIMEOUT_MS = 75_000L
    private const val MAX_RECONNECT_ATTEMPTS = 4
    private const val RECONNECT_DELAY_MS = 2_000L
    private const val SCAN_WINDOW_MS = 12_000L
    /** If no battery packet arrives within this window while A2DP is up, the GATT data
     *  channel is dead (another app — e.g. the official QCY app — grabbed the LE link, or
     *  the link dropped without an onConnectionStateChange callback). Probe + reconnect. */
    private const val GATT_HEARTBEAT_INTERVAL_MS = 5_000L
    private const val GATT_STALE_AFTER_MS = 15_000L
    private const val SPATIAL_AUDIO_OFF = 0
    private const val SPATIAL_AUDIO_HEAD_TRACKING = 2

    private var context: Context? = null
    private var prefs: SharedPreferences? = null
    private var classicDevice: BluetoothDevice? = null
    private var leDevice: BluetoothDevice? = null
    private var gatt: BluetoothGatt? = null
    private var commandCharacteristic: BluetoothGattCharacteristic? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null
    private var batteryCharacteristic: BluetoothGattCharacteristic? = null
    private var receiverRegistered = false
    private var appUiActive = false
    private var appUiActiveUntilMs = 0L
    private var showedConnectedToast = false
    private val pendingNotificationCharacteristics = ArrayDeque<BluetoothGattCharacteristic>()
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private val scanHandler = Handler(Looper.getMainLooper())
    private var reconnectAttempts = 0
    private var reconnectPending = false
    private var scanning = false
    @Volatile private var lastBatteryReceivedMs: Long = 0L
    private val heartbeatHandler = Handler(Looper.getMainLooper())
    private var heartbeatRunnable: Runnable? = null
    /** Last LE MACs seen in a matching QCY advertisement, used to resolve the GATT peer. */
    @Volatile private var lastAdvControlAddress: String? = null
    @Volatile private var lastAdvOtherAddress: String? = null
    /** The advertisement's *source* MAC is the actual LE (GATT) identity. On the QCY
     *  Crossky C50S the classic peer is C4:33:96:10:64:23 while the LE peer advertises as
     *  C4:33:96:10:64:76 — same prefix, only the last byte differs. */
    @Volatile private var lastAdvSourceAddress: String? = null

    private var currentBatteryParams: BatteryParams? = null
    private var currentAnc: Int = 1
    private var currentGameMode: Boolean = false
    private var currentSpatialAudioMode: Int = SPATIAL_AUDIO_OFF
    private var currentDualDeviceConnection: Boolean = false
    private var currentLdac: Boolean = false
    private var currentDynamicEq: Boolean = false
    private var currentSleepMode: Boolean = false
    private var currentAdaptiveVolume: Boolean = false
    private var currentEqPreset: Int = QcyEqPreset.DEFAULT
    private var currentCustomEqGains: IntArray = IntArray(QcyOfficialEqCurves.customFrequencies.size)
    private var lastWrittenEqPreset: Int? = null
    private var lastWrittenCustomEqGains: IntArray? = null
    private var regularBatteryLevelUnsupported = false
    private var currentTransparencyVocalEnhancement: Boolean = false
    private var currentAddress: String? = null
    private var currentDeviceName: String? = null
    private var connecting = false
    private var connected = false

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handleScanResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach(::handleScanResult)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            Log.w(TAG, "QCY BLE scan failed errorCode=$errorCode")
        }
    }

    data class StatusSnapshot(
        val battery: BatteryParams?,
        val anc: Int,
        val transparencyVocalEnhancement: Boolean,
        val address: String?,
        val deviceName: String?,
        val connected: Boolean,
        val connecting: Boolean,
        val reconnectPending: Boolean,
    )

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            when (intent.action) {
                OppoPodsAction.ACTION_PODS_UI_INIT -> {
                    markAppUiActive()
                    if (!connected && classicDevice != null) {
                        // App reopened with a dead data channel (GATT dropped while the app
                        // was closed and hit the reconnect cap, or the broadcast that would
                        // have reconnected was gated). Kick a fresh attempt so the user sees
                        // live battery/ANC instead of a frozen snapshot.
                        reconnectAttempts = 0
                        scheduleReconnectIfNeeded("ui-init gatt-dead")
                    }
                    emitUiSnapshot()
                }
                OppoPodsAction.ACTION_PODS_UI_CLOSED -> {
                    appUiActive = false
                    appUiActiveUntilMs = 0L
                }
                OppoPodsAction.ACTION_REFRESH_STATUS -> queryStatus()
                OppoPodsAction.ACTION_ANC_SELECT -> {
                    when (intent.getIntExtra("status", currentAnc)) {
                        1 -> setAncMode(QcyNoiseMode.OFF)
                        2, 4, 5, 6, 7, 8 -> setAncMode(QcyNoiseMode.ANC)
                        3 -> setAncMode(QcyNoiseMode.TRANSPARENCY)
                    }
                }
                OppoPodsAction.ACTION_GAME_MODE_SET -> {
                    setGameMode(intent.getBooleanExtra("enabled", false))
                }
                OppoPodsAction.ACTION_SPATIAL_AUDIO_SET -> {
                    setSpatialAudioMode(intent.getIntExtra("mode", SPATIAL_AUDIO_OFF))
                }
                OppoPodsAction.ACTION_DUAL_DEVICE_CONNECTION_SET -> {
                    setDualDeviceConnection(intent.getBooleanExtra("enabled", false))
                }
                OppoPodsAction.ACTION_LDAC_SET -> {
                    setLdac(intent.getBooleanExtra("enabled", false))
                }
                OppoPodsAction.ACTION_DYNAMIC_EQ_SET -> {
                    setDynamicEq(intent.getBooleanExtra("enabled", false))
                }
                OppoPodsAction.ACTION_SLEEP_MODE_SET -> {
                    setSleepMode(intent.getBooleanExtra("enabled", false))
                }
                OppoPodsAction.ACTION_ADAPTIVE_VOLUME_SET -> {
                    setAdaptiveVolume(intent.getBooleanExtra("enabled", false))
                }
                OppoPodsAction.ACTION_EQ_PRESET_SET -> {
                    // Upstream UI sends "id" (OPPO convention); keep "preset" fallback.
                    val preset = intent.getIntExtra("id", intent.getIntExtra("preset", currentEqPreset))
                    setEqPreset(preset)
                }
                OppoPodsAction.ACTION_CUSTOM_EQ_SET -> {
                    setCustomEq(intent.getIntArrayExtra("gains") ?: currentCustomEqGains)
                }
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange status=$status state=$newState device=${gatt.device.address}")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connected = true
                    connecting = false
                    reconnectPending = false
                    this@QcyController.gatt = gatt
                    gatt.requestMtu(512)
                    gatt.discoverServices()
                    changeUIConnectionState("connecting")
                    startHeartbeat()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connected = false
                    connecting = false
                    closeGatt()
                    // A GATT drop is NOT an earphone disconnect — A2DP/HFP can stay up while
                    // the BLE data channel dies (link idle, single-bud swap, BLE coex).
                    // Do NOT broadcast ACTION_PODS_DISCONNECTED here: that clears the app's
                    // state and finishes MainActivity (the "app crashes / shows 未连接" bug),
                    // and the old code then refused to reconnect because currentBatteryParams
                    // was non-null, freezing the battery at its last value forever.
                    // The real disconnect is driven by the A2DP hook -> disconnectedPod().
                    // Here we just try to bring the data channel back so battery/ANC refresh.
                    scheduleReconnectIfNeeded("gatt-disconnected status=$status")
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "onMtuChanged mtu=$mtu status=$status")
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.d(TAG, "onServicesDiscovered status=$status")
            val service = gatt.getService(QcyUuids.MAIN_SERVICE) ?: return
            commandCharacteristic = service.getCharacteristic(QcyUuids.COMMAND)
            notifyCharacteristic = service.getCharacteristic(QcyUuids.NOTIFY)
            batteryCharacteristic = service.getCharacteristic(QcyUuids.BATTERY)
            pendingNotificationCharacteristics.clear()
            enqueueNotificationSetup(gatt, batteryCharacteristic)
            enqueueNotificationSetup(gatt, notifyCharacteristic)
            if (!writeNextNotificationDescriptor(gatt)) {
                queryStatus()
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            Log.d(TAG, "onDescriptorWrite status=$status uuid=${descriptor.uuid}")
            if (!writeNextNotificationDescriptor(gatt)) {
                queryStatus()
            }
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            if (characteristic.uuid == QcyUuids.BATTERY) {
                QcyPacketParser.parseBattery(characteristic.value)?.let(::handleBatteryChanged)
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            when (characteristic.uuid) {
                QcyUuids.BATTERY -> {
                    Log.d(TAG, "onCharacteristicChanged battery=${characteristic.value?.toHexString()}")
                    QcyPacketParser.parseBattery(characteristic.value ?: return)?.let(::handleBatteryChanged)
                }
                QcyUuids.NOTIFY -> {
                    Log.d(TAG, "onCharacteristicChanged notify=${characteristic.value?.toHexString()}")
                    handleNotifyPacket(characteristic.value ?: return)
                }
            }
        }
    }

    fun currentStatusSnapshot(): StatusSnapshot = StatusSnapshot(
        battery = currentBatteryParams,
        anc = currentAnc,
        transparencyVocalEnhancement = currentTransparencyVocalEnhancement,
        address = currentAddress,
        deviceName = currentDeviceName,
        connected = connected,
        connecting = connecting,
        reconnectPending = false,
    )

    fun miuiRefreshPayload(battery: BatteryParams?, anc: Int, transparencyVocalEnhancement: Boolean = false): String {
        val values = MutableList(16) { "" }
        values[0] = miuiBatteryValue(battery?.left)
        values[1] = miuiBatteryValue(battery?.right)
        values[2] = miuiBatteryValue(battery?.case)
        values[7] = when (anc) {
            3 -> if (transparencyVocalEnhancement) "0201" else "0200"
            2, 4, 5, 6, 7, 8 -> "0100"
            else -> "0000"
        }
        values[8] = "true"
        values[11] = "00"
        values[13] = "00"
        values[14] = "00"
        return values.joinToString(",")
    }

    fun connectPod(context: Context, device: BluetoothDevice, prefs: SharedPreferences, appRequested: Boolean = false) {
        this.context = context
        this.prefs = prefs
        this.classicDevice = device
        this.currentAddress = device.address
        this.currentDeviceName = device.name
        PodMetadata.markHeadset(device)
        restoreCachedEqState()
        if (appRequested) markAppUiActive()
        reconnectPending = false
        reconnectHandler.removeCallbacksAndMessages(null)
        registerReceiverIfNeeded(context)
        startQcyAdvertisementScan(context)
        connectLePeer(context, device)
    }

    fun disconnectedPod(context: Context, device: BluetoothDevice) {
        // Prefer this controller's OWN bound device (the QCY it connected to). The passed-in
        // `device` is the A2DP event's device; when PodController tears us down to switch to a
        // different brand (e.g. QCY still A2DP-connected but the user pairs a vivo/iQOO), that
        // device is the NEW earphone — we must clean up the QCY, not the new one.
        val own = classicDevice ?: device
        PodMetadata.clear(own)
        connected = false
        connecting = false
        showedConnectedToast = false
        currentBatteryParams = null
        lastBatteryReceivedMs = 0L
        stopHeartbeat()
        currentAnc = 1
        currentGameMode = false
        currentSpatialAudioMode = SPATIAL_AUDIO_OFF
        currentDualDeviceConnection = false
        currentLdac = false
        currentDynamicEq = false
        currentSleepMode = false
        currentAdaptiveVolume = false
        currentEqPreset = QcyEqPreset.DEFAULT
        currentCustomEqGains = IntArray(QcyOfficialEqCurves.customFrequencies.size)
        lastWrittenEqPreset = null
        lastWrittenCustomEqGains = null
        currentTransparencyVocalEnhancement = false
        reconnectAttempts = 0
        reconnectPending = false
        reconnectHandler.removeCallbacksAndMessages(null)
        stopQcyAdvertisementScan()
        closeGatt()
        MiuiStrongToastUtil.cancelPodsNotificationByMiuiBt(context, own)
        setRegularBatteryLevel(SystemApisUtils.BATTERY_LEVEL_UNKNOWN)
        sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_DISCONNECTED, force = true) {
            putExtra("address", own.address)
        }
        changeUIConnectionState("disconnected")
    }

    fun queryStatus() {
        context?.let(::startQcyAdvertisementScan)
        batteryCharacteristic?.let { gatt?.readCharacteristic(it) }
        sendCommand(QcyPacketBuilder.request(QcyCmd.BATTERY))
        sendCommand(QcyPacketBuilder.request(QcyCmd.NOISE_CANCEL_MODE))
        sendCommand(QcyPacketBuilder.request(QcyCmd.LOW_LATENCY))
        sendCommand(QcyPacketBuilder.request(QcyCmd.SPATIAL_AUDIO))
        sendCommand(QcyPacketBuilder.request(QcyCmd.DUAL_DEVICE_CONNECTION))
        sendCommand(QcyPacketBuilder.request(QcyCmd.LDAC))
        sendCommand(QcyPacketBuilder.request(QcyCmd.DYNAMIC_EQ))
        sendCommand(QcyPacketBuilder.request(QcyCmd.SLEEP_MODE))
        sendCommand(QcyPacketBuilder.request(QcyCmd.ADAPTIVE_VOLUME))
        restoreSavedEqStateForUi()
        sendCommand(QcyPacketBuilder.request(QcyCmd.EQ_PARAMS_V2))
    }

    fun setGameMode(enabled: Boolean) {
        currentGameMode = enabled
        changeUIGameModeStatus(enabled)
        sendCommand(QcyPacketBuilder.lowLatency(enabled))
    }

    private fun setAncMode(mode: Int) {
        sendCommand(QcyPacketBuilder.noiseMode(mode))
    }

    private fun setSpatialAudioMode(mode: Int) {
        currentSpatialAudioMode = mode.coerceIn(SPATIAL_AUDIO_OFF, SPATIAL_AUDIO_HEAD_TRACKING)
        changeUISpatialAudioStatus(currentSpatialAudioMode)
        sendCommand(QcyPacketBuilder.spatialAudio(currentSpatialAudioMode))
    }

    private fun setDualDeviceConnection(enabled: Boolean) {
        currentDualDeviceConnection = enabled
        changeUIDualDeviceConnectionStatus(enabled)
        sendCommand(QcyPacketBuilder.qcyToggle(QcyCmd.DUAL_DEVICE_CONNECTION, enabled))
    }

    private fun setLdac(enabled: Boolean) {
        currentLdac = enabled
        changeUILdacStatus(enabled)
        sendCommand(QcyPacketBuilder.qcyToggle(QcyCmd.LDAC, enabled))
    }

    private fun setDynamicEq(enabled: Boolean) {
        currentDynamicEq = enabled
        changeUIDynamicEqStatus(enabled)
        sendCommand(QcyPacketBuilder.qcyToggle(QcyCmd.DYNAMIC_EQ, enabled))
    }

    private fun setSleepMode(enabled: Boolean) {
        currentSleepMode = enabled
        changeUISleepModeStatus(enabled)
        sendCommand(QcyPacketBuilder.qcyToggle(QcyCmd.SLEEP_MODE, enabled))
    }

    private fun setAdaptiveVolume(enabled: Boolean) {
        currentAdaptiveVolume = enabled
        changeUIAdaptiveVolumeStatus(enabled)
        sendCommand(QcyPacketBuilder.qcyToggle(QcyCmd.ADAPTIVE_VOLUME, enabled))
    }

    private fun setEqPreset(preset: Int) {
        if (preset == QcyEqPreset.CUSTOM) {
            if (currentEqPreset == preset && lastWrittenEqPreset == preset) {
                Log.d(TAG, "setEqPreset ignored: unchanged custom preset")
                return
            }
            currentEqPreset = preset
            lastWrittenEqPreset = preset
            saveCurrentEqConfig()
            changeUIEqPreset(preset)
            changeUICustomEqGains(currentCustomEqGains)
            sendCommand(QcyPacketBuilder.eqPreset(preset))
            return
        }
        if (QcyOfficialEqCurves.byId(preset) == null) {
            Log.w(TAG, "setEqPreset ignored: unsupported QCY preset=$preset")
            return
        }
        if (currentEqPreset == preset && lastWrittenEqPreset == preset) {
            Log.d(TAG, "setEqPreset ignored: unchanged preset=$preset")
            return
        }
        currentEqPreset = preset
        lastWrittenEqPreset = preset
        lastWrittenCustomEqGains = null
        saveCurrentEqConfig()
        changeUIEqPreset(preset)
        sendCommand(QcyPacketBuilder.eqPreset(preset))
    }

    private fun setCustomEq(gains: IntArray) {
        val normalizedGains = normalizeCustomEqGains(gains)
        if (currentEqPreset == QcyEqPreset.CUSTOM && lastWrittenCustomEqGains.contentEqualsSafe(normalizedGains)) {
            Log.d(TAG, "setCustomEq ignored: unchanged custom gains")
            return
        }
        currentCustomEqGains = normalizedGains
        currentEqPreset = QcyEqPreset.CUSTOM
        lastWrittenEqPreset = QcyEqPreset.CUSTOM
        lastWrittenCustomEqGains = currentCustomEqGains.copyOf()
        saveCurrentEqConfig()
        changeUIEqPreset(currentEqPreset)
        changeUICustomEqGains(currentCustomEqGains)
        sendCommand(QcyPacketBuilder.parametricEqV2(QcyOfficialEqCurves.custom(currentCustomEqGains.toList())))
    }

    private fun restoreCachedEqState() {
        val saved = prefs?.let { QcyEqPrefs.read(it, currentAddress) } ?: return
        applySavedEqState(saved)
        Log.d(TAG, "restored cached QCY EQ state preset=${saved.preset} address=$currentAddress")
    }

    private fun restoreSavedEqStateForUi() {
        val saved = prefs?.let { QcyEqPrefs.read(it, currentAddress) } ?: return
        applySavedEqState(saved)
        changeUIEqPreset(currentEqPreset)
        changeUICustomEqGains(currentCustomEqGains)
        Log.d(TAG, "restored saved QCY EQ UI state preset=$currentEqPreset address=$currentAddress")
    }

    private fun applySavedEqState(saved: QcyEqConfig) {
        currentEqPreset = saved.preset.takeIf { it in QcyEqPreset.ALL } ?: QcyEqPreset.DEFAULT
        currentCustomEqGains = normalizeCustomEqGains(saved.gains)
    }

    private fun saveCurrentEqConfig() {
        val preferences = prefs ?: return
        QcyEqPrefs.save(
            prefs = preferences,
            address = currentAddress,
            preset = currentEqPreset,
            gains = currentCustomEqGains,
        )
    }

    private fun connectLePeer(context: Context, classicDevice: BluetoothDevice) {
        closeGatt()
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter ?: return
        val candidateName = buildLePeerName(classicDevice)
        val inferredAddress = inferLeAddress(classicDevice.address)
        val classicPrefix = classicDevice.address.uppercase(Locale.ROOT).substringBeforeLast(':') + ':'
        val bonded = adapter.bondedDevices.orEmpty()
        // A dual-mode TWS pair shares one MAC prefix between its classic (A2DP/HFP) and LE
        // (GATT data) identities — only the final byte differs (C4:33:96:10:64:23 classic vs
        // C4:33:96:10:64:76 LE on the QCY Crossky C50S). The LE peer is usually NOT in
        // bondedDevices (it only advertises), so resolve it from (a) a same-prefix bonded
        // device, (b) the LE MACs captured from the last advertisement. Without this we'd
        // connectGatt the classic address, never establish the data channel, and the battery
        // would freeze on the last advertisement value.
        leDevice = bonded.firstOrNull { device ->
            if (device.address.equals(classicDevice.address, ignoreCase = true)) return@firstOrNull false
            val addr = device.address.uppercase(Locale.ROOT)
            PodDetector.isQcyAppDevice(device) ||
                addr.equals(inferredAddress, ignoreCase = true) ||
                addr.startsWith(classicPrefix) ||
                ((device.name ?: "").contains(candidateName, ignoreCase = true))
        } ?: inferredAddress?.let { address ->
            runCatching { adapter.getRemoteDevice(address) }.onFailure {
                Log.w(TAG, "getRemoteDevice failed for inferred address=$address", it)
            }.getOrNull()
        } ?: matchAdvPeerAddress(adapter, classicDevice.address)?.let { address ->
            runCatching { adapter.getRemoteDevice(address) }.onFailure {
                Log.w(TAG, "getRemoteDevice failed for adv peer address=$address", it)
            }.getOrNull()
        }
        val target = leDevice ?: classicDevice
        Log.d(
            TAG,
            "connectLePeer classic=${classicDevice.address} inferred=$inferredAddress le=${leDevice?.address} target=${target.address} targetName=${target.name}"
        )
        connecting = true
        reconnectPending = false
        changeUIConnectionState("connecting")
        gatt = if (target.type == BluetoothDevice.DEVICE_TYPE_CLASSIC) {
            target.connectGatt(context, false, gattCallback)
        } else {
            target.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }
    }

    /**
     * Best-effort LE peer address for a dual-mode TWS pair. The LE identity shares the
     * classic MAC prefix with only the final byte differing (C4:33:96:10:64:23 classic →
     * C4:33:96:10:64:76 LE on the QCY Crossky C50S). Known QCY suffixes :CC→:99 are handled
     * explicitly; otherwise we keep the classic address so the caller can still try it and
     * rely on the advertisement-scanned peer (matchAdvPeerAddress) to supply the real LE MAC.
     */
    private fun inferLeAddress(classicAddress: String): String? {
        val clean = classicAddress.uppercase(Locale.ROOT)
        return if (clean.endsWith(":CC")) clean.dropLast(2) + "99" else null
    }

    /**
     * Resolve the LE peer for GATT from the last advertisement we saw: the advertisement's
     * source MAC is the LE (GATT) identity, which is NOT in bondedDevices as a separate
     * device. Used to connect the data channel on firmware whose classic suffix isn't a
     * known :CC→:99 mapping (e.g. C50S classic :…64:23 → LE :…64:76).
     */
    private fun matchAdvPeerAddress(adapter: android.bluetooth.BluetoothAdapter, classicAddress: String): String? {
        val classic = classicAddress.uppercase(Locale.ROOT)
        val prefix = classic.substringBeforeLast(':') + ':'
        val candidates = buildSet {
            lastAdvSourceAddress?.uppercase(Locale.ROOT)?.let(::add)
            lastAdvControlAddress?.uppercase(Locale.ROOT)?.let(::add)
            lastAdvOtherAddress?.uppercase(Locale.ROOT)?.let(::add)
        }.filter { it != classic && it.startsWith(prefix) }
        Log.d(TAG, "matchAdvPeerAddress classic=$classic candidates=$candidates")
        return candidates.firstOrNull()
    }

    private fun buildLePeerName(device: BluetoothDevice): String {
        val base = device.name ?: "QCY"
        return "$base-APP"
    }

    private fun handleNotifyPacket(packet: ByteArray) {
        QcyPacketParser.parse(packet).forEach { event ->
            when (event.cmd) {
                QcyCmd.BATTERY -> QcyPacketParser.parseBattery(event.params)?.let(::handleBatteryChanged)
                QcyCmd.NOISE_CANCEL_MODE -> QcyPacketParser.parseNoiseMode(event.params)?.let {
                    currentAnc = it
                    changeUIAncStatus(it)
                }
                QcyCmd.LOW_LATENCY -> QcyPacketParser.parseLowLatencyEnabled(event.params)?.let {
                    currentGameMode = it
                    changeUIGameModeStatus(it)
                }
                QcyCmd.SPATIAL_AUDIO -> QcyPacketParser.parseSpatialAudioMode(event.params)?.let {
                    currentSpatialAudioMode = it
                    changeUISpatialAudioStatus(it)
                }
                QcyCmd.DUAL_DEVICE_CONNECTION -> QcyPacketParser.parseToggleEnabled(event.params)?.let {
                    currentDualDeviceConnection = it
                    changeUIDualDeviceConnectionStatus(it)
                }
                QcyCmd.LDAC -> QcyPacketParser.parseToggleEnabled(event.params)?.let {
                    currentLdac = it
                    changeUILdacStatus(it)
                }
                QcyCmd.DYNAMIC_EQ -> QcyPacketParser.parseToggleEnabled(event.params)?.let {
                    currentDynamicEq = it
                    changeUIDynamicEqStatus(it)
                }
                QcyCmd.SLEEP_MODE -> QcyPacketParser.parseToggleEnabled(event.params)?.let {
                    currentSleepMode = it
                    changeUISleepModeStatus(it)
                }
                QcyCmd.ADAPTIVE_VOLUME -> QcyPacketParser.parseToggleEnabled(event.params)?.let {
                    currentAdaptiveVolume = it
                    changeUIAdaptiveVolumeStatus(it)
                }
                QcyCmd.EQ_PARAMS_V1,
                QcyCmd.EQ_PARAMS_V2 -> {
                    QcyPacketParser.parseEqV2Gains(event.params)?.let { gains ->
                        currentCustomEqGains = normalizeCustomEqGains(gains.toIntArray())
                        changeUICustomEqGains(currentCustomEqGains)
                    }
                    QcyPacketParser.parseEqPreset(event.params)?.let {
                        currentEqPreset = it
                        changeUIEqPreset(it)
                    }
                }
            }
        }
    }

    private fun handleBatteryChanged(batteryParams: BatteryParams) {
        currentBatteryParams = batteryParams
        lastBatteryReceivedMs = SystemClock.elapsedRealtime()
        reconnectAttempts = 0
        reconnectPending = false
        reconnectHandler.removeCallbacksAndMessages(null)
        if (!showedConnectedToast) {
            showedConnectedToast = true
            changeUIConnectionState("connected")
            sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_CONNECTED) {
                currentAddress?.let { putExtra("address", it) }
                currentDeviceName?.let { putExtra("device_name", it) }
            }
            val ctx = context
            val device = classicDevice
            if (ctx != null && device != null) {
                val notificationSettings = prefs?.let { NotificationSettings.fromPrefs(it) } ?: NotificationSettings()
                MiuiStrongToastUtil.showPodsBatteryToastByMiuiBt(ctx, batteryParams, notificationSettings)
            }
        }
        classicDevice?.let { device ->
            context?.let { ctx ->
                MiuiStrongToastUtil.showPodsNotificationByMiuiBt(ctx, batteryParams, device)
                val mainBattery = listOfNotNull(batteryParams.left, batteryParams.right)
                    .filter { it.isConnected }
                    .minOfOrNull { it.battery } ?: SystemApisUtils.BATTERY_LEVEL_UNKNOWN
                setRegularBatteryLevel(mainBattery)
            }
        }
        classicDevice?.let { PodMetadata.applyBattery(it, batteryParams) }
        sendBatteryBroadcast(batteryParams)
    }

    private fun handleAdvertisementChanged(status: QcyAdvertisementStatus, sourceDevice: BluetoothDevice) {
        if (!matchesCurrentPod(status, sourceDevice)) {
            Log.d(
                TAG,
                "ignore QCY advertisement source=${sourceDevice.address} control=${status.controlAddress} other=${status.otherAddress}"
            )
            return
        }
        if (currentAddress == null) {
            currentAddress = status.controlAddress ?: classicDevice?.address ?: sourceDevice.address
        }
        if (currentDeviceName == null) {
            currentDeviceName = classicDevice?.name ?: sourceDevice.name
        }
        Log.d(
            TAG,
            "QCY advertisement battery source=${sourceDevice.address} control=${status.controlAddress} other=${status.otherAddress}"
        )
        // Remember the LE MACs from the advertisement — these are the GATT peer identities
        // (not separate bonded devices), used by matchAdvPeerAddress to connect the data channel.
        status.controlAddress?.let { lastAdvControlAddress = it }
        status.otherAddress?.let { lastAdvOtherAddress = it }
        lastAdvSourceAddress = sourceDevice.address
        connected = connected || classicDevice != null
        connecting = false
        handleBatteryChanged(status.battery)
    }

    private fun sendBatteryBroadcast(status: BatteryParams) {
        sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_BATTERY_CHANGED) {
            currentAddress?.let { putExtra("address", it) }
            putExtra("status", status)
            putExtra("left_battery", status.left?.battery ?: 0)
            putExtra("left_charging", status.left?.isCharging == true)
            putExtra("left_connected", status.left?.isConnected == true)
            putExtra("right_battery", status.right?.battery ?: 0)
            putExtra("right_charging", status.right?.isCharging == true)
            putExtra("right_connected", status.right?.isConnected == true)
            putExtra("case_battery", status.case?.battery ?: 0)
            putExtra("case_charging", status.case?.isCharging == true)
            putExtra("case_connected", status.case?.isConnected == true)
        }
        // Also broadcast to system processes that need battery data
        // (com.android.settings, com.xiaomi.bluetooth) even when module UI is inactive.
        // These processes have their own broadcast receivers in SettingsHeadsetHook
        // and BluetoothUpstreamHeadsetHook that depend on this data.
        val ctx = context ?: return
        listOf("com.android.settings", "com.xiaomi.bluetooth", "com.milink.service").forEach { pkg ->
            Intent(OppoPodsAction.ACTION_PODS_BATTERY_CHANGED).apply {
                currentAddress?.let { putExtra("address", it) }
                putExtra("status", status)
                putExtra("left_battery", status.left?.battery ?: 0)
                putExtra("left_charging", status.left?.isCharging == true)
                putExtra("left_connected", status.left?.isConnected == true)
                putExtra("right_battery", status.right?.battery ?: 0)
                putExtra("right_charging", status.right?.isCharging == true)
                putExtra("right_connected", status.right?.isConnected == true)
                putExtra("case_battery", status.case?.battery ?: 0)
                putExtra("case_charging", status.case?.isCharging == true)
                putExtra("case_connected", status.case?.isConnected == true)
                setPackage(pkg)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                ctx.sendBroadcast(this)
            }
        }
    }

    private fun sendCommand(packet: ByteArray) {
        val gatt = gatt ?: return
        val characteristic = commandCharacteristic ?: return
        characteristic.value = packet
        gatt.writeCharacteristic(characteristic)
    }

    private fun emitUiSnapshot() {
        changeUIConnectionState(
            when {
                connected -> "connected"
                connecting -> "connecting"
                else -> "disconnected"
            }
        )
        // Re-emit ACTION_PODS_CONNECTED on app UI init so the app's "connected" state
        // reflects reality even when the pod connected before the app opened (the
        // first-connect broadcast is gated on appUiActive and thus missed).
        if (connected) {
            sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_CONNECTED) {
                currentAddress?.let { putExtra("address", it) }
                currentDeviceName?.let { putExtra("device_name", it) }
            }
        }
        currentBatteryParams?.let(::sendBatteryBroadcast)
        changeUIAncStatus(currentAnc)
        changeUIGameModeStatus(currentGameMode)
        changeUISpatialAudioStatus(currentSpatialAudioMode)
        changeUIDualDeviceConnectionStatus(currentDualDeviceConnection)
        changeUILdacStatus(currentLdac)
        changeUIDynamicEqStatus(currentDynamicEq)
        changeUISleepModeStatus(currentSleepMode)
        changeUIAdaptiveVolumeStatus(currentAdaptiveVolume)
        changeUIEqPreset(currentEqPreset)
        changeUICustomEqGains(currentCustomEqGains)
    }

    private fun changeUIConnectionState(state: String) {
        Log.d(
            TAG,
            "changeUIConnectionState state=$state connected=$connected connecting=$connecting battery=${currentBatteryParams != null} address=$currentAddress name=$currentDeviceName"
        )
        sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_CONNECTION_STATE_CHANGED) {
            currentAddress?.let { putExtra("address", it) }
            currentDeviceName?.let { putExtra("device_name", it) }
            putExtra("state", state)
        }
    }

    private fun changeUIAncStatus(status: Int) {
        sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_ANC_CHANGED) {
            currentAddress?.let { putExtra("address", it) }
            putExtra("status", status)
        }
    }

    private fun changeUIGameModeStatus(enabled: Boolean) {
        sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_GAME_MODE_CHANGED) {
            putExtra("enabled", enabled)
        }
    }

    private fun changeUISpatialAudioStatus(mode: Int) {
        sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_SPATIAL_AUDIO_CHANGED) {
            putExtra("mode", mode.coerceIn(SPATIAL_AUDIO_OFF, SPATIAL_AUDIO_HEAD_TRACKING))
        }
    }

    private fun changeUIDualDeviceConnectionStatus(enabled: Boolean) {
        sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_DUAL_DEVICE_CONNECTION_CHANGED) {
            putExtra("enabled", enabled)
        }
    }

    private fun changeUILdacStatus(enabled: Boolean) {
        sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_LDAC_CHANGED) {
            putExtra("enabled", enabled)
        }
    }

    private fun changeUIDynamicEqStatus(enabled: Boolean) {
        sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_DYNAMIC_EQ_CHANGED) {
            putExtra("enabled", enabled)
        }
    }

    private fun changeUISleepModeStatus(enabled: Boolean) {
        sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_SLEEP_MODE_CHANGED) {
            putExtra("enabled", enabled)
        }
    }

    private fun changeUIAdaptiveVolumeStatus(enabled: Boolean) {
        sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_ADAPTIVE_VOLUME_CHANGED) {
            putExtra("enabled", enabled)
        }
    }

    private fun changeUIEqPreset(preset: Int) {
        sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_EQ_PRESET_CHANGED) {
            putExtra("preset", preset)
        }
    }

    private fun changeUICustomEqGains(gains: IntArray) {
        sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_CUSTOM_EQ_CHANGED) {
            putExtra("gains", gains.copyOf())
        }
    }

    private fun sendAppStatusBroadcast(action: String, force: Boolean = false, fill: Intent.() -> Unit = {}) {
        val ctx = context ?: return
        if (!force && !isAppUiActive()) return
        Intent(action).apply {
            fill()
            `package` = BuildConfig.APPLICATION_ID
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            ctx.sendBroadcast(this)
        }
    }

    private fun registerReceiverIfNeeded(context: Context) {
        if (receiverRegistered) return
        context.registerReceiver(
            broadcastReceiver,
            IntentFilter().apply {
                addAction(OppoPodsAction.ACTION_PODS_UI_INIT)
                addAction(OppoPodsAction.ACTION_PODS_UI_CLOSED)
                addAction(OppoPodsAction.ACTION_REFRESH_STATUS)
                addAction(OppoPodsAction.ACTION_ANC_SELECT)
                addAction(OppoPodsAction.ACTION_GAME_MODE_SET)
                addAction(OppoPodsAction.ACTION_SPATIAL_AUDIO_SET)
                addAction(OppoPodsAction.ACTION_DUAL_DEVICE_CONNECTION_SET)
                addAction(OppoPodsAction.ACTION_LDAC_SET)
                addAction(OppoPodsAction.ACTION_DYNAMIC_EQ_SET)
                addAction(OppoPodsAction.ACTION_SLEEP_MODE_SET)
                addAction(OppoPodsAction.ACTION_ADAPTIVE_VOLUME_SET)
                addAction(OppoPodsAction.ACTION_EQ_PRESET_SET)
                addAction(OppoPodsAction.ACTION_CUSTOM_EQ_SET)
            },
            Context.RECEIVER_EXPORTED
        )
        receiverRegistered = true
    }

    private fun closeGatt() {
        pendingNotificationCharacteristics.clear()
        try {
            gatt?.close()
        } catch (_: Throwable) {
        }
        gatt = null
        commandCharacteristic = null
        notifyCharacteristic = null
        batteryCharacteristic = null
    }

    private fun markAppUiActive() {
        appUiActive = true
        appUiActiveUntilMs = SystemClock.elapsedRealtime() + APP_UI_ACTIVE_TIMEOUT_MS
    }

    private fun isAppUiActive(): Boolean {
        if (!appUiActive) return false
        if (SystemClock.elapsedRealtime() <= appUiActiveUntilMs) return true
        appUiActive = false
        appUiActiveUntilMs = 0L
        return false
    }

    private fun miuiBatteryValue(params: io.github.zuohl.hyperpods.utils.miuiStrongToast.data.PodParams?): String {
        if (params?.isConnected != true) return "255"
        val value = params.battery.coerceIn(0, 100)
        return (if (params.isCharging) value or 128 else value).toString()
    }

    private fun setRegularBatteryLevel(level: Int) {
        if (regularBatteryLevelUnsupported) return
        val ctx = context ?: return
        val device = classicDevice ?: return
        runCatching {
            val service = Class.forName("com.android.bluetooth.btservice.AdapterService")
            val getAdapterService = service.getDeclaredMethod("getAdapterService")
            val adapterService = getAdapterService.invoke(null)
            adapterService?.javaClass?.getMethod("setBatteryLevel", BluetoothDevice::class.java, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                ?.invoke(adapterService, device, level, false)
        }.onFailure {
            if (it is ClassNotFoundException) {
                regularBatteryLevelUnsupported = true
                Log.d(TAG, "setRegularBatteryLevel unsupported on this ROM")
            } else {
                Log.w(TAG, "setRegularBatteryLevel failed", it)
            }
        }
    }

    private fun scheduleReconnectIfNeeded(reason: String) {
        val ctx = context ?: return
        val device = classicDevice ?: return
        if (reconnectPending || reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.d(
                TAG,
                "skip reconnect reason=$reason reconnectPending=$reconnectPending attempts=$reconnectAttempts"
            )
            return
        }
        // NOTE: do NOT skip when currentBatteryParams != null. "Battery already known once"
        // is not a reason to stop refreshing — the GATT data channel is dead and must be
        // brought back, otherwise the displayed battery freezes at its last value (the
        // "电量不会变" bug). Bounded by MAX_RECONNECT_ATTEMPTS; disconnectedPod() cancels
        // pending reconnects when the earphone truly goes away.
        reconnectPending = true
        reconnectAttempts += 1
        Log.d(TAG, "schedule reconnect attempt=$reconnectAttempts reason=$reason device=${device.address}")
        reconnectHandler.postDelayed({
            reconnectPending = false
            Log.d(TAG, "reconnect now attempt=$reconnectAttempts device=${device.address}")
            startQcyAdvertisementScan(ctx)
            connectLePeer(ctx, device)
        }, RECONNECT_DELAY_MS)
    }

    /**
     * Background heartbeat that detects a dead GATT data channel and revives it.
     *
     * Why this exists: the QCY GATT link is exclusive — when another app (the official QCY
     * app) opens, it grabs the LE connection out from under us. That teardown often does
     * NOT fire our BluetoothGattCallback.onConnectionStateChange (it's torn down below the
     * Java layer), so the normal reconnect path never runs and the displayed battery
     * freezes at its last value forever ("模块电量不变" bug) even though A2DP stays up and
     * the system tray shows the correct battery.
     *
     * While the pod's A2DP is connected, if no battery packet has arrived for
     * [GATT_STALE_AFTER_MS], we treat the data channel as dead and kick a reconnect
     * (resetting the attempt counter so recovery isn't gated by the prior cap).
     */
    private fun startHeartbeat() {
        if (heartbeatRunnable != null) return
        val runnable = object : Runnable {
            override fun run() {
                checkGattHealth()
                heartbeatHandler.postDelayed(this, GATT_HEARTBEAT_INTERVAL_MS)
            }
        }
        heartbeatRunnable = runnable
        heartbeatHandler.postDelayed(runnable, GATT_HEARTBEAT_INTERVAL_MS)
    }

    private fun stopHeartbeat() {
        heartbeatRunnable?.let { heartbeatHandler.removeCallbacks(it) }
        heartbeatRunnable = null
    }

    @SuppressLint("MissingPermission")
    private fun checkGattHealth() {
        val device = classicDevice ?: return
        val ctx = context ?: return
        // Only probe while the earphone's classic profile is still up — once it's truly
        // gone, disconnectedPod() stops the heartbeat.
        val a2dpUp = runCatching {
            val bm = ctx.getSystemService(BluetoothManager::class.java)
            bm?.getConnectedDevices(BluetoothProfile.A2DP).orEmpty().any { it.address == device.address } ||
                bm?.getConnectedDevices(BluetoothProfile.HEADSET).orEmpty().any { it.address == device.address }
        }.getOrDefault(false)
        if (!a2dpUp) {
            Log.d(TAG, "heartbeat: A2DP down, skipping probe")
            return
        }
        val now = SystemClock.elapsedRealtime()
        val stale = lastBatteryReceivedMs == 0L || (now - lastBatteryReceivedMs) > GATT_STALE_AFTER_MS
        if (!stale) {
            return // data channel is alive
        }
        Log.i(TAG, "heartbeat: GATT data channel stale (lastBattery=${lastBatteryReceivedMs} now=$now) — reconnecting")
        // Official QCY app likely stole the LE link. Close our (dead) gatt handle and
        // reconnect so battery/ANC refresh resumes once the LE link is free again.
        reconnectAttempts = 0
        reconnectPending = false
        closeGatt()
        startQcyAdvertisementScan(ctx)
        connectLePeer(ctx, device)
    }

    private fun startQcyAdvertisementScan(context: Context) {
        if (scanning) return
        val scanner = context.getSystemService(BluetoothManager::class.java)
            ?.adapter
            ?.bluetoothLeScanner
            ?: return
        val filter = ScanFilter.Builder()
            .setManufacturerData(QcyCompany.ID, byteArrayOf())
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        runCatching {
            scanner.startScan(listOf(filter), settings, scanCallback)
            scanning = true
            Log.d(TAG, "QCY advertisement scan started")
            scanHandler.removeCallbacksAndMessages(null)
            scanHandler.postDelayed({ stopQcyAdvertisementScan() }, SCAN_WINDOW_MS)
        }.onFailure {
            Log.w(TAG, "start QCY advertisement scan failed", it)
        }
    }

    private fun stopQcyAdvertisementScan() {
        val ctx = context
        if (!scanning || ctx == null) return
        val scanner = ctx.getSystemService(BluetoothManager::class.java)
            ?.adapter
            ?.bluetoothLeScanner
            ?: return
        runCatching {
            scanner.stopScan(scanCallback)
            Log.d(TAG, "QCY advertisement scan stopped")
        }.onFailure {
            Log.w(TAG, "stop QCY advertisement scan failed", it)
        }
        scanning = false
    }

    private fun handleScanResult(result: ScanResult) {
        val data = result.scanRecord?.getManufacturerSpecificData(QcyCompany.ID) ?: return
        val status = QcyAdvertisementParser.parse(data) ?: return
        handleAdvertisementChanged(status, result.device)
    }

    private fun matchesCurrentPod(status: QcyAdvertisementStatus, sourceDevice: BluetoothDevice): Boolean {
        val control = status.controlAddress?.uppercase(Locale.ROOT)
        val other = status.otherAddress?.uppercase(Locale.ROOT)
        val source = sourceDevice.address.uppercase(Locale.ROOT)
        val inferred = inferLeAddress(classicDevice?.address.orEmpty())?.uppercase(Locale.ROOT)
        val knownAddresses = buildSet {
            classicDevice?.address?.uppercase(Locale.ROOT)?.let(::add)
            leDevice?.address?.uppercase(Locale.ROOT)?.let(::add)
            currentAddress?.uppercase(Locale.ROOT)?.let(::add)
            inferred?.let(::add)
        }
        if (knownAddresses.isEmpty()) return PodDetector.isSupportedPod(sourceDevice) || control != null || other != null
        return listOfNotNull(source, control, other).any { it in knownAddresses }
    }

    private fun enqueueNotificationSetup(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic?) {
        if (characteristic == null) return
        val notificationEnabled = runCatching {
            gatt.setCharacteristicNotification(characteristic, true)
        }.getOrElse {
            Log.w(TAG, "setCharacteristicNotification failed uuid=${characteristic.uuid}", it)
            false
        }
        Log.d(TAG, "setCharacteristicNotification uuid=${characteristic.uuid} enabled=$notificationEnabled")
        val cccd = characteristic.getDescriptor(QcyUuids.CCCD)
        if (cccd != null) {
            pendingNotificationCharacteristics += characteristic
        } else {
            Log.d(TAG, "characteristic ${characteristic.uuid} has no CCCD")
        }
    }

    private fun writeNextNotificationDescriptor(gatt: BluetoothGatt): Boolean {
        while (pendingNotificationCharacteristics.isNotEmpty()) {
            val characteristic = pendingNotificationCharacteristics.removeFirst()
            val cccd = characteristic.getDescriptor(QcyUuids.CCCD) ?: continue
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            val started = runCatching { gatt.writeDescriptor(cccd) }.getOrElse {
                Log.w(TAG, "writeDescriptor failed uuid=${characteristic.uuid}", it)
                false
            }
            Log.d(TAG, "writeDescriptor start uuid=${characteristic.uuid} started=$started")
            if (started) return true
        }
        return false
    }

    private fun normalizeCustomEqGains(gains: IntArray): IntArray =
        IntArray(QcyOfficialEqCurves.customFrequencies.size) { index ->
            gains.getOrNull(index)?.coerceIn(-8, 8) ?: 0
        }

    private fun IntArray?.contentEqualsSafe(other: IntArray): Boolean =
        this != null && contentEquals(other)

    private fun ByteArray.toHexString(): String = joinToString(separator = " ") { "%02X".format(it.toInt() and 0xFF) }
}
