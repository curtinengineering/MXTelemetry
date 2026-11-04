package com.curtinengineering.mxtelemetry

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

class PidScheduler(supported: Set<Int>) {
    private val fast = listOf(0x0C, 0x0D, if (0x5A in supported) 0x5A else 0x11)
    private val slow = listOf(0x05, 0x0F, 0x10, 0x04, 0x11)
        .filter { (it in supported) && (it !in fast) }
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
        private const val DEBUG_VERBOSE_BYTES = false

        fun send(context: Context, action: String, mac: String? = null) {
            val intent = Intent(context, ObdBluetoothService::class.java).apply {
                this.action = action
                if (mac != null) {
                    putExtra(EXTRA_MAC, mac)
                }
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

    private var activeSocket: BluetoothSocket? = null
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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                        stopWorker("Missing BLUETOOTH_CONNECT permission")
                        return@launch
                    }
                }

                val adapter = getSystemService(BluetoothManager::class.java)?.adapter
                if (adapter == null || !adapter.isEnabled) {
                    stopWorker("Bluetooth is disabled")
                    return@launch
                }

                val device = adapter.getRemoteDevice(mac)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED && adapter.isDiscovering) {
                        adapter.cancelDiscovery()
                    }
                } else {
                    adapter.cancelDiscovery()
                }

                val sppUuid = java.util.UUID.fromString("00001101-0000-1000-8000-00885F9B34FB")
                Log.d("OBD_RAW", "Attempting socket connection to $mac...")

                var socket: BluetoothSocket? = null
                try {
                    Log.d("OBD_RAW", "Trying createInsecureRfcommSocketToServiceRecord...")
                    val s = device.createInsecureRfcommSocketToServiceRecord(sppUuid)
                    s.connect()
                    socket = s
                    Log.d("OBD_RAW", "Connected via insecure RFCOMM!")
                } catch (e1: Exception) {
                    Log.w("OBD_RAW", "Insecure RFCOMM failed: ${e1.message}. Trying secure RFCOMM...")
                    try {
                        Log.d("OBD_RAW", "Trying createRfcommSocketToServiceRecord...")
                        val s = device.createRfcommSocketToServiceRecord(sppUuid)
                        s.connect()
                        socket = s
                        Log.d("OBD_RAW", "Connected via secure RFCOMM!")
                    } catch (e2: Exception) {
                        Log.w("OBD_RAW", "Secure RFCOMM failed: ${e2.message}. Trying reflection channel 1...")
                        try {
                            val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                            val s = method.invoke(device, 1) as BluetoothSocket
                            s.connect()
                            socket = s
                            Log.d("OBD_RAW", "Connected via reflection channel 1!")
                        } catch (e3: Exception) {
                            Log.e("OBD_RAW", "ERROR: All socket connection methods failed", e3)
                            throw e3
                        }
                    }
                }

                val connectedSocket = socket ?: throw IllegalStateException("Socket is null after connection attempts")
                activeSocket = connectedSocket
                Log.d("OBD_RAW", "socket.connect() succeeded!")

                inputStream = connectedSocket.inputStream
                outputStream = connectedSocket.outputStream

                TelemetryHub.status.value = "Connected. Initializing ELM327..."
                runObdLoop()

            } catch (e: Exception) {
                Log.e("OBD_RAW", "ERROR: Connection failed: ${e.localizedMessage}", e)
                stopWorker("Connection failed: ${e.localizedMessage}")
            }
        }
    }

    /**
     * Blocking prompt-oriented command runner guaranteed to execute off the main thread (Dispatchers.IO).
     * Accumulates characters until the ELM prompt '>' is received.
     * Throws IOException only when the socket connection is broken (EOF or stream error).
     */
    private suspend fun sendCommandRaw(command: String): String = withContext(Dispatchers.IO) {
        val out = outputStream ?: throw IOException("Output stream is null")
        val inStream = inputStream ?: throw IOException("Input stream is null")

        val formatted = command.trim() + "\r"
        Log.d("OBD_RAW", "TX >> $command")
        out.write(formatted.toByteArray(StandardCharsets.US_ASCII))
        out.flush()

        val buffer = StringBuilder()
        var b: Int
        while (true) {
            b = inStream.read()
            if (b == -1) throw IOException("Socket EOF (-1) reached")
            val c = b.toChar()
            if (DEBUG_VERBOSE_BYTES) {
                Log.d("OBD_RAW", "RX << '$c' (${c.code})")
            }
            if (c == '>') {
                break // Prompt received, adapter is ready for next command
            }
            buffer.append(c)
        }

        val cleaned = buffer.toString()
            .replace("\r", "")
            .replace("\n", "")
            .replace(" ", "")
            .trim()

        Log.d("OBD_RAW", "TX >> $command -> RX << '$cleaned'")
        return@withContext cleaned
    }

    /**
     * Standard proven ELM327 initialization sequence.
     * Uses non-blocking coroutine delays and wraps non-critical setup calls in try/catch.
     */
    private suspend fun initializeAdapter(): Boolean {
        Log.d("OBD_RAW", "Starting ELM327 initialization sequence...")
        delay(500)

        // 1. Reset
        try {
            val resZ = sendCommandRaw("ATZ")
            Log.d("OBD_RAW", "ATZ res: '$resZ'")
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            Log.w("OBD_RAW", "ATZ non-fatal exception: ${e.message}")
        }

        delay(1000) // Allow microcontroller reboot

        // 2. Setup formatting
        try { sendCommandRaw("ATE0") } catch (e: IOException) { throw e } catch (_: Exception) {} // Echo off
        try { sendCommandRaw("ATL0") } catch (e: IOException) { throw e } catch (_: Exception) {} // Linefeeds off
        try { sendCommandRaw("ATS0") } catch (e: IOException) { throw e } catch (_: Exception) {} // Spaces off
        try { sendCommandRaw("ATH0") } catch (e: IOException) { throw e } catch (_: Exception) {} // Headers off

        // 3. Protocol selection for Mazda SkyActiv (ISO 15765-4 CAN 11-bit 500k)
        val protocolRes = try {
            sendCommandRaw("ATSP6")
        } catch (e: IOException) {
            throw e
        } catch (_: Exception) {
            ""
        }
        Log.d("OBD_RAW", "ATSP6 res: '$protocolRes'")

        // 4. Test bus connection with a simple 0100 (Supported PIDs)
        val testRes = try {
            sendCommandRaw("0100")
        } catch (e: IOException) {
            throw e
        } catch (_: Exception) {
            ""
        }
        Log.d("OBD_RAW", "0100 test res: '$testRes'")

        if (testRes.contains("SEARCHING") || testRes.contains("UNABLE") || testRes.contains("ERROR") || testRes.isEmpty()) {
            Log.w("OBD_RAW", "ATSP6 bus test returned '$testRes'. Retrying with ATSP0 (auto)...")
            try { sendCommandRaw("ATSP0") } catch (e: IOException) { throw e } catch (_: Exception) {}
            val retryRes = try { sendCommandRaw("0100") } catch (e: IOException) { throw e } catch (_: Exception) { "" }
            Log.d("OBD_RAW", "ATSP0 0100 test res: '$retryRes'")
            if (retryRes.contains("UNABLE") || retryRes.contains("NODATA") || retryRes.isEmpty()) {
                Log.w("OBD_RAW", "Ignition is off or CAN bus not answering ($retryRes)")
                return false
            }
        }

        return true
    }

    private suspend fun runObdLoop() = withContext(Dispatchers.IO) {
        val adapterReady = try {
            initializeAdapter()
        } catch (e: IOException) {
            Log.e("OBD_RAW", "Bluetooth link broken during initialization", e)
            stopWorker("Bluetooth link severed: ${e.localizedMessage}")
            return@withContext
        }

        if (!adapterReady) {
            TelemetryHub.status.value = "Connected to adapter (Ignition off?)"
        } else {
            TelemetryHub.status.value = "Discovering PIDs..."
        }

        val supported = mutableSetOf<Int>()
        try {
            var base = 0x00
            while (base <= 0x60) {
                val raw = sendCommandRaw("01%02X".format(base))
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
            try { sendCommandRaw("AT DPN") } catch (_: Exception) {}
        } catch (e: IOException) {
            Log.e("OBD_RAW", "Bluetooth link broken during PID discovery", e)
            stopWorker("Bluetooth link severed: ${e.localizedMessage}")
            return@withContext
        } catch (e: Exception) {
            Log.w("OBD_RAW", "PID discovery non-fatal exception: ${e.message}")
        }

        if (supported.isEmpty()) {
            supported.addAll(setOf(0x0C, 0x0D, 0x11, 0x05, 0x0F, 0x10, 0x04))
        }
        TelemetryHub.supportedPids.value = supported

        val scheduler = PidScheduler(supported)
        val cachedValues = HashMap<Int, List<Int>>()
        var pollCount = 0L
        var frames = 0
        var hzTimer = SystemClock.elapsedRealtime()

        ObdParser.resetTrackers()
        TelemetryHub.status.value = "Live (~13-15Hz)"

        while (currentCoroutineContext().isActive) {
            val verbose = pollCount < 5 || pollCount % 50 == 0L

            val raw = try {
                val batch = scheduler.nextBatch()
                val cmdString = "01" + batch.joinToString("") { "%02X".format(it) } +
                        if (USE_RESPONSE_COUNT_DIGIT) "1" else ""
                sendCommandRaw(cmdString)
            } catch (e: IOException) {
                Log.e("OBD_RAW", "Bluetooth socket IO exception during polling: ${e.localizedMessage}", e)
                stopWorker("Bluetooth link severed: ${e.localizedMessage}")
                break
            } catch (e: Exception) {
                Log.w("OBD_RAW", "Non-fatal polling exception: ${e.localizedMessage}")
                delay(50)
                continue
            }

            if (raw.contains("NODATA") || raw.contains("SEARCHING") || raw.contains("UNABLE") || raw.contains("ERROR") || raw.isEmpty()) {
                if (verbose) Log.d("OBD_RAW", "No data response from vehicle: '$raw'")
                TelemetryHub.status.value = "No valid data (ignition on?)"
                delay(50)
                continue
            }

            val parsed = ObdParser.parseResponse(raw)
            if (parsed.isNotEmpty()) {
                cachedValues.putAll(parsed)
                val t = ObdParser.processValues(
                    cachedValues,
                    sensorLatG = TelemetryHub.latestSensorLatG,
                    sensorLongG = TelemetryHub.latestSensorLongG
                )
                emit(t, raw)
                if (verbose) TelemetryHub.log("TX -> RX: ${raw.replace("\r", " ").replace("\n", " ")}")
            } else {
                if (verbose) Log.d("OBD_RAW", "Unparsed CAN response: '$raw'")
                TelemetryHub.status.value = "Reading data..."
                delay(50)
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
            try { inputStream?.close() } catch (_: Exception) {}
            try { outputStream?.close() } catch (_: Exception) {}
            try { activeSocket?.close() } catch (_: Exception) {}
        } catch (e: Exception) {
            Log.e("OBD_RAW", "Error closing resources in stopWorker", e)
        } finally {
            activeSocket = null
            inputStream = null
            outputStream = null
        }

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
