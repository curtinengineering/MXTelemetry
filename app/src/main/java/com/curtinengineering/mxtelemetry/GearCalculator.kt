package com.curtinengineering.mxtelemetry

import kotlin.math.abs
import kotlin.math.ln

object VehicleConstants {
    // rpm per mph with the drivetrain locked.
    // Calibrated empirical medians derived from physical road testing (ND2 6-Speed Manual):
    // 1st: 210.8, 2nd: 123.3, 3rd: 83.4, 4th: 65.4, 5th: 52.8, 6th: 40.9
    val RATIOS = floatArrayOf(210.8f, 123.3f, 83.4f, 65.4f, 52.8f, 40.9f)

    const val IDLE_RPM = 755f
    const val STALL_RPM = IDLE_RPM - 75f      // 680
    const val TACH_MAX_RPM = 8000f
    const val DOWNSHIFT_MAX_RPM = 6800f       // hide ghost if target would be above this
    const val REV_MATCH_TOLERANCE_RPM = 150f
}

object GearCalculator {
    private const val MIN_SPEED_MPH = 3.0f

    // Empirical gear ratio bands derived from ND2 physical road test dataset
    private val GEAR_BANDS = listOf(
        175.0f..250.0f, // 1st Gear (Median: 210.8)
        105.0f..140.0f, // 2nd Gear (Median: 123.3)
        75.0f..93.0f,   // 3rd Gear (Median: 83.4)
        59.0f..72.0f,   // 4th Gear (Median: 65.4)
        47.0f..58.0f,   // 5th Gear (Median: 52.8)
        37.0f..45.0f    // 6th Gear (Median: 40.9)
    )

    /** 1..6, or null when no gear fits (clutch in / neutral / slipping / shifting / stopped). */
    fun match(rpm: Float, speedMph: Float, pedalPct: Float): Int? {
        if (speedMph < MIN_SPEED_MPH) return null
        // Near idle with pedal up is never a trustworthy locked gear (clutch-in coasting)
        if (pedalPct < 5f && rpm in VehicleConstants.STALL_RPM..(VehicleConstants.IDLE_RPM + 75f)) return null
        val ratio = rpm / speedMph

        var best = -1
        var bestErr = Float.MAX_VALUE
        for (i in VehicleConstants.RATIOS.indices) {
            val err = abs(ln(ratio / VehicleConstants.RATIOS[i]))
            if (err < bestErr) {
                bestErr = err
                best = i
            }
        }

        if (best == -1) return null

        val maxAllowedErr = if (pedalPct < 15f) 0.06f else 0.10f
        if (bestErr <= maxAllowedErr && ratio in GEAR_BANDS[best]) {
            return best + 1
        }
        return null
    }
}

/** Debounce + latch. Call update() ONCE PER NEW OBD SAMPLE, not once per rendered frame. */
class GearTracker {
    var displayGear: Int? = null; private set       // null = N / decoupled
    var lastLockedGear: Int? = null; private set    // survives clutch-in; used by the rev-match ghost
    private var candidate: Int? = null
    private var count = 0
    private var gearBeforeLoss: Int? = null
    private var lostAtMs = 0L

    /** Call ONCE PER NEW SAMPLE (live, demo or replay). nowMs = the sample's timestamp. */
    fun update(nowMs: Long, rpm: Float, speedMph: Float, pedalPct: Float): Int? {
        val m = GearCalculator.match(rpm, speedMph, pedalPct)
        if (m == candidate) count++ else { candidate = m; count = 1 }

        val before = gearBeforeLoss
        val upAfterLoss = m != null && before != null && m > before &&
                nowMs - lostAtMs < 2500L && pedalPct < 15f
        val needed = if (upAfterLoss) 5 else 2

        if (count >= needed && m != displayGear) {
            if (displayGear != null && m == null) { gearBeforeLoss = displayGear; lostAtMs = nowMs }
            displayGear = m
        }
        val g = displayGear
        if (g != null && candidate == g && count >= 5 && !upAfterLoss) lastLockedGear = g
        if (speedMph < 3f) { lastLockedGear = null; gearBeforeLoss = null }
        return displayGear
    }

    fun reset() {
        displayGear = null
        lastLockedGear = null
        candidate = null
        count = 0
        gearBeforeLoss = null
        lostAtMs = 0L
    }
}

class StallDetector {
    private val hist = ArrayDeque<Float>()
    private var hits = 0

    fun reset() {
        hist.clear()
        hits = 0
    }

    /** Call once per new OBD sample. Returns true while the alert should show. */
    fun update(rpm: Float, speedMph: Float, pedalPct: Float, lockedGear: Int?): Boolean {
        hist.addLast(rpm)
        if (hist.size > 4) hist.removeFirst()
        val notRising = hist.size >= 3 && rpm <= hist.first()
        val cond = speedMph > 4f && rpm < VehicleConstants.STALL_RPM &&
                pedalPct < 5f && lockedGear != null && notRising
        hits = if (cond) hits + 1 else 0
        return hits >= 3
    }
}

data class RevMatchTarget(val gear: Int, val targetRpm: Float)

class RevMatchTracker {
    private val speeds = ArrayDeque<Float>()
    private var active = false
    private var since = 0L

    fun reset() {
        speeds.clear()
        active = false
        since = 0L
    }

    fun update(nowMs: Long, speedMph: Float, decoupled: Boolean, lastGear: Int?): RevMatchTarget? {
        speeds.addLast(speedMph)
        if (speeds.size > 8) speeds.removeFirst()
        val slowing = speeds.size >= 4 && speedMph < speeds.first()
        if (!decoupled || lastGear == null || lastGear < 2 || speedMph < 15f) {
            active = false
            return null
        }
        if (!active && slowing) {
            active = true
            since = nowMs
        }
        if (active && nowMs - since > 5000) active = false
        if (!active) return null
        val target = speedMph * VehicleConstants.RATIOS[lastGear - 2]
        return if (target <= VehicleConstants.DOWNSHIFT_MAX_RPM) RevMatchTarget(lastGear - 1, target) else null
    }
}
