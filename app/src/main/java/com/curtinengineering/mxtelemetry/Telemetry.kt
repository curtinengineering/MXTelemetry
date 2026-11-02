package com.curtinengineering.mxtelemetry

import android.os.SystemClock
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.math.abs

/**
 * Immutable snapshot of vehicle state for the current frame.
 */
data class TelemetryData(
    val timestampMs: Long,
    val rpm: Float,
    val speedKmh: Float,
    val throttlePlatePct: Float? = null,
    val pedalPct: Float? = null,
    val pedalDPct: Float? = null,
    val pedalEPct: Float? = null,
    val engineLoadPct: Float? = null,
    val mafGs: Float? = null,
    val coolantTempC: Float? = null,
    val intakeTempC: Float? = null,
    val oilTempC: Float? = null,
    val calculatedGear: String = "N",
    val clutchPedalPct: Float = 0f,
    val estimatedBrakePct: Float = 0f,
    val targetDownshiftRpm: Float? = null,
    val revMatchDeltaRpm: Float? = null,
    val lateralG: Float = 0f,
    val longitudinalG: Float = 0f,
    val instantMpg: Float = 0f,
    val fuelRateGph: Float = 0f,
    val suggestedGear: String? = null,
    val revMatchStatus: String = "",
    val isStallWarning: Boolean = false,
    val revMatchTargetGear: Int? = null,
    val isDecoupled: Boolean = false,
    val source: String = "obd"
) {
    val speedMph: Float get() = speedKmh / 1.609344f
    val coolantTempF: Float get() = (coolantTempC ?: 20f) * 1.8f + 32f
    val intakeTempF: Float get() = (intakeTempC ?: 20f) * 1.8f + 32f
    val oilTempF: Float get() = (oilTempC ?: 20f) * 1.8f + 32f
    val dynamicRedlineRpm: Float get() {
        val ect = coolantTempC ?: 90f
        return when {
            ect < 60f -> 6000f
            ect < 70f -> 6800f
            else -> 7200f
        }
    }
    val isRevMatchReady: Boolean get() = revMatchStatus == "1" || (revMatchDeltaRpm?.let { abs(it) < VehicleConstants.REV_MATCH_TOLERANCE_RPM } ?: false)
    val isOverRevRisk: Boolean get() = targetDownshiftRpm?.let { it > dynamicRedlineRpm } ?: false

    // Universal dynamic brake percentage (0..100%)
    val dynamicBrakePct: Float
        get() {
            val ped = pedalPct ?: throttlePlatePct ?: 0f
            return if (ped <= 2.0f && longitudinalG < -0.10f) {
                (((-longitudinalG - 0.10f) / 0.75f) * 100f).coerceIn(0f, 100f)
            } else {
                0f
            }
        }

    // Universal dynamic clutch percentage (0..100%)
    val dynamicClutchPct: Float
        get() = if (isDecoupled || clutchPedalPct > 50f) 100f else 0f
}

/**
 * Parses raw OBD-II CAN bus hexadecimal responses for speed, RPM, throttle, engine load,
 * coolant, intake, MAF, and pedal position into structured data classes.
 */
object ObdParser {
    const val PID_ENGINE_LOAD = 0x04
    const val PID_COOLANT_TEMP = 0x05
    const val PID_RPM = 0x0C
    const val PID_SPEED = 0x0D
    const val PID_INTAKE_TEMP = 0x0F
    const val PID_MAF = 0x10
    const val PID_THROTTLE = 0x11
    const val PID_PEDAL_5A = 0x5A

    private val BYTES_PER_PID = mapOf(
        PID_ENGINE_LOAD to 1,
        PID_COOLANT_TEMP to 1,
        PID_RPM to 2,
        PID_SPEED to 1,
        PID_INTAKE_TEMP to 1,
        PID_MAF to 2,
        PID_THROTTLE to 1,
        PID_PEDAL_5A to 1
    )

    private var prevSpeedMph = 0f
    private var lastSpeedKmh = 0f
    private var lastTimestampMs = 0L

    val gearTracker = GearTracker()
    val stallDetector = StallDetector()
    val revMatchTracker = RevMatchTracker()

    fun resetTrackers() {
        prevSpeedMph = 0f
        lastSpeedKmh = 0f
        lastTimestampMs = 0L
        gearTracker.reset()
        stallDetector.reset()
        revMatchTracker.reset()
    }

    fun parseResponse(raw: String): Map<Int, List<Int>> {
        val clean = raw.replace("\r", "").replace("\n", "").replace(" ", "").uppercase(Locale.US)
        if (!clean.startsWith("41")) return emptyMap()

        val bytes = mutableListOf<Int>()
        var i = 2
        while (i + 1 < clean.length) {
            val b = clean.substring(i, i + 2).toIntOrNull(16) ?: break
            bytes.add(b)
            i += 2
        }

        val out = mutableMapOf<Int, List<Int>>()
        i = 0
        while (i < bytes.size) {
            val pid = bytes[i]
            val n = BYTES_PER_PID[pid] ?: break
            if (i + 1 + n > bytes.size) break
            out.putIfAbsent(pid, bytes.subList(i + 1, i + 1 + n))
            i += 1 + n
        }
        return out
    }

    /** Single ingestion entry point for live, demo, and replay telemetry samples. */
    fun onSample(sample: TelemetryData, nowMs: Long = sample.timestampMs): TelemetryData {
        val rpm = sample.rpm
        val speedMph = sample.speedMph
        val pedalPct = sample.pedalPct ?: sample.throttlePlatePct ?: 0f

        val displayGearInt = gearTracker.update(nowMs, rpm, speedMph, pedalPct)
        val calcGearStr = displayGearInt?.toString() ?: "N"
        val isDecoupled = speedMph > 5.0f && displayGearInt == null

        val isStallAlert = stallDetector.update(rpm, speedMph, pedalPct, displayGearInt)

        val revTarget = revMatchTracker.update(nowMs, speedMph, isDecoupled, gearTracker.lastLockedGear)
        val targetDownshiftRpm = revTarget?.targetRpm
        val revMatchDeltaRpm = targetDownshiftRpm?.let { rpm - targetDownshiftRpm }
        val revMatchStatusStr = if (targetDownshiftRpm != null) {
            if (abs(rpm - targetDownshiftRpm) <= VehicleConstants.REV_MATCH_TOLERANCE_RPM) "1" else "0"
        } else ""

        return sample.copy(
            calculatedGear = calcGearStr,
            isDecoupled = isDecoupled,
            clutchPedalPct = if (isDecoupled) 100f else 0f,
            isStallWarning = isStallAlert,
            targetDownshiftRpm = targetDownshiftRpm,
            revMatchDeltaRpm = revMatchDeltaRpm,
            revMatchTargetGear = revTarget?.gear,
            revMatchStatus = revMatchStatusStr
        )
    }

    fun processValues(
        values: Map<Int, List<Int>>,
        sensorLatG: Float = 0f,
        sensorLongG: Float = 0f
    ): TelemetryData {
        val nowMs = SystemClock.elapsedRealtime()

        val rpm = values[PID_RPM]?.let { (it[0] * 256 + it[1]) / 4f } ?: 0f
        val speedKmh = values[PID_SPEED]?.get(0)?.toFloat() ?: 0f
        val speedMph = speedKmh / 1.609344f

        val pedal5A = values[PID_PEDAL_5A]?.let { it[0] * 100f / 255f }
        val throttlePlate = values[PID_THROTTLE]?.let { it[0] * 100f / 255f }
        val pedalPctVal = pedal5A ?: throttlePlate ?: 0f

        val dtSec = if (lastTimestampMs > 0L && nowMs > lastTimestampMs) {
            (nowMs - lastTimestampMs) / 1000f
        } else 0.05f

        // Decel Brake Estimation
        val decelG = if (dtSec > 0f) -((speedMph - prevSpeedMph) * 0.44704f) / (dtSec * 9.80665f) else 0f
        val calcLongG = if (abs(sensorLongG) > 0.02f) sensorLongG else -decelG.coerceIn(-1.2f, 1.2f)

        prevSpeedMph = speedMph
        lastSpeedKmh = speedKmh
        lastTimestampMs = nowMs

        val maf = values[PID_MAF]?.let { (it[0] * 256 + it[1]) / 100f }

        val instantMpg = if (maf != null && maf > 0.1f && speedMph > 2f) {
            ((speedMph * 7.107f) / maf).coerceIn(0f, 99.9f)
        } else {
            0f
        }

        val fuelGph = if (maf != null) (maf / 3.027f).coerceIn(0.1f, 50f) else 0f
        val estimatedBrake = if (pedalPctVal <= 2.0f && calcLongG < -0.10f) {
            (((-calcLongG - 0.10f) / 0.75f) * 100f).coerceIn(0f, 100f)
        } else 0f

        val rawSample = TelemetryData(
            timestampMs = System.currentTimeMillis(),
            rpm = rpm,
            speedKmh = speedKmh,
            throttlePlatePct = throttlePlate,
            pedalPct = pedalPctVal,
            pedalDPct = pedal5A,
            pedalEPct = null,
            engineLoadPct = values[PID_ENGINE_LOAD]?.get(0)?.let { it * 100f / 255f },
            mafGs = maf,
            coolantTempC = values[PID_COOLANT_TEMP]?.get(0)?.let { (it - 40).toFloat() },
            intakeTempC = values[PID_INTAKE_TEMP]?.get(0)?.let { (it - 40).toFloat() },
            oilTempC = null,
            estimatedBrakePct = estimatedBrake,
            lateralG = sensorLatG,
            longitudinalG = calcLongG,
            instantMpg = instantMpg,
            fuelRateGph = fuelGph,
            source = "obd"
        )

        return onSample(rawSample, nowMs)
    }
}

/**
 * Manages raw OBD-II socket communication commands and init sequences for ELM327 / vLinker adapters.
 */
class ObdCommander(private val input: InputStream, private val output: OutputStream) {

    fun send(cmd: String): String {
        output.write("$cmd\r".toByteArray(StandardCharsets.US_ASCII))
        output.flush()

        val sb = StringBuilder()
        val buf = ByteArray(256)
        val start = System.currentTimeMillis()

        while (System.currentTimeMillis() - start < 1500) {
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
        return sb.toString()
    }

    fun initAdapter() {
        send("AT Z")
        send("AT E0")
        send("AT L0")
        send("AT S0")
        send("AT SP 6") // ISO 15765-4 CAN (11 bit ID, 500 kbaud)
    }

    fun discoverPids(): Set<Int> {
        val res = send("01 00")
        val parsed = ObdParser.parseResponse(res)
        val bytes = parsed[0] ?: return emptySet()
        val pids = mutableSetOf<Int>()

        var bitIndex = 0
        for (b in bytes) {
            for (bit in 7 downTo 0) {
                bitIndex++
                if ((b and (1 shl bit)) != 0) {
                    pids.add(bitIndex)
                }
            }
        }
        return pids
    }
}
