package com.curtinengineering.mxtelemetry

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.min

/**
 * Custom 2D Friction Circle / G-Meter view with peak-hold dot marker and live numeric readouts.
 */
class GMeterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var lateralG: Float = 0f
    private var longitudinalG: Float = 0f

    var peakLatG: Float = 0f
        private set
    var peakLongG: Float = 0f
        private set
    private var peakHoldTimeMs: Long = 0L

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#12161A")
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#263238")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#37474F")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val gridDashedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#263238")
        style = Paint.Style.STROKE
        strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(8f, 8f), 0f)
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

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B0BEC5")
        textSize = 22f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#78909C")
        textSize = 20f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    fun setGForce(latG: Float, longG: Float) {
        this.lateralG = latG.coerceIn(-1.5f, 1.5f)
        this.longitudinalG = longG.coerceIn(-1.5f, 1.5f)

        val currentMag = hypot(latG, longG)
        val peakMag = hypot(peakLatG, peakLongG)

        val now = System.currentTimeMillis()
        if (currentMag > peakMag || now - peakHoldTimeMs > 3000) {
            peakLatG = latG
            peakLongG = longG
            peakHoldTimeMs = now
        }

        invalidate()
    }

    fun resetPeaks() {
        peakLatG = 0f
        peakLongG = 0f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val padding = 10f
        val rect = RectF(padding, padding, w - padding, h - padding)
        canvas.drawRoundRect(rect, 14f, 14f, bgPaint)
        canvas.drawRoundRect(rect, 14f, 14f, borderPaint)

        canvas.drawText("2D G-METER", padding + 12f, padding + 24f, titlePaint)

        // Center circle geometry
        val cx = w / 2f
        val cy = (h + 16f) / 2f
        val maxRadius = min(w, h) / 2f - 40f
        if (maxRadius <= 10f) return

        // 1.0G outer circle & 0.5G inner dashed circle
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
        canvas.drawCircle(cx + peakDx, cy + peakDy, 8f, peakDotPaint)

        // Live Vector Dot & Line
        val liveDx = (lateralG / 1.2f) * maxRadius
        val liveDy = -(longitudinalG / 1.2f) * maxRadius
        val dotX = cx + liveDx
        val dotY = cy + liveDy

        // Vector line from center to dot
        canvas.drawLine(cx, cy, dotX, dotY, liveDotPaint)
        // Glow circle + solid center
        canvas.drawCircle(dotX, dotY, 14f, liveDotGlowPaint)
        canvas.drawCircle(dotX, dotY, 8f, liveDotPaint)

        // Numeric Readouts
        val latStr = String.format(Locale.US, "L:%+.2f", lateralG)
        val longStr = String.format(Locale.US, "B:%+.2f", longitudinalG)

        canvas.drawText(latStr, padding + 10f, h - padding - 12f, labelPaint)
        canvas.drawText(longStr, w - padding - 130f, h - padding - 12f, labelPaint)
    }
}
