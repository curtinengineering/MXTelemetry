package com.curtinengineering.mxtelemetry

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

class PidScheduler(supported: Set<Int>) {
    private val fast = listOf(0x0C, 0x0D, if (0x5A in supported) 0x5A else 0x11)
    private val slow = listOf(0x05, 0x0F, 0x10, 0x04, 0x11)
        .filter { it in supported && it !in fast }
    private var i = 0

    fun nextBatch(): List<Int> {
        val batch = fast.toMutableList()
        if (slow.isNotEmpty()) {
            batch += slow[i]
            i = (i + 1) % slow.size
        }
        return batch
    }
}

class ObdBluetoothService : LifecycleService() {

    companion object {
        const val ACTION_START_BT = "com.curtinengineering.miataobdlogger.action.START_BT"
        const val ACTION_START_DEMO = "com.curtinengineering.miataobdlogger.action.START_DEMO"
        const val ACTION_STOP = "com.curtinengineering.miataobdlogger.action.STOP"
        const val ACTION_START_LOG = "com.curtinengineering.miataobdlogger.action.START_LOG"
        const val ACTION_STOP_LOG = "com.curtinengineering.miataobdlogger.action.STOP_LOG"

        const val EXTRA_MAC = "extra_mac"

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "obd_logger_channel"

        const val USE_RESPONSE_COUNT_DIGIT = false
        const val USE_ATAT2 = false

        fun send(context: Context, action: String, mac: String? = null) {
            val intent = Intent(context, ObdBluetoothService::class.java).apply {
                this.action = action
                if (mac != null) putExtra(EXTRA_MAC, mac)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private var workerJob: Job? = null
    private var csvLogger: CsvLogger? = null

    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_START_DEMO -> startDemo()
            ACTION_START_BT -> {
                val mac = intent.getStringExtra(EXTRA_MAC)
                if (!mac.isNullOrBlank()) startBluetooth(mac)
                else stopWorker("No MAC address provided")
            }
            ACTION_STOP -> stopWorker("Stopped by user")
            ACTION_START_LOG -> startLogging()
            ACTION_STOP_LOG -> stopLogging()
        }

        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "OBD Telemetry Logger Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitors live vehicle CAN bus telemetry & logs data"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): android.app.Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Miata OBD Telemetry")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
    }

    private fun startDemo() {
        stopWorker("Switching to Demo mode")
        TelemetryHub.mode.value = Mode.DEMO
        TelemetryHub.status.value = "Demo Mode Active"
        startForeground(NOTIFICATION_ID, buildNotification("Demo Mode Running"))

        ObdParser.resetTrackers()
        val demoGen = DemoGenerator()
        workerJob = lifecycleScope.launch(Dispatchers.Default) {
            var lastMs = SystemClock.elapsedRealtime()
            var frames = 0
            var hzTimer = SystemClock.elapsedRealtime()

            while (isActive) {
                val now = SystemClock.elapsedRealtime()
                val dt = (now - lastMs) / 1000.0
                lastMs = now

                val t = demoGen.step(if (dt <= 0.0 || dt > 0.2) 0.05 else dt)
                emit(t, "DEMO_SYNTHETIC")

                frames++
                if (now - hzTimer >= 1000) {
                    TelemetryHub.pollHz.value = frames * 1000f / (now - hzTimer)
                    frames = 0
                    hzTimer = now
                }

                delay(33) // ~30 Hz updates
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBluetooth(mac: String) {
        stopWorker("Connecting to $mac")
        TelemetryHub.mode.value = Mode.BLUETOOTH
        TelemetryHub.status.value = "Connecting to $mac ..."
        startForeground(NOTIFICATION_ID, buildNotification("Connecting to OBD Adapter"))

        workerJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val adapter = getSystemService(BluetoothManager::class.java)?.adapter
                if (adapter == null || !adapter.isEnabled) {
                    stopWorker("Bluetooth is disabled")
                    return@launch
                }

                val device = adapter.getRemoteDevice(mac)
                // Standard SPP UUID
                val uuid = java.util.UUID.fromString("00001101-0000-1000-8000-00885F9B34FB")
                val socket = device.createRfcommSocketToServiceRecord(uuid)

                adapter.cancelDiscovery()
                socket.connect()

                inputStream = socket.inputStream
                outputStream = socket.outputStream

                TelemetryHub.status.value = "Connected. Initializing vLinker..."
                runObdLoop()

            } catch (e: Exception) {
                e.printStackTrace()
                stopWorker("Connection failed: ${e.localizedMessage}")
            }
        }
    }

    private suspend fun runObdLoop() {
        val input = inputStream ?: return
        val output = outputStream ?: return

        fun sendCommand(cmd: String, timeoutMs: Long = 1500, verbose: Boolean = false): String {
            output.write("$cmd\r".toByteArray(StandardCharsets.US_ASCII))
            output.flush()

            val sb = StringBuilder()
            val buf = ByteArray(256)
            val start = SystemClock.elapsedRealtime()

            while (SystemClock.elapsedRealtime() - start < timeoutMs) {
                if (input.available() > 0) {
                    val n = input.read(buf)
                    if (n > 0) {
                        val s = String(buf, 0, n, StandardCharsets.US_ASCII)
                        sb.append(s)
                        if (s.contains(">")) break
                    }
                } else {
                    SystemClock.sleep(10)
                }
            }
            val res = sb.toString()
            if (verbose) TelemetryHub.log("TX: $cmd -> RX: ${res.replace("\r", " ").replace("\n", " ")}")
            return res
        }

        // --- ELM327 / vLinker INIT SEQUENCE ---------------------------------------------
        TelemetryHub.status.value = "Resetting OBD adapter..."
        sendCommand("AT Z")
        sendCommand("AT E0")
        sendCommand("AT L0")
        sendCommand("AT S0")
        if (USE_ATAT2) {
            sendCommand("AT AT 2")
        } else {
            sendCommand("AT AT 1")
        }
        sendCommand("AT SP 6") // ISO 15765-4 CAN (11 bit ID, 500 kbaud)
        sendCommand("AT RV")

        TelemetryHub.status.value = "Discovering PIDs..."
        val supported = mutableSetOf<Int>()
        var base = 0x00
        while (base <= 0x60) {
            val raw = sendCommand("01%02X".format(base), 3000)
            val parsed = ObdParser.parseResponse(raw)
            val bytes = parsed[base] ?: break
            for (bitIndex in 1..32) {
                val byteIdx = (bitIndex - 1) / 8
                val bitOffset = 7 - ((bitIndex - 1) % 8)
                if (byteIdx < bytes.size && (bytes[byteIdx] and (1 shl bitOffset)) != 0) {
                    supported.add(base + bitIndex)
                }
            }
            if ((base + 0x20) !in supported) break
            base += 0x20
        }
        sendCommand("AT DPN")
        TelemetryHub.supportedPids.value = supported

        val scheduler = PidScheduler(supported)
        val cachedValues = HashMap<Int, List<Int>>()
        var parseFailures = 0
        var pollCount = 0L
        var frames = 0
        var hzTimer = SystemClock.elapsedRealtime()

        ObdParser.resetTrackers()
        TelemetryHub.status.value = "Live (~13-15Hz)"

        while (currentCoroutineContext().isActive) {
            val verbose = pollCount < 5 || pollCount % 50 == 0L

            if (parseFailures >= 10) {
                // Fallback to single-PID requests if multi-PID fails 10 times consecutively
                val singleBatch = listOf(0x0C, 0x0D, 0x11)
                val rawParts = ArrayList<String>()
                for (p in singleBatch) {
                    val r = sendCommand("01%02X".format(p), 500, verbose)
                    rawParts += r
                    cachedValues.putAll(ObdParser.parseResponse(r))
                }
                val rawJoined = rawParts.joinToString(" | ") { it.replace("\r", "/").replace("\n", "/") }
                val t = ObdParser.processValues(cachedValues)
                emit(t, rawJoined)
            } else {
                val batch = scheduler.nextBatch()
                val cmdString = "01" + batch.joinToString("") { "%02X".format(it) } +
                        if (USE_RESPONSE_COUNT_DIGIT) "1" else ""

                val raw = sendCommand(cmdString, 1500, verbose)
                val parsed = ObdParser.parseResponse(raw)

                if (parsed.isNotEmpty()) {
                    parseFailures = 0
                    cachedValues.putAll(parsed)
                    val t = ObdParser.processValues(cachedValues)
                    emit(t, raw)
                } else {
                    parseFailures++
                    if (parseFailures % 20 == 1) {
                        TelemetryHub.status.value = "No valid data (ignition on?)"
                    }
                    delay(50)
                }
            }

            frames++
            val now = SystemClock.elapsedRealtime()
            if (now - hzTimer >= 1000) {
                TelemetryHub.pollHz.value = frames * 1000f / (now - hzTimer)
                frames = 0
                hzTimer = now
            }

            pollCount++
            delay(10)
        }
    }

    private fun emit(t: TelemetryData, raw: String) {
        TelemetryHub.telemetry.value = t
        if (TelemetryHub.logging.value) {
            csvLogger?.log(t, raw)
        }
    }

    private fun startLogging() {
        if (csvLogger != null) return
        val logsDir = File(getExternalFilesDir(null) ?: filesDir, "logs")
        val logger = CsvLogger(logsDir)
        val file = logger.startLogging()
        csvLogger = logger
        TelemetryHub.logging.value = true
        TelemetryHub.logPath.value = file.absolutePath
        TelemetryHub.log("Started logging to ${file.name}")
    }

    private fun stopLogging() {
        csvLogger?.stopLogging()
        csvLogger = null
        TelemetryHub.logging.value = false
        TelemetryHub.log("Stopped logging")
    }

    private fun stopWorker(reason: String) {
        workerJob?.cancel()
        workerJob = null

        try {
            ObdParser.resetTrackers()
            inputStream?.close()
            outputStream?.close()
        } catch (_: Exception) {}

        inputStream = null
        outputStream = null

        TelemetryHub.mode.value = Mode.IDLE
        TelemetryHub.status.value = reason
        TelemetryHub.pollHz.value = 0f

        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLogging()
        stopWorker("Service Destroyed")
    }
}
