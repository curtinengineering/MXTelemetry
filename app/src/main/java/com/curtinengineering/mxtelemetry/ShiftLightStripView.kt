package com.curtinengineering.mxtelemetry

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Progressive shift light LED strip with 9 circular indicators across the top of the Cockpit HUD.
 * Dynamically adjusts LED thresholds based on cold-start engine warm-up status (`dynamicRedlineRpm`).
 */
class ShiftLightStripView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var currentRpm: Float = 0f
    private var dynamicRedlineRpm: Float = 7200f

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#08090C")
        style = Paint.Style.FILL
    }

    private val offLedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#151B22")
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1D212C")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val ledPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val ledGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private var animFlashState = false
    private var lastFlashTimeMs = 0L

    fun setRpm(rpm: Float, dynamicRedlineRpm: Float = 7200f) {
        this.currentRpm = rpm.coerceAtLeast(0f)
        this.dynamicRedlineRpm = dynamicRedlineRpm
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val now = System.currentTimeMillis()
        if (now - lastFlashTimeMs > 120) {
            animFlashState = !animFlashState
            lastFlashTimeMs = now
        }

        val padding = 8f
        val rect = RectF(padding, padding, w - padding, h - padding)
        canvas.drawRoundRect(rect, 12f, 12f, bgPaint)
        canvas.drawRoundRect(rect, 12f, 12f, borderPaint)

        val numLeds = 9
        val availableWidth = w - padding * 2 - 20f
        val cy = h / 2f
        val spacing = availableWidth / numLeds
        val radius = (spacing / 2.2f).coerceAtMost(cy - 10f)

        // Dynamic thresholds based on engine warm-up
        val redline = dynamicRedlineRpm
        val startThreshold = (redline - 1700f).coerceAtLeast(2800f)
        val step = (redline - startThreshold) / 9f

        for (i in 0 until numLeds) {
            val cx = padding + 10f + (i + 0.5f) * spacing
            val t = startThreshold + i * step
            val isActive = currentRpm >= t

            if (isActive) {
                val baseColor = when (i) {
                    in 0..2 -> Color.parseColor("#00E676") // Green
                    in 3..5 -> Color.parseColor("#FF9100") // Amber
                    else -> Color.parseColor("#FF1744")    // Red
                }

                if (i >= 6 && currentRpm >= redline && !animFlashState) {
                    canvas.drawCircle(cx, cy, radius, offLedPaint)
                } else {
                    ledGlowPaint.color = (baseColor and 0x00FFFFFF) or 0x40000000
                    canvas.drawCircle(cx, cy, radius + 6f, ledGlowPaint)

                    ledPaint.color = baseColor
                    canvas.drawCircle(cx, cy, radius, ledPaint)
                }
            } else {
                canvas.drawCircle(cx, cy, radius, offLedPaint)
            }
        }
    }
}
