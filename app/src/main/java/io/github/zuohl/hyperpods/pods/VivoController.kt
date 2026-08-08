package io.github.zuohl.hyperpods.pods

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.zuohl.hyperpods.BuildConfig
import io.github.zuohl.hyperpods.utils.miuiStrongToast.data.BatteryParams
import io.github.zuohl.hyperpods.utils.miuiStrongToast.data.OppoPodsAction
import io.github.zuohl.hyperpods.utils.miuiStrongToast.data.PodParams
import io.github.zuohl.hyperpods.utils.miuiStrongToast.data.putBatteryStatus
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * vivo / iQOO TWS controller over Bluetooth Classic RFCOMM (GAIA protocol, UUID 00000837-...).
 *
 * Reads battery (left/right/case) and controls noise mode (ANC/OFF/transparency). The earphone
 * needs a persistent SPP session: open the channel on connect, handshake + query battery/noise,
 * then keep it open and poll — user noise commands go over the same live channel so they are
 * reliably applied (a connect-send-close approach dropped/misapplied commands).
 */
object VivoController {
    private const val TAG = "HyperPods-Vivo"
    private const val VIVO_GAIA_UUID = "00000837-d102-11e1-9b23-00025b00a5a5"
    private const val POLL_INTERVAL_MS = 20_000L

    private val handler = Handler(Looper.getMainLooper())
    private var context: Context? = null
    private var prefs: SharedPreferences? = null
    private var classicDevice: BluetoothDevice? = null
    private val connected = AtomicBoolean(false)
    private val sessionActive = AtomicBoolean(false)
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var currentBattery: BatteryParams? = null
    @Volatile private var currentAnc = 1
    private var receiverRegistered = false
    private var showedConnected = false

    private val pollRunnable = object : Runnable {
        override fun run() {
            sendQuery()
            if (connected.get()) handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private val broadcastReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: android.content.Intent?) {
            when (intent?.action) {
                OppoPodsAction.ACTION_REFRESH_STATUS,
                OppoPodsAction.ACTION_PODS_UI_INIT -> {
                    if (connected.get()) sendAppConnectedBroadcast()
                    currentBattery?.let { sendBatteryBroadcast(it) }
                    sendAncBroadcast()
                }
                OppoPodsAction.ACTION_ANC_SELECT -> {
                    val status = intent.getIntExtra("status", 1)
                    setNoiseModeFromUi(status)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerReceiverIfNeeded(context: Context) {
        if (receiverRegistered) return
        runCatching {
            context.registerReceiver(
                broadcastReceiver,
                android.content.IntentFilter().apply {
                    addAction(OppoPodsAction.ACTION_REFRESH_STATUS)
                    addAction(OppoPodsAction.ACTION_PODS_UI_INIT)
                    addAction(OppoPodsAction.ACTION_ANC_SELECT)
                },
                Context.RECEIVER_EXPORTED
            )
            receiverRegistered = true
        }
    }

    @SuppressLint("MissingPermission")
    fun connectPod(context: Context, device: BluetoothDevice, prefs: SharedPreferences, appRequested: Boolean = false) {
        this.context = context
        this.prefs = prefs
        this.classicDevice = device
        registerReceiverIfNeeded(context)
        PodMetadata.markHeadset(device)
        connected.set(true)
        // Reset per-connection so the super-island (shown on first battery report) fires on
        // every fresh connect — not only the first after a bluetooth-process restart.
        showedConnected = false
        sendAppConnectedBroadcast()
        Log.d(TAG, "vivo connected ${device.address}; opening persistent GAIA session")
        startSession(device)
        handler.removeCallbacks(pollRunnable)
        handler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
    }

    fun disconnectedPod(context: Context, device: BluetoothDevice) {
        Log.d(TAG, "disconnectedPod ${device.address}")
        connected.set(false)
        sessionActive.set(false)
        handler.removeCallbacks(pollRunnable)
        currentBattery = null
        currentAnc = 1
        showedConnected = false
        runCatching { socket?.close() }
        socket = null
        classicDevice?.let { PodMetadata.clear(it) }
        classicDevice = null
        sendDisconnectedBroadcast(context, device)
    }

    fun queryStatus() {
        sendQuery()
    }

    fun currentStatusSnapshot(): PodStatusSnapshot = PodStatusSnapshot(
        battery = currentBattery,
        anc = currentAnc,
        transparencyVocalEnhancement = false,
        address = classicDevice?.address,
        deviceName = classicDevice?.name,
        connected = connected.get(),
        connecting = false,
    )

    // ------------------------------------------------------------------
    // Persistent GAIA session
    // ------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun startSession(device: BluetoothDevice) {
        if (!sessionActive.compareAndSet(false, true)) return
        Thread {
            try {
                Log.d(TAG, "session: RFCOMM connecting ${device.address}")
                val sock = device.createRfcommSocketToServiceRecord(UUID.fromString(VIVO_GAIA_UUID))
                sock.connect()
                socket = sock
                Log.d(TAG, "session: RFCOMM connected ${device.address}")
                // handshake establishes the GAIA session; then query battery + noise mode.
                runCatching {
                    sock.outputStream.write(VivoProtocol.handshake())
                    sock.outputStream.flush()
                    Thread.sleep(300)
                    sock.outputStream.write(VivoProtocol.queryBattery())
                    sock.outputStream.flush()
                    sock.outputStream.write(VivoProtocol.queryNoiseMode())
                    sock.outputStream.flush()
                    Log.d(TAG, "session: handshake + queries sent")
                }
                readLoop(sock)
            } catch (e: Throwable) {
                Log.w(TAG, "session failed (will retry)", e)
            } finally {
                runCatching { socket?.close() }
                socket = null
                sessionActive.set(false)
            }
            // Retry after a short delay so a freshly-reconnected earphone whose SPP service is
            // not ready yet (connection succeeded but read returned -1) still gets a session and
            // thus triggers battery/super-island on this connection.
            if (connected.get()) {
                runCatching { Thread.sleep(2_000) }
                if (connected.get()) startSession(device)
            }
        }.apply { isDaemon = true }.start()
    }

    private fun sendQuery() {
        val sock = socket ?: return
        runCatching {
            sock.outputStream.write(VivoProtocol.queryBattery())
            sock.outputStream.flush()
            sock.outputStream.write(VivoProtocol.queryNoiseMode())
            sock.outputStream.flush()
        }
    }

    private fun readLoop(sock: BluetoothSocket) {
        val ins = sock.inputStream
        val buf = ByteArray(4096)
        val pending = mutableListOf<Byte>()
        while (sessionActive.get()) {
            val n = try {
                ins.read(buf)
            } catch (e: Exception) { -1 }
            if (n <= 0) break
            for (i in 0 until n) pending.add(buf[i])
            parseFrames(pending)
        }
        connected.set(false)
    }

    private fun parseFrames(pending: MutableList<Byte>) {
        while (true) {
            val start = pending.indexOfFirst { it.toInt() and 0xFF == 0xFF }
            if (start < 0) { pending.clear(); return }
            if (start > 0) pending.subList(0, start).clear()
            if (pending.size < 4) return
            val flags = pending[2].toInt() and 0xFF
            val headerSize = if (flags and 0x02 != 0) 5 else 4
            if (pending.size < headerSize) return
            val payloadLen = if (flags and 0x02 != 0) {
                ((pending[3].toInt() and 0xFF) shl 8) or (pending[4].toInt() and 0xFF)
            } else {
                pending[3].toInt() and 0xFF
            }
            val total = headerSize + 4 + payloadLen
            if (pending.size < total) return
            val raw = pending.subList(0, total).toByteArray()
            pending.subList(0, total).clear()
            val contentOffset = headerSize
            val vendor = ((raw[contentOffset].toInt() and 0xFF) shl 8) or (raw[contentOffset + 1].toInt() and 0xFF)
            val command = ((raw[contentOffset + 2].toInt() and 0xFF) shl 8) or (raw[contentOffset + 3].toInt() and 0xFF)
            val payload = raw.copyOfRange(contentOffset + 4, total)
            val frame = VivoProtocol.Frame(
                version = raw[1].toInt() and 0xFF,
                flags = flags,
                vendor = vendor,
                command = command,
                payload = payload,
                raw = raw,
            )
            VivoProtocol.parseBatteryState(frame)?.let(::onBattery)
            VivoProtocol.parseNoiseState(frame)?.let { onNoise(it.mode) }
        }
    }

    private fun onBattery(b: VivoProtocol.BatteryState) {
        val battery = BatteryParams(
            left = b.leftPercent?.let { PodParams(it, b.leftCharging, true) },
            right = b.rightPercent?.let { PodParams(it, b.rightCharging, true) },
            case = b.casePercent?.let { PodParams(it, b.caseCharging, true) },
        )
        currentBattery = battery
        classicDevice?.let { PodMetadata.applyBattery(it, battery) }
        if (!showedConnected) {
            showedConnected = true
            // First battery report -> battery super-island + connect broadcast, matching QCY.
            sendAppConnectedBroadcast()
            val ctx = context
            val device = classicDevice
            if (ctx != null && device != null) {
                val settings = prefs?.let { io.github.zuohl.hyperpods.utils.miuiStrongToast.data.NotificationSettings.fromPrefs(it) }
                    ?: io.github.zuohl.hyperpods.utils.miuiStrongToast.data.NotificationSettings()
                io.github.zuohl.hyperpods.utils.miuiStrongToast.MiuiStrongToastUtil.showPodsBatteryToastByMiuiBt(ctx, battery, settings)
            }
        }
        classicDevice?.let { device ->
            context?.let { ctx ->
                io.github.zuohl.hyperpods.utils.miuiStrongToast.MiuiStrongToastUtil.showPodsNotificationByMiuiBt(ctx, battery, device)
            }
        }
        sendBatteryBroadcast(battery)
        Log.d(TAG, "battery L=${b.leftPercent} R=${b.rightPercent} C=${b.casePercent}")
    }

    private fun onNoise(wireMode: Int) {
        // wire: 0=ANC 1=OFF 2=TRANSPARENCY -> UI: 2=ANC 1=OFF 3=TRANSPARENCY
        val ui = when (wireMode) {
            VivoProtocol.NoiseMode.ANC -> 2
            VivoProtocol.NoiseMode.TRANSPARENCY -> 3
            else -> 1
        }
        if (ui != currentAnc) {
            currentAnc = ui
            sendAncBroadcast()
        }
        Log.d(TAG, "noise mode wire=$wireMode ui=${currentAnc}")
    }

    /** UI status (1=OFF 2=ANC 3=TRANSPARENCY) -> optimistic UI update + send over live session. */
    private fun setNoiseModeFromUi(status: Int) {
        val wireMode = when (status) {
            2 -> VivoProtocol.NoiseMode.ANC
            3 -> VivoProtocol.NoiseMode.TRANSPARENCY
            else -> VivoProtocol.NoiseMode.OFF
        }
        // Optimistic update: reflect the user's choice immediately so the UI doesn't block;
        // the ACK (REPORT/ACK_NOISE_MODE) received later re-syncs currentAnc + broadcasts the
        // confirmed value.
        currentAnc = status
        sendAncBroadcast()
        val sock = socket
        if (sock == null) {
            Log.w(TAG, "noise set skipped: no live session")
            return
        }
        runCatching {
            sock.outputStream.write(VivoProtocol.setNoiseMode(wireMode))
            sock.outputStream.flush()
            Log.d(TAG, "noise set sent wire=$wireMode over live session")
        }.onFailure { Log.w(TAG, "noise set failed", it) }
    }

    private fun sendAncBroadcast() {
        val ctx = context ?: return
        android.content.Intent(OppoPodsAction.ACTION_PODS_ANC_CHANGED).apply {
            classicDevice?.address?.let { putExtra("address", it) }
            putExtra("status", currentAnc)
            `package` = BuildConfig.APPLICATION_ID
            addFlags(android.content.Intent.FLAG_RECEIVER_FOREGROUND)
        }.let { ctx.sendBroadcast(it) }
        listOf("com.android.settings", "com.milink.service", "com.xiaomi.bluetooth").forEach { pkg ->
            runCatching {
                ctx.sendBroadcast(
                    android.content.Intent(OppoPodsAction.ACTION_PODS_ANC_CHANGED).apply {
                        classicDevice?.address?.let { putExtra("address", it) }
                        putExtra("status", currentAnc)
                        setPackage(pkg)
                    }
                )
            }
        }
    }

    private fun sendAppConnectedBroadcast() {
        val ctx = context ?: return
        val addr = classicDevice?.address
        val name = classicDevice?.name
        android.content.Intent(OppoPodsAction.ACTION_PODS_CONNECTED).apply {
            addr?.let { putExtra("address", it) }
            name?.let { putExtra("device_name", it) }
            `package` = BuildConfig.APPLICATION_ID
            addFlags(android.content.Intent.FLAG_RECEIVER_FOREGROUND)
        }.let { ctx.sendBroadcast(it) }
        listOf("com.android.settings", "com.milink.service", "com.xiaomi.bluetooth").forEach { pkg ->
            runCatching {
                ctx.sendBroadcast(
                    android.content.Intent(OppoPodsAction.ACTION_PODS_CONNECTED).apply {
                        addr?.let { putExtra("address", it) }
                        name?.let { putExtra("device_name", it) }
                        setPackage(pkg)
                    }
                )
            }
        }
    }

    private fun sendBatteryBroadcast(battery: BatteryParams) {
        val ctx = context ?: return
        val addr = classicDevice?.address
        android.content.Intent(OppoPodsAction.ACTION_PODS_BATTERY_CHANGED).apply {
            addr?.let { putExtra("address", it) }
            putBatteryStatus(battery)
        }.let { base ->
            ctx.sendBroadcast(base.apply { `package` = BuildConfig.APPLICATION_ID }.addFlags(android.content.Intent.FLAG_RECEIVER_FOREGROUND))
            listOf("com.android.settings", "com.milink.service", "com.xiaomi.bluetooth").forEach { pkg ->
                runCatching { ctx.sendBroadcast(android.content.Intent(base).setPackage(pkg)) }
            }
        }
    }

    private fun sendDisconnectedBroadcast(context: Context, device: BluetoothDevice) {
        android.content.Intent(OppoPodsAction.ACTION_PODS_DISCONNECTED).apply {
            putExtra("address", device.address)
            `package` = BuildConfig.APPLICATION_ID
            addFlags(android.content.Intent.FLAG_RECEIVER_FOREGROUND)
        }.let { context.sendBroadcast(it) }
        listOf("com.android.settings", "com.milink.service", "com.xiaomi.bluetooth").forEach { pkg ->
            runCatching {
                context.sendBroadcast(
                    android.content.Intent(OppoPodsAction.ACTION_PODS_DISCONNECTED).apply {
                        putExtra("address", device.address)
                        setPackage(pkg)
                    }
                )
            }
        }
    }
}
