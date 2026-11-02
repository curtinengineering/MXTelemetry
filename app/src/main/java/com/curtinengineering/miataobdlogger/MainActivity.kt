package com.curtinengineering.miataobdlogger

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var btnStyleGt7: Button
    private lateinit var btnStyleGt4: Button
    private lateinit var btnOrientation: Button
    private lateinit var btnShiftBeep: Button
    private lateinit var layoutGt7Hud: View
    private lateinit var layoutGt4Cluster: View

    private lateinit var shiftLightStrip: ShiftLightStripView
    private lateinit var s2000TachView: S2000TachView
    private lateinit var mfdCarouselView: MfdCarouselView
    private lateinit var gt4ClusterView: GT4ClusterView

    private lateinit var tvSpeed: TextView
    private lateinit var tvGear: TextView
    private lateinit var tvRpm: TextView
    private lateinit var cardGear: MaterialCardView

    private lateinit var tvPeakBadges: TextView
    private lateinit var btnTare: Button
    private lateinit var btnResetPeaks: Button

    private lateinit var pbThrottle: ProgressBar
    private lateinit var pbBrake: ProgressBar
    private lateinit var pbClutch: ProgressBar
    private lateinit var tvThrottleBarLabel: TextView
    private lateinit var tvBrakeBarLabel: TextView
    private lateinit var tvClutchBarLabel: TextView

    private lateinit var layoutUtilityContent: View
    private lateinit var cardUtility: View

    private lateinit var tvStatus: TextView
    private lateinit var swDemo: SwitchCompat
    private lateinit var spDevices: Spinner
    private lateinit var btnConnect: Button
    private lateinit var btnLog: Button
    private lateinit var btnShare: Button
    private lateinit var btnClear: Button

    private lateinit var tvInfo: TextView
    private lateinit var svRaw: ScrollView
    private lateinit var tvRaw: TextView

    private lateinit var tvBrightnessOverlay: TextView
    private lateinit var vBrightnessEdgeStrip: View
    private val brightnessHandler = Handler(Looper.getMainLooper())
    private val hideBrightnessRunnable = Runnable {
        tvBrightnessOverlay.animate()
            .alpha(0f)
            .setDuration(300L)
            .withEndAction { tvBrightnessOverlay.visibility = View.GONE }
    }
    private var initialY = 0f
    private var isAdjustingBrightness = false

    private var activeStyleIndex = 1 // 1 = GT7 Modern, 2 = GT4 Classic

    private var shiftBeepTrack: AudioTrack? = null
    private var beepSoundBuffer: ShortArray? = null
    private var isShiftBeepEnabled = true
    private var lastBeepTimeMs = 0L

    private var sensorManager: SensorManager? = null
    private var accelSensor: Sensor? = null
    private var rawLatG = 0f
    private var rawLongG = 0f
    private var latGOffset = 0f
    private var longGOffset = 0f

    private var sessionMaxLatG = 0f
    private var sessionMaxDecelG = 0f
    private var sessionPeakRpm = 0f

    private var devices: List<BluetoothDevice> = emptyList()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshDevices()
            renderControls()
            if (!hasBtPermission()) toast("Bluetooth permission is needed to talk to the adapter")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val root = findViewById<View>(R.id.root)
        val pad = (8 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(b.left + pad, b.top + pad, b.right + pad, b.bottom + pad)
            insets
        }

        btnStyleGt7 = findViewById(R.id.btnStyleGt7)
        btnStyleGt4 = findViewById(R.id.btnStyleGt4)
        btnOrientation = findViewById(R.id.btnOrientation)
        btnShiftBeep = findViewById(R.id.btnShiftBeep)
        layoutGt7Hud = findViewById(R.id.layoutGt7Hud)
        layoutGt4Cluster = findViewById(R.id.layoutGt4Cluster)

        shiftLightStrip = findViewById(R.id.shiftLightStrip)
        s2000TachView = findViewById(R.id.s2000Tach)
        mfdCarouselView = findViewById(R.id.mfdCarousel)
        gt4ClusterView = findViewById(R.id.gt4ClusterView)

        tvSpeed = findViewById(R.id.tvSpeed)
        tvGear = findViewById(R.id.tvGear)
        tvRpm = findViewById(R.id.tvRpm)
        cardGear = findViewById(R.id.cardGear)

        tvPeakBadges = findViewById(R.id.tvPeakBadges)
        btnTare = findViewById(R.id.btnTare)
        btnResetPeaks = findViewById(R.id.btnResetPeaks)

        pbThrottle = findViewById(R.id.pbThrottle)
        pbBrake = findViewById(R.id.pbBrake)
        pbClutch = findViewById(R.id.pbClutch)
        tvThrottleBarLabel = findViewById(R.id.tvThrottleBarLabel)
        tvBrakeBarLabel = findViewById(R.id.tvBrakeBarLabel)
        tvClutchBarLabel = findViewById(R.id.tvClutchBarLabel)

        cardUtility = findViewById(R.id.cardUtility)
        layoutUtilityContent = findViewById(R.id.layoutUtilityContent)

        tvStatus = findViewById(R.id.tvStatus)
        swDemo = findViewById(R.id.swDemo)
        spDevices = findViewById(R.id.spDevices)
        btnConnect = findViewById(R.id.btnConnect)
        btnLog = findViewById(R.id.btnLog)
        btnShare = findViewById(R.id.btnShare)
        btnClear = findViewById(R.id.btnClear)

        tvInfo = findViewById(R.id.tvInfo)
        svRaw = findViewById(R.id.svRaw)
        tvRaw = findViewById(R.id.tvRaw)

        // Initialize Low-Latency PCM AudioTrack Sine Wave Shift Beep
        initBeepAudio()

        // Native Hardware Accelerometer Sensor Initialization
        sensorManager = getSystemService(SensorManager::class.java)
        accelSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        // Theme / Style Pill Selectors
        btnStyleGt7.setOnClickListener { setGaugeStyle(1) }
        btnStyleGt4.setOnClickListener { setGaugeStyle(2) }

        // On-Screen Orientation Lock PopupMenu Anchor
        btnOrientation.setOnClickListener { v ->
            val popup = PopupMenu(this, v)
            popup.menu.add(0, 1, 0, "Auto-Rotate (Sensor)")
            popup.menu.add(0, 2, 1, "Lock Landscape")
            popup.menu.add(0, 3, 2, "Lock Portrait")
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> {
                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
                        btnOrientation.text = "ROTATE: AUTO ▾"
                    }
                    2 -> {
                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                        btnOrientation.text = "ROTATE: LAND ▾"
                    }
                    3 -> {
                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        btnOrientation.text = "ROTATE: PORT ▾"
                    }
                }
                true
            }
            popup.show()
        }

        // Speaker Mute/Unmute Shift Beep Toggle Button with Immediate Audio Test Chirp
        btnShiftBeep.setOnClickListener {
            isShiftBeepEnabled = !isShiftBeepEnabled
            btnShiftBeep.text = if (isShiftBeepEnabled) "BEEP: ON" else "BEEP: OFF"
            btnShiftBeep.setTextColor(if (isShiftBeepEnabled) 0xFF00E676.toInt() else 0xFF8A99AD.toInt())
            if (isShiftBeepEnabled) {
                triggerShiftBeep()
            }
        }

        // Tare / Zero-G Calibration Touch Action
        btnTare.setOnClickListener {
            latGOffset = rawLatG
            longGOffset = rawLongG
            toast("G-Meter Calibrated (Zero G set)")
        }

        // Reset Session Peak Hold Touch Action
        btnResetPeaks.setOnClickListener {
            sessionMaxLatG = 0f
            sessionMaxDecelG = 0f
            sessionPeakRpm = 0f
            mfdCarouselView.resetPeaks()
            updatePeakBadgeText()
            toast("Session peaks reset")
        }

        // Long-Press Session Peak Bar to reveal/hide Utility & Diagnostics panel when parked
        val layoutSessionPeaks = findViewById<View>(R.id.layoutSessionPeaks)
        layoutSessionPeaks.setOnLongClickListener {
            cardUtility.visibility = if (cardUtility.visibility == View.GONE) View.VISIBLE else View.GONE
            toast(if (cardUtility.visibility == View.VISIBLE) "Diagnostics Panel Opened" else "Diagnostics Panel Hidden")
            true
        }

        swDemo.setOnClickListener {
            if (swDemo.isChecked) {
                if (!hasBtPermission()) {
                    swDemo.isChecked = false
                    requestMissingPermissions()
                    return@setOnClickListener
                }
                ObdBluetoothService.send(this, ObdBluetoothService.ACTION_START_DEMO)
                swDemo.isChecked = false   // the mode flow turns it on once the service really starts
            } else {
                ObdBluetoothService.send(this, ObdBluetoothService.ACTION_STOP)
            }
        }

        btnConnect.setOnClickListener {
            when (TelemetryHub.mode.value) {
                Mode.BLUETOOTH -> ObdBluetoothService.send(this, ObdBluetoothService.ACTION_STOP)
                Mode.DEMO -> toast("Turn demo mode off first")
                Mode.IDLE -> {
                    if (!hasBtPermission()) { requestMissingPermissions(); return@setOnClickListener }
                    val d = devices.getOrNull(spDevices.selectedItemPosition)
                    if (d == null) { toast("Pair the vLinker in Android's Bluetooth settings first"); return@setOnClickListener }
                    ObdBluetoothService.send(this, ObdBluetoothService.ACTION_START_BT, d.address)
                }
            }
        }

        btnLog.setOnClickListener {
            val action = if (TelemetryHub.logging.value) ObdBluetoothService.ACTION_STOP_LOG
            else ObdBluetoothService.ACTION_START_LOG
            ObdBluetoothService.send(this, action)
        }

        btnShare.setOnClickListener { shareLatestLog() }
        btnClear.setOnClickListener { TelemetryHub.clearRaw() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { TelemetryHub.telemetry.collect { renderTelemetry(it) } }
                launch { TelemetryHub.mode.collect { renderControls() } }
                launch { TelemetryHub.logging.collect { renderControls() } }
                launch { TelemetryHub.status.collect { tvStatus.text = it } }
                launch { TelemetryHub.raw.collect { renderRaw(it) } }
                launch {
                    combine(TelemetryHub.pollHz, TelemetryHub.supportedPids) { hz, pids -> hz to pids }
                        .collect { (hz, pids) -> renderInfo(hz, pids) }
                }
            }
        }

        if (missingPermissions().isNotEmpty()) requestMissingPermissions()
    }

    private fun initBeepAudio() {
        try {
            val sampleRate = 44100
            val numSamples = (sampleRate * 0.12).toInt() // 120ms beep
            beepSoundBuffer = ShortArray(numSamples)
            val freqOfTone = 2000.0 // 2 kHz crisp prompt tone

            for (i in 0 until numSamples) {
                val sample = Math.sin(2.0 * Math.PI * i.toDouble() / (sampleRate / freqOfTone))
                beepSoundBuffer!![i] = (sample * 32767).toInt().toShort()
            }

            val bufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            val audioFormat = AudioFormat.Builder()
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .build()

            shiftBeepTrack = AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            shiftBeepTrack?.play()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun triggerShiftBeep() {
        if (!isShiftBeepEnabled) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastBeepTimeMs > 600L) {
            lastBeepTimeMs = now
            beepSoundBuffer?.let { buffer ->
                shiftBeepTrack?.write(buffer, 0, buffer.size)
            }
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev != null) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (ev.x < resources.displayMetrics.widthPixels * 0.18f) {
                        initialY = ev.y
                        isAdjustingBrightness = true
                        vBrightnessEdgeStrip.animate().alpha(0.8f).setDuration(150L)
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isAdjustingBrightness) {
                        val deltaY = initialY - ev.y
                        val lp = window.attributes
                        val currentBrightness = if (lp.screenBrightness < 0f) 0.8f else lp.screenBrightness
                        val newBrightness = (currentBrightness + (deltaY / 800f)).coerceIn(0.05f, 1.0f)

                        lp.screenBrightness = newBrightness
                        window.attributes = lp

                        val pct = (newBrightness * 100).roundToInt()
                        showBrightnessOverlay(pct)
                        initialY = ev.y
                        return true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isAdjustingBrightness) {
                        vBrightnessEdgeStrip.animate().alpha(0f).setDuration(300L)
                    }
                    isAdjustingBrightness = false
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun showBrightnessOverlay(pct: Int) {
        tvBrightnessOverlay.text = "☀ BRIGHTNESS: $pct%"
        tvBrightnessOverlay.bringToFront()
        tvBrightnessOverlay.animate().cancel()
        tvBrightnessOverlay.alpha = 1.0f
        tvBrightnessOverlay.visibility = View.VISIBLE

        brightnessHandler.removeCallbacks(hideBrightnessRunnable)
        brightnessHandler.postDelayed(hideBrightnessRunnable, 1200L)
    }

    private fun setGaugeStyle(styleIndex: Int) {
        activeStyleIndex = styleIndex
        if (styleIndex == 1) {
            layoutGt7Hud.visibility = View.VISIBLE
            layoutGt4Cluster.visibility = View.GONE
            btnStyleGt7.setTextColor(0xFF00E5FF.toInt())
            btnStyleGt4.setTextColor(0xFF8A99AD.toInt())
        } else {
            layoutGt7Hud.visibility = View.GONE
            layoutGt4Cluster.visibility = View.VISIBLE
            btnStyleGt7.setTextColor(0xFF8A99AD.toInt())
            btnStyleGt4.setTextColor(0xFF00E5FF.toInt())
        }
    }

    override fun onResume() {
        super.onResume()
        if (TelemetryHub.mode.value == Mode.IDLE) refreshDevices()
        renderControls()
        accelSensor?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager?.unregisterListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        shiftBeepTrack?.stop()
        shiftBeepTrack?.release()
        shiftBeepTrack = null
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        val rawX = event.values[0] / 9.80665f // Lateral G
        val rawY = event.values[1] / 9.80665f // Longitudinal G

        // Low-pass filter for road vibration noise
        rawLatG += (rawX - rawLatG) * 0.15f
        rawLongG += (rawY - rawLongG) * 0.15f
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ---------------------------------------------------------------- permissions

    private fun hasBtPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun missingPermissions(): List<String> {
        val list = mutableListOf<String>()
        if (!hasBtPermission()) list += Manifest.permission.BLUETOOTH_CONNECT
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) list += Manifest.permission.POST_NOTIFICATIONS
        return list
    }

    private fun requestMissingPermissions() {
        val list = missingPermissions()
        if (list.isNotEmpty()) permissionLauncher.launch(list.toTypedArray())
    }

    // ---------------------------------------------------------------- device list

    @SuppressLint("MissingPermission")   // guarded by hasBtPermission()
    private fun refreshDevices() {
        if (!hasBtPermission()) {
            devices = emptyList()
            spDevices.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("(grant Bluetooth permission)"))
            return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        devices = adapter?.bondedDevices?.sortedBy { it.name ?: "" } ?: emptyList()
        val names = devices.map { "${it.name ?: "Unknown"}  (${it.address})" }
            .ifEmpty { listOf("(no paired devices - pair the vLinker in Android settings)") }
        spDevices.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        val guess = devices.indexOfFirst {
            val n = (it.name ?: "").lowercase()
            "vlink" in n || "obd" in n || "elm" in n
        }
        if (guess >= 0) spDevices.setSelection(guess)
    }

    // ---------------------------------------------------------------- rendering

    private fun renderControls() {
        val mode = TelemetryHub.mode.value
        swDemo.isChecked = mode == Mode.DEMO
        swDemo.isEnabled = mode != Mode.BLUETOOTH
        btnConnect.text = if (mode == Mode.BLUETOOTH) "Disconnect" else "Connect"
        btnConnect.isEnabled = mode != Mode.DEMO
        spDevices.isEnabled = mode == Mode.IDLE
        btnLog.isEnabled = mode != Mode.IDLE
        btnLog.text = if (TelemetryHub.logging.value) "Stop logging" else "Start logging"

        // Screen Wakelock: Keep display awake when active
        if (mode != Mode.IDLE || TelemetryHub.logging.value) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun updatePeakBadgeText() {
        tvPeakBadges.text = String.format(
            Locale.US,
            "MAX LAT: %.2fG  |  MAX DECEL: %.2fG  |  PEAK RPM: %,.0f",
            sessionMaxLatG,
            sessionMaxDecelG,
            sessionPeakRpm
        )
    }

    private fun renderTelemetry(t: TelemetryData?) {
        if (t == null) return

        // Update Primary Driving Cluster (Speed + Gear + RPM)
        val speedMph = t.speedKmh * 0.621371f
        tvSpeed.text = String.format(Locale.US, "%.0f", speedMph)
        tvGear.text = t.calculatedGear
        tvRpm.text = String.format(Locale.US, "%,.0f RPM", t.rpm)

        if (t.rpm >= t.dynamicRedlineRpm) {
            cardGear.setCardBackgroundColor(0xFFD50000.toInt())
            cardGear.strokeColor = 0xFFFFFFFF.toInt()
        } else {
            cardGear.setCardBackgroundColor(0xFF0D1117.toInt())
            cardGear.strokeColor = 0xFF00E5FF.toInt()
        }

        // 1. Shift Light Strip
        shiftLightStrip.setRpm(t.rpm, t.dynamicRedlineRpm)

        // 2. Honda S2000 Segmented Tachometer & Readout
        s2000TachView.setTelemetry(
            t.rpm,
            t.speedKmh,
            t.calculatedGear,
            t.targetDownshiftRpm,
            t.isRevMatchReady,
            t.isOverRevRisk,
            t.dynamicRedlineRpm
        )

        // 3. Active Calibrated Accelerometer G-Forces
        val activeLatG = if (TelemetryHub.mode.value == Mode.DEMO) t.lateralG else (rawLatG - latGOffset)
        val activeLongG = if (TelemetryHub.mode.value == Mode.DEMO) t.longitudinalG else (rawLongG - longGOffset)

        // 4. Multi-Function Display (MFD) Carousel Widget (Embeds Page 1: Live 2D Friction Circle)
        mfdCarouselView.setTelemetry(t, activeLatG, activeLongG, TelemetryHub.pollHz.value)

        // 5. GT4 Classic Instrument Cluster View
        val throttlePct = t.pedalPct ?: t.throttlePlatePct ?: 0f
        gt4ClusterView.setTelemetry(
            t.rpm,
            t.speedKmh,
            t.calculatedGear,
            throttlePct,
            t.dynamicBrakePct,
            activeLatG,
            t.oilTempC
        )

        // Session Peak-Hold Tracking
        val currentAbsLat = abs(activeLatG)
        val currentDecel = if (activeLongG < 0f) abs(activeLongG) else 0f
        if (currentAbsLat > sessionMaxLatG) sessionMaxLatG = currentAbsLat
        if (currentDecel > sessionMaxDecelG) sessionMaxDecelG = currentDecel
        if (t.rpm > sessionPeakRpm) sessionPeakRpm = t.rpm
        updatePeakBadgeText()

        // 6. Center Vertical Bar Gauges (THR, BRK, CLT)
        val throttleInt = throttlePct.roundToInt().coerceIn(0, 100)
        val brakeInt = t.dynamicBrakePct.roundToInt().coerceIn(0, 100)
        val clutchInt = t.dynamicClutchPct.roundToInt().coerceIn(0, 100)

        pbThrottle.progress = throttleInt
        pbBrake.progress = brakeInt
        pbClutch.progress = clutchInt

        tvThrottleBarLabel.text = String.format(Locale.US, "THR\n%d%%", throttleInt)
        tvBrakeBarLabel.text = String.format(Locale.US, "BRK\n%d%%", brakeInt)
        tvClutchBarLabel.text = String.format(Locale.US, "CLT\n%d%%", clutchInt)

        tvThrottleBarLabel.setTextColor(0xFF00E676.toInt())
        tvBrakeBarLabel.setTextColor(0xFFFF1744.toInt())
        tvClutchBarLabel.setTextColor(0xFF00B0FF.toInt())
    }

    private fun renderInfo(hz: Float, pids: Set<Int>) {
        val support = if (pids.isEmpty()) "PID discovery: not run / no answer" else
            "Supported: RPM ${yn(pids, ObdParser.PID_RPM)}, speed ${yn(pids, ObdParser.PID_SPEED)}, pedal5A ${yn(pids, ObdParser.PID_PEDAL_5A)}, plate ${yn(pids, ObdParser.PID_THROTTLE)}, load ${yn(pids, ObdParser.PID_ENGINE_LOAD)}, MAF ${yn(pids, ObdParser.PID_MAF)}, ECT ${yn(pids, ObdParser.PID_COOLANT_TEMP)}, IAT ${yn(pids, ObdParser.PID_INTAKE_TEMP)} (${pids.size} PIDs total)"
        tvInfo.text = String.format(Locale.US, "Update rate: %.1f Hz\n%s", hz, support)
    }

    private fun yn(pids: Set<Int>, pid: Int) = if (pid in pids) "yes" else "no"

    private fun renderRaw(lines: List<String>) {
        tvRaw.text = lines.joinToString("\n")
        svRaw.post { svRaw.fullScroll(View.FOCUS_DOWN) }
    }

    // ---------------------------------------------------------------- sharing

    private fun shareLatestLog() {
        val dir = File(getExternalFilesDir(null) ?: filesDir, "logs")
        val file = TelemetryHub.logPath.value?.let { File(it) }?.takeIf { it.exists() }
            ?: dir.listFiles()?.maxByOrNull { it.lastModified() }
        if (file == null) { toast("No log files yet - start logging first"); return }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Share ${file.name}"))
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
