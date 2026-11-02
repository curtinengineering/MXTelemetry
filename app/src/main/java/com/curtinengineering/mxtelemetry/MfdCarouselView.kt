package com.curtinengineering.mxtelemetry

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.min

/**
 * Compact GT7/S2000-style Multi-Function Display (MFD) Carousel Widget embedding:
 * - Page 0 (1/3): Live 2D Friction Circle G-Meter with crosshairs, peak hold dot, and G-value readouts.
 * - Page 1 (2/3): Thermals (Coolant ECT, Intake IAT, Engine Oil).
 * - Page 2 (3/3): Fuel & Eco / Diagnostics (Instant MPG, Fuel Flow GPH, MAF, Update Rate Hz).
 */
class MfdCarouselView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var pageIndex = 0 // 0 = G-Force, 1 = Thermals, 2 = Fuel & Diagnostics

    private var telemetryData: TelemetryData? = null
    private var pollHz: Float = 0f
    private var lateralG: Float = 0f
    private var longitudinalG: Float = 0f

    private var peakLatG: Float = 0f
    private var peakLongG: Float = 0f
    private var peakHoldTimeMs: Long = 0L

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0E1118")
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1F2633")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val headerBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#161A22")
        style = Paint.Style.FILL
    }

    private val headerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF") // GT Cyan
        textSize = 22f // ~16sp bold
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8A99AD")
        textSize = 22f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 26f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.RIGHT
    }

    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B0BEC5")
        style = Paint.Style.FILL
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // G-Meter Drawing Paints
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8B949E") // Bright white-gray crosshairs
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val gridDashedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#30363D")
        style = Paint.Style.STROKE
        strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(6f, 6f), 0f)
    }

    private val liveDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.FILL
    }

    private val liveDotGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4000E5FF")
        style = Paint.Style.FILL
    }

    private val peakDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF9100")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val gValueTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFFF") // 14sp bold white
        textSize = 20f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    fun setTelemetry(data: TelemetryData?, activeLatG: Float = 0f, activeLongG: Float = 0f, hz: Float = 0f) {
        this.telemetryData = data
        this.lateralG = activeLatG.coerceIn(-1.5f, 1.5f)
        this.longitudinalG = activeLongG.coerceIn(-1.5f, 1.5f)
        this.pollHz = hz

        val currentMag = hypot(activeLatG, activeLongG)
        val peakMag = hypot(peakLatG, peakLongG)
        val now = System.currentTimeMillis()
        if (currentMag > peakMag || now - peakHoldTimeMs > 3000) {
            peakLatG = activeLatG
            peakLongG = activeLongG
            peakHoldTimeMs = now
        }

        invalidate()
    }

    fun resetPeaks() {
        peakLatG = 0f
        peakLongG = 0f
        invalidate()
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (event?.action == MotionEvent.ACTION_DOWN) {
            performClick()
            val x = event.x
            val w = width.toFloat()
            if (x < (w * 0.25f)) {
                pageIndex = (pageIndex + 2) % 3
                invalidate()
                return true
            } else if (x > (w * 0.75f)) {
                pageIndex = (pageIndex + 1) % 3
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val padding = 6f
        val rect = RectF(padding, padding, w - padding, h - padding)
        canvas.drawRoundRect(rect, 12f, 12f, bgPaint)
        canvas.drawRoundRect(rect, 12f, 12f, borderPaint)

        // MFD Header Bar
        val headerRect = RectF(padding, padding, w - padding, padding + 36f)
        canvas.drawRoundRect(headerRect, 12f, 12f, headerBgPaint)

        val title = when (pageIndex) {
            0 -> "◀ MFD: G-FORCE (1/3) ▶"
            1 -> "◀ MFD: THERMALS (2/3) ▶"
            else -> "◀ MFD: FUEL & ECO (3/3) ▶"
        }
        canvas.drawText(title, w / 2f, padding + 26f, headerTextPaint)

        // Left / Right Touch Arrow Triangles
        val leftArrowPath = Path().apply {
            moveTo(padding + 24f, padding + 16f)
            lineTo(padding + 12f, padding + 24f)
            lineTo(padding + 24f, padding + 32f)
            close()
        }
        canvas.drawPath(leftArrowPath, arrowPaint)

        val rightArrowPath = Path().apply {
            moveTo(w - padding - 24f, padding + 16f)
            lineTo(w - padding - 12f, padding + 24f)
            lineTo(w - padding - 24f, padding + 32f)
            close()
        }
        canvas.drawPath(rightArrowPath, arrowPaint)

        val t = telemetryData

        when (pageIndex) {
            0 -> { // PAGE 0: LIVE 2D FRICTION CIRCLE G-METER
                val cx = w / 2f
                val cy = (padding + 36f + h - 24f) / 2f
                val maxRadius = min(w, h - 72f) / 2f - 12f

                if (maxRadius > 10f) {
                    val r10G = maxRadius * (1.0f / 1.2f)
                    val r05G = maxRadius * (0.5f / 1.2f)

                    canvas.drawCircle(cx, cy, r10G, gridPaint)
                    canvas.drawCircle(cx, cy, r05G, gridDashedPaint)

                    // Crosshairs
                    canvas.drawLine(cx - r10G, cy, cx + r10G, cy, gridPaint)
                    canvas.drawLine(cx, cy - r10G, cx, cy + r10G, gridPaint)

                    // Peak Hold Marker
                    val peakDx = (peakLatG / 1.2f) * maxRadius
                    val peakDy = -(peakLongG / 1.2f) * maxRadius
                    canvas.drawCircle(cx + peakDx, cy + peakDy, 7f, peakDotPaint)

                    // Live Vector Dot & Line
                    val liveDx = (lateralG / 1.2f) * maxRadius
                    val liveDy = -(longitudinalG / 1.2f) * maxRadius
                    val dotX = cx + liveDx
                    val dotY = cy + liveDy

                    canvas.drawLine(cx, cy, dotX, dotY, liveDotPaint)
                    canvas.drawCircle(dotX, dotY, 12f, liveDotGlowPaint)
                    canvas.drawCircle(dotX, dotY, 7f, liveDotPaint)

                    // G-Value Readouts at Bottom
                    val latStr = String.format(Locale.US, "LAT:%+.2fG", lateralG)
                    val longStr = String.format(Locale.US, "ACC:%+.2fG", longitudinalG)

                    gValueTextPaint.textAlign = Paint.Align.LEFT
                    canvas.drawText(latStr, padding + 12f, h - padding - 10f, gValueTextPaint)

                    gValueTextPaint.textAlign = Paint.Align.RIGHT
                    canvas.drawText(longStr, w - padding - 12f, h - padding - 10f, gValueTextPaint)
                }
            }

            1 -> { // PAGE 1: THERMALS
                val leftX = padding + 16f
                val rightX = w - padding - 16f
                var startY = padding + 76f
                val lineSpacing = 38f

                val ectStr = t?.coolantTempC?.let { String.format(Locale.US, "%.0f °C (%.0f °F)", it, t.coolantTempF) } ?: "n/a"
                val iatStr = t?.intakeTempC?.let { String.format(Locale.US, "%.0f °C (%.0f °F)", it, t.intakeTempF) } ?: "n/a"
                val oilStr = t?.oilTempC?.let { String.format(Locale.US, "%.0f °C (%.0f °F)", it, t.oilTempF) } ?: "n/a"

                canvas.drawText("Coolant (ECT)", leftX, startY, labelPaint)
                canvas.drawText(ectStr, rightX, startY, valuePaint)

                startY += lineSpacing
                canvas.drawText("Intake (IAT)", leftX, startY, labelPaint)
                canvas.drawText(iatStr, rightX, startY, valuePaint)

                startY += lineSpacing
                canvas.drawText("Engine Oil", leftX, startY, labelPaint)
                canvas.drawText(oilStr, rightX, startY, valuePaint)
            }

            else -> { // PAGE 2: FUEL & ECO / DIAGNOSTICS
                val leftX = padding + 16f
                val rightX = w - padding - 16f
                var startY = padding + 76f
                val lineSpacing = 38f

                val mpgStr = t?.instantMpg?.let { String.format(Locale.US, "%.1f MPG", it) } ?: "n/a"
                val gphStr = t?.fuelRateGph?.let { String.format(Locale.US, "%.2f GPH", it) } ?: "n/a"
                val hzStr = String.format(Locale.US, "%.1f Hz", pollHz)

                canvas.drawText("Instant MPG", leftX, startY, labelPaint)
                canvas.drawText(mpgStr, rightX, startY, valuePaint)

                startY += lineSpacing
                canvas.drawText("Fuel Rate", leftX, startY, labelPaint)
                canvas.drawText(gphStr, rightX, startY, valuePaint)

                startY += lineSpacing
                canvas.drawText("Update Rate", leftX, startY, labelPaint)
                canvas.drawText(hzStr, rightX, startY, valuePaint)
            }
        }

        // Page Indicator Dots at Bottom
        val dotRadius = 4f
        val dotSpacing = 16f
        val dotCenterX = w / 2f
        val dotY = h - padding - 8f

        for (i in 0..2) {
            val dx = dotCenterX + (i - 1) * dotSpacing
            if (i == pageIndex) {
                dotPaint.color = Color.parseColor("#00E5FF")
                canvas.drawCircle(dx, dotY, dotRadius + 1.5f, dotPaint)
            } else {
                dotPaint.color = Color.parseColor("#37474F")
                canvas.drawCircle(dx, dotY, dotRadius, dotPaint)
            }
        }
    }
}
