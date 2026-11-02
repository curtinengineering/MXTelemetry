package com.curtinengineering.mxtelemetry

import android.os.SystemClock
import kotlin.math.roundToInt
import kotlin.random.Random

data class DemoFrame(
    val speedMph: Float,
    val rpm: Float,
    val pedalPct: Float,
    val longG: Float,
    val latG: Float
)

/**
 * Replay engine playing through actual recorded ND2 Miata telemetry snapshots.
 * Emits raw samples every ~65ms (real OBD cadence) through ObdParser.onSample.
 */
class DemoGenerator {

    private val demoFrames = arrayOf(
        // 0: Staging / Idle
        DemoFrame(0.0f, 850f, 0f, 0.0f, 0.0f),
        DemoFrame(0.0f, 850f, 0f, 0.0f, 0.0f),
        // 1: Launch / 1st Gear Pull
        DemoFrame(4.5f, 960f, 15f, 0.15f, 0.0f),
        DemoFrame(9.3f, 2000f, 25f, 0.35f, 0.0f),
        DemoFrame(14.3f, 3050f, 40f, 0.42f, 0.0f),
        DemoFrame(19.3f, 4150f, 65f, 0.45f, 0.05f),
        DemoFrame(24.9f, 5350f, 85f, 0.45f, 0.10f),
        DemoFrame(27.5f, 5900f, 90f, 0.40f, 0.0f),
        // 2: Quick Shift to 2nd (Clutch in, revs drop, throttle 0)
        DemoFrame(28.0f, 4200f, 0f, -0.05f, 0.0f), // ratio breaks -> CLT=100%, Gear=N
        DemoFrame(28.5f, 3560f, 45f, 0.30f, 0.15f), // 2nd gear engaged -> 3560/28.5 = 125 -> Gear 2, CLT=0%
        // 3: 2nd Gear Pull + Cornering
        DemoFrame(33.0f, 4125f, 75f, 0.38f, 0.45f),
        DemoFrame(37.9f, 4740f, 80f, 0.35f, 0.78f),
        DemoFrame(41.0f, 5125f, 85f, 0.30f, 0.82f),
        // 4: Firm Braking into Stop
        DemoFrame(39.0f, 4875f, 0f, -0.35f, 0.10f), // BRK bar rises ~33%
        DemoFrame(32.0f, 4000f, 0f, -0.65f, 0.0f),  // BRK bar rises ~73%
        DemoFrame(22.0f, 2750f, 0f, -0.75f, 0.0f),  // BRK bar rises ~86%
        DemoFrame(12.0f, 950f, 0f, -0.45f, 0.0f),   // clutch in to stop stall -> CLT=100%
        DemoFrame(2.0f, 850f, 0f, -0.15f, 0.0f),
        DemoFrame(0.0f, 850f, 0f, 0.0f, 0.0f),
        DemoFrame(0.0f, 850f, 0f, 0.0f, 0.0f)
    )

    private val rnd = Random(42)
    private var frameTimer = 0.0
    private var totalElapsed = 0.0
    private var frameIndex = 0

    fun step(dt: Double): TelemetryData {
        totalElapsed += dt
        frameTimer += dt

        val frameInterval = 0.28 // Advance to next recorded keyframe every ~280ms
        if (frameTimer >= frameInterval) {
            frameTimer %= frameInterval
            frameIndex = (frameIndex + 1) % demoFrames.size
        }

        val currFrame = demoFrames[frameIndex]
        val nextFrame = demoFrames[(frameIndex + 1) % demoFrames.size]
        val fraction = (frameTimer / frameInterval).coerceIn(0.0, 1.0)

        fun lerp(v1: Float, v2: Float) = (v1 + (v2 - v1) * fraction).toFloat()

        val speedMph = lerp(currFrame.speedMph, nextFrame.speedMph)
        val rpm = lerp(currFrame.rpm, nextFrame.rpm)
        val pedalPct = lerp(currFrame.pedalPct, nextFrame.pedalPct)
        val longG = lerp(currFrame.longG, nextFrame.longG)
        val latG = lerp(currFrame.latG, nextFrame.latG)

        val noiseRpm = (rpm + rnd.nextFloat() * 4f - 2f).coerceIn(800f, 7800f)
        val finalRpm = (noiseRpm * 4).roundToInt() / 4f
        val finalSpeedKmh = speedMph * 1.60934f

        val engineLoad = (12.0f + pedalPct * 0.72f + (finalRpm / 7800.0f) * 8.0f).coerceIn(10.0f, 98.0f)
        val maf = ((finalRpm * engineLoad / 100.0f) * 0.022f + rnd.nextFloat() * 0.2f).coerceIn(2.5f, 135.0f)

        val instantMpg = if (maf > 0.1f && speedMph > 2.0f) ((speedMph * 7.107f) / maf).coerceIn(0.0f, 99.9f) else 0f
        val fuelGph = (maf / 3.027f).coerceIn(0.1f, 50.0f)

        val nowMs = SystemClock.elapsedRealtime()

        val rawSample = TelemetryData(
            timestampMs = System.currentTimeMillis(),
            rpm = finalRpm,
            speedKmh = finalSpeedKmh,
            throttlePlatePct = (12.0f + pedalPct * 0.85f).coerceIn(12.0f, 100.0f),
            pedalPct = pedalPct,
            pedalDPct = pedalPct * 0.985f,
            pedalEPct = null,
            engineLoadPct = engineLoad,
            mafGs = maf,
            coolantTempC = 88f,
            intakeTempC = 28f,
            oilTempC = null,
            lateralG = latG,
            longitudinalG = longG,
            instantMpg = instantMpg,
            fuelRateGph = fuelGph,
            source = "demo"
        )

        return ObdParser.onSample(rawSample, nowMs)
    }
}
