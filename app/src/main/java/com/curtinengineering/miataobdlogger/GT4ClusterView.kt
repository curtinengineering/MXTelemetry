package com.curtinengineering.miataobdlogger

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Custom Classic Gran Turismo 4 (GT4 A-Spec) Instrument Cluster View featuring:
 * - Dual circular gauge pods: Left Speedometer (0-160 MPH), Right Tachometer (0-8,000 RPM)
 * - Glowing orange needle pointers and redline zone
 * - Central Column: Giant Engaged Gear Box, Massive Digital MPH
 * - Flanking curved vertical arc bars: Left Brake (Red), Right Throttle (Cyan/Blue)
 * - Bottom Lateral G-Meter bipolar bar
 * - Warning lamps: OIL, FUEL, and BRAKE
 */
class GT4ClusterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var currentRpm: Float = 0f
    private var speedKmh: Float = 0f
    private var gear: String = "N"
    private var throttlePct: Float = 0f
    private var brakePct: Float = 0f
    private var lateralG: Float = 0f
    private var oilTempC: Float? = null

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0A0B0E")
        style = Paint.Style.FILL
    }

    private val panelBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A1D24")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val dialBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#12161A")
        style = Paint.Style.FILL
    }

    private val dialRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#263238")
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#ECEFF1")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val redlineArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF1744")
        style = Paint.Style.STROKE
        strokeWidth = 10f
    }

    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF5252") // Glowing Orange-Red Needle
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
    }

    private val needleCapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#37474F")
        style = Paint.Style.FILL
    }

    private val textTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B0BEC5")
        textSize = 18f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val digitalSpeedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 72f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val digitalMphUnitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF") // GT Cyan
        textSize = 22f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val gearBadgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#161A22")
        style = Paint.Style.FILL
    }

    private val gearTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 58f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val arcTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A232A")
        style = Paint.Style.STROKE
        strokeWidth = 14f
        strokeCap = Paint.Cap.ROUND
    }

    private val throttleArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF") // Cyan / Sky Blue
        style = Paint.Style.STROKE
        strokeWidth = 14f
        strokeCap = Paint.Cap.ROUND
    }

    private val brakeArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF1744") // GT Red
        style = Paint.Style.STROKE
        strokeWidth = 14f
        strokeCap = Paint.Cap.ROUND
    }

    private val gBarTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A232A")
        style = Paint.Style.FILL
    }

    private val gBarFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.FILL
    }

    private val lampOffPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A232A")
        style = Paint.Style.FILL
    }

    private val lampOnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val lampTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 14f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private var animFlashState = false
    private var lastFlashTimeMs = 0L

    fun setTelemetry(
        rpm: Float,
        speedKmh: Float,
        gear: String,
        throttlePct: Float,
        brakePct: Float,
        lateralG: Float,
        oilTempC: Float?
    ) {
        this.currentRpm = rpm.coerceAtLeast(0f)
        this.speedKmh = speedKmh.coerceAtLeast(0f)
        this.gear = gear
        this.throttlePct = throttlePct.coerceIn(0f, 100f)
        this.brakePct = brakePct.coerceIn(0f, 100f)
        this.lateralG = lateralG.coerceIn(-1.5f, 1.5f)
        this.oilTempC = oilTempC
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val now = System.currentTimeMillis()
        if (now - lastFlashTimeMs > 180) {
            animFlashState = !animFlashState
            lastFlashTimeMs = now
        }

        val padding = 12f
        val rect = RectF(padding, padding, w - padding, h - padding)
        canvas.drawRoundRect(rect, 16f, 16f, bgPaint)
        canvas.drawRoundRect(rect, 16f, 16f, panelBorderPaint)

        val speedMph = speedKmh * 0.621371f
        val dialRadius = min(w * 0.28f, h * 0.38f)
        val cy = h * 0.44f

        val leftCx = w * 0.26f
        val rightCx = w * 0.74f

        // --- 1. LEFT SPEEDOMETER GAUGE POD (0 to 160 MPH) ---
        canvas.drawCircle(leftCx, cy, dialRadius, dialBgPaint)
        canvas.drawCircle(leftCx, cy, dialRadius, dialRingPaint)

        // Speedometer Ticks (220 deg sweep: 135 deg to 45 deg)
        val startAngle = 135f
        val sweepAngle = 270f
        val maxSpeedMph = 160f

        for (s in 0..16 step 2) {
            val ratio = s / 16f
            val angle = Math.toRadians((startAngle + ratio * sweepAngle).toDouble())
            val innerR = dialRadius - 20f
            val outerR = dialRadius - 6f

            val x1 = (leftCx + innerR * cos(angle)).toFloat()
            val y1 = (cy + innerR * sin(angle)).toFloat()
            val x2 = (leftCx + outerR * cos(angle)).toFloat()
            val y2 = (cy + outerR * sin(angle)).toFloat()

            canvas.drawLine(x1, y1, x2, y2, tickPaint)

            val textR = dialRadius - 38f
            val tx = (leftCx + textR * cos(angle)).toFloat()
            val ty = (cy + textR * sin(angle)).toFloat() + 6f
            canvas.drawText("${s * 10}", tx, ty, textTickPaint)
        }

        // Speedometer Needle
        val speedRatio = (speedMph / maxSpeedMph).coerceIn(0f, 1f)
        val speedAngle = Math.toRadians((startAngle + speedRatio * sweepAngle).toDouble())
        val needleLenS = dialRadius - 16f
        val nxS = (leftCx + needleLenS * cos(speedAngle)).toFloat()
        val nyS = (cy + needleLenS * sin(speedAngle)).toFloat()

        canvas.drawLine(leftCx, cy, nxS, nyS, needlePaint)
        canvas.drawCircle(leftCx, cy, 14f, needleCapPaint)
        canvas.drawText("MPH", leftCx, cy + dialRadius * 0.55f, digitalMphUnitPaint)

        // --- 2. RIGHT TACHOMETER GAUGE POD (0 to 8,000 RPM) ---
        canvas.drawCircle(rightCx, cy, dialRadius, dialBgPaint)
        canvas.drawCircle(rightCx, cy, dialRadius, dialRingPaint)

        // Tachometer Redline Arc (7.2k to 8k RPM -> 90% to 100% sweep)
        val redlineStartAngle = startAngle + sweepAngle * 0.90f
        val redlineSweep = sweepAngle * 0.10f
        val redlineRect = RectF(rightCx - dialRadius + 8f, cy - dialRadius + 8f, rightCx + dialRadius - 8f, cy + dialRadius - 8f)
        canvas.drawArc(redlineRect, redlineStartAngle, redlineSweep, false, redlineArcPaint)

        for (r in 0..8) {
            val ratio = r / 8f
            val angle = Math.toRadians((startAngle + ratio * sweepAngle).toDouble())
            val innerR = dialRadius - 20f
            val outerR = dialRadius - 6f

            val x1 = (rightCx + innerR * cos(angle)).toFloat()
            val y1 = (cy + innerR * sin(angle)).toFloat()
            val x2 = (rightCx + outerR * cos(angle)).toFloat()
            val y2 = (cy + outerR * sin(angle)).toFloat()

            canvas.drawLine(x1, y1, x2, y2, tickPaint)

            val textR = dialRadius - 38f
            val tx = (rightCx + textR * cos(angle)).toFloat()
            val ty = (cy + textR * sin(angle)).toFloat() + 6f
            canvas.drawText("$r", tx, ty, textTickPaint)
        }

        // Tachometer Needle
        val rpmRatio = (currentRpm / 8000f).coerceIn(0f, 1f)
        val rpmAngle = Math.toRadians((startAngle + rpmRatio * sweepAngle).toDouble())
        val needleLenT = dialRadius - 16f
        val nxT = (rightCx + needleLenT * cos(rpmAngle)).toFloat()
        val nyT = (cy + needleLenT * sin(rpmAngle)).toFloat()

        canvas.drawLine(rightCx, cy, nxT, nyT, needlePaint)
        canvas.drawCircle(rightCx, cy, 14f, needleCapPaint)
        canvas.drawText("x1000r/min", rightCx, cy + dialRadius * 0.55f, digitalMphUnitPaint)

        // Tach Shift Light Indicator at Top of Dial
        val shiftLightY = cy - dialRadius + 22f
        if (currentRpm >= 7200f && animFlashState) {
            lampOnPaint.color = Color.parseColor("#FF1744") // Flashing Crimson
            canvas.drawCircle(rightCx, shiftLightY, 12f, lampOnPaint)
        } else if (currentRpm >= 6500f) {
            lampOnPaint.color = Color.parseColor("#FF9100") // Amber
            canvas.drawCircle(rightCx, shiftLightY, 12f, lampOnPaint)
        } else {
            canvas.drawCircle(rightCx, shiftLightY, 10f, lampOffPaint)
        }

        // --- 3. CENTRAL COLUMN (ENGAGED GEAR, MASSIVE SPEED) ---
        val centerCx = w * 0.50f
        val gearTop = cy - 80f
        val gearBoxRect = RectF(centerCx - 50f, gearTop, centerCx + 50f, gearTop + 90f)

        // Redline Strobe for Gear Box
        if (currentRpm >= 6800f && animFlashState) {
            gearBadgeBgPaint.color = Color.parseColor("#D50000")
        } else {
            gearBadgeBgPaint.color = Color.parseColor("#161A22")
        }

        canvas.drawRoundRect(gearBoxRect, 14f, 14f, gearBadgeBgPaint)
        canvas.drawText(gear, centerCx, gearTop + 68f, gearTextPaint)

        // Massive Digital Speed Readout Below Gear Box
        val speedY = gearTop + 160f
        canvas.drawText(String.format(Locale.US, "%.0f", speedMph), centerCx, speedY, digitalSpeedPaint)
        canvas.drawText("MPH", centerCx, speedY + 28f, digitalMphUnitPaint)

        // --- 4. FLANKING VERTICAL CURVED ARC BARS (BRAKE & THROTTLE) ---
        val arcRadius = dialRadius + 18f
        val arcRectLeft = RectF(leftCx - arcRadius, cy - arcRadius, leftCx + arcRadius, cy + arcRadius)
        val arcRectRight = RectF(rightCx - arcRadius, cy - arcRadius, rightCx + arcRadius, cy + arcRadius)

        // Left Arc: Brake (Red, 140 deg to 220 deg)
        canvas.drawArc(arcRectLeft, 140f, 80f, false, arcTrackPaint)
        val brakeSweep = (brakePct / 100f) * 80f
        if (brakeSweep > 1f) {
            canvas.drawArc(arcRectLeft, 140f, brakeSweep, false, brakeArcPaint)
        }

        // Right Arc: Throttle (Cyan, 40 deg to -40 deg / 320 deg to 40 deg)
        canvas.drawArc(arcRectRight, -40f, 80f, false, arcTrackPaint)
        val throttleSweep = (throttlePct / 100f) * 80f
        if (throttleSweep > 1f) {
            canvas.drawArc(arcRectRight, 40f, -throttleSweep, false, throttleArcPaint)
        }

        // --- 5. BOTTOM LATERAL G-METER BAR ---
        val gBarWidth = w * 0.46f
        val gBarHeight = 16f
        val gBarLeft = centerCx - gBarWidth / 2f
        val gBarTop = h - padding - 48f
        val gBarRect = RectF(gBarLeft, gBarTop, gBarLeft + gBarWidth, gBarTop + gBarHeight)

        canvas.drawRoundRect(gBarRect, 8f, 8f, gBarTrackPaint)
        // Center tick line
        canvas.drawLine(centerCx, gBarTop - 4f, centerCx, gBarTop + gBarHeight + 4f, tickPaint)

        val gRatio = (lateralG / 1.2f).coerceIn(-1.0f, 1.0f)
        val fillWidth = (gRatio * (gBarWidth / 2f))
        val fillRect = if (fillWidth >= 0f) {
            RectF(centerCx, gBarTop, centerCx + fillWidth, gBarTop + gBarHeight)
        } else {
            RectF(centerCx + fillWidth, gBarTop, centerCx, gBarTop + gBarHeight)
        }
        canvas.drawRect(fillRect, gBarFillPaint)

        // --- 6. GT4 WARNING LAMPS (OIL, FUEL, BRAKE) ---
        val lampY = h - padding - 18f
        val lampR = 14f

        // Lamp 1: OIL
        val oilCx = centerCx - 90f
        val isOilWarning = (oilTempC ?: 0f) > 115f
        if (isOilWarning) {
            lampOnPaint.color = Color.parseColor("#FF1744")
            canvas.drawCircle(oilCx, lampY, lampR, lampOnPaint)
        } else {
            canvas.drawCircle(oilCx, lampY, lampR, lampOffPaint)
        }
        canvas.drawText("OIL", oilCx, lampY + 5f, lampTextPaint)

        // Lamp 2: FUEL
        val fuelCx = centerCx
        canvas.drawCircle(fuelCx, lampY, lampR, lampOffPaint)
        canvas.drawText("FUEL", fuelCx, lampY + 5f, lampTextPaint)

        // Lamp 3: BRAKE
        val brakeLampCx = centerCx + 90f
        if (brakePct > 10f) {
            lampOnPaint.color = Color.parseColor("#FF1744")
            canvas.drawCircle(brakeLampCx, lampY, lampR, lampOnPaint)
        } else {
            canvas.drawCircle(brakeLampCx, lampY, lampR, lampOffPaint)
        }
        canvas.drawText("BRK", brakeLampCx, lampY + 5f, lampTextPaint)
    }
}
