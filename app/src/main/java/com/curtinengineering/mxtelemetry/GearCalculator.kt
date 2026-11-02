package com.curtinengineering.mxtelemetry

import kotlin.math.abs
import kotlin.math.ln

object VehicleConstants {
    // rpm per mph with the drivetrain locked.
    // 2nd-4th measured from the 2026-11-01 car log (within ~2%).
    // 1st/5th/6th = spec math x ~1.04 (the offset seen in 2nd-4th); PROVISIONAL until measured.
    val RATIOS = floatArrayOf(210.0f, 124.7f, 83.2f, 65.3f, 52.9f, 41.2f)

    const val IDLE_RPM = 755f
    const val STALL_RPM = IDLE_RPM - 75f      // 680
    const val TACH_MAX_RPM = 8000f
    const val DOWNSHIFT_MAX_RPM = 6800f       // hide ghost if target would be above this
    const val REV_MATCH_TOLERANCE_RPM = 150f
}

object GearCalculator {
    private const val MIN_SPEED_MPH = 3.0f
    private const val BASE_TOL = 0.06f
    private const val MAX_TOL = 0.10f
    private const val KMH_PER_MPH = 1.609344f

    /** 1..6, or null when no gear fits (clutch in / neutral / slipping / shifting / stopped). */
    fun match(rpm: Float, speedMph: Float, pedalPct: Float): Int? {
        if (speedMph < MIN_SPEED_MPH) return null
        // Near idle with the pedal up is never a trustworthy locked gear (clutch-in coasting).
        // The band starts at STALL_RPM so real lugging below 680 can still count as locked.
        if (pedalPct < 5f && rpm in VehicleConstants.STALL_RPM..(VehicleConstants.IDLE_RPM + 75f)) return null
        val ratio = rpm / speedMph
        val tol = (0.6f / (speedMph * KMH_PER_MPH)).coerceIn(BASE_TOL, MAX_TOL)
        var best = -1
        var bestErr = Float.MAX_VALUE
        for (i in VehicleConstants.RATIOS.indices) {
            val err = abs(ln(ratio / VehicleConstants.RATIOS[i]))
            if (err < bestErr) {
                bestErr = err
                best = i
            }
        }
        return if (bestErr <= ln(1f + tol)) best + 1 else null
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
