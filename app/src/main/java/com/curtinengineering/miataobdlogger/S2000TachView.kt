package com.curtinengineering.miataobdlogger

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View

/**
 * Custom Honda S2000 AP1-style flat horizontal segmented tachometer view (68dp view height).
 * Displays linear 0-8,000 RPM segmented rev bar, 18sp bold high-contrast tick scale numbers (0..8),
 * x1000r/min unit badge, and downshift rev-match ghost marker.
 */
class S2000TachView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var currentRpm: Float = 0f
    private var targetDownshiftRpm: Float? = null
    private var isRevMatchReady: Boolean = false
    private var isOverRevRisk: Boolean = false
    private var dynamicRedlineRpm: Float = 7200f

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#090B0E") // Ultra-dark carbon
        style = Paint.Style.FILL
    }

    private val panelBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1F2633") // AP1 border
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val segPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val ghostPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val tickTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFFF") // Crisp high-contrast white
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val unitTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8B949E") // Crisp S2000 unit label
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.LEFT
    }

    private var animFlashState = false
    private var lastFlashTimeMs = 0L

    @Suppress("UNUSED_PARAMETER")
    fun setTelemetry(
        rpm: Float,
        speedKmh: Float,
        gear: String,
        targetDownshiftRpm: Float?,
        isRevMatchReady: Boolean,
        isOverRevRisk: Boolean,
        dynamicRedlineRpm: Float = 7200f
    ) {
        this.currentRpm = rpm.coerceAtLeast(0f)
        this.targetDownshiftRpm = targetDownshiftRpm
        this.isRevMatchReady = isRevMatchReady
        this.isOverRevRisk = isOverRevRisk
        this.dynamicRedlineRpm = dynamicRedlineRpm
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val density = resources.displayMetrics.density
        tickTextPaint.textSize = 18f * density // True 18sp bold
        unitTextPaint.textSize = 10f * density // True 10sp bold

        val now = System.currentTimeMillis()
        if (now - lastFlashTimeMs > 150) {
            animFlashState = !animFlashState
            lastFlashTimeMs = now
        }

        val padding = 4f
        val rect = RectF(padding, padding, w - padding, h - padding)
        canvas.drawRoundRect(rect, 8f, 8f, bgPaint)
        canvas.drawRoundRect(rect, 8f, 8f, panelBorderPaint)

        // Balanced inner padding with 16dp clearance on right edge to prevent '8' clipping
        val innerLeft = padding + 12f
        val innerRight = w - padding - 16f
        val topY = padding + 2f

        // --- 1. FULL-WIDTH FLAT SEGMENTED TACHOMETER BAR (0 to 8,000 RPM) ---
        val totalSegments = 40
        val tachWidth = innerRight - innerLeft
        val segHeight = 32f // Enlarged segment height
        val segGap = 2f
        val singleSegWidth = (tachWidth - (totalSegments - 1) * segGap) / totalSegments

        val redlineThreshold = dynamicRedlineRpm

        for (i in 0 until totalSegments) {
            val segRpmStart = (i * 8000f) / totalSegments
            val segRpmEnd = ((i + 1) * 8000f) / totalSegments

            val segLeft = innerLeft + i * (singleSegWidth + segGap)
            val segTop = topY + 4f
            val segRight = segLeft + singleSegWidth
            val segBottom = segTop + segHeight

            val isActive = currentRpm >= segRpmStart

            if (isActive) {
                when {
                    segRpmEnd > redlineThreshold && currentRpm >= redlineThreshold && animFlashState -> {
                        segPaint.color = Color.parseColor("#FF1744") // Flashing redline warning
                    }
                    segRpmStart >= redlineThreshold -> segPaint.color = Color.parseColor("#FF1744") // Redline #FF1744
                    segRpmStart >= (redlineThreshold - 1500f) -> segPaint.color = Color.parseColor("#FFAB00") // Yellow-Orange Amber
                    else -> segPaint.color = Color.parseColor("#FF9100") // S2000 Golden Amber
                }
            } else {
                if (segRpmStart >= redlineThreshold) {
                    segPaint.color = Color.parseColor("#331015") // Distinct dark red unlit redline zone
                } else if (redlineThreshold < 7000f && segRpmStart >= redlineThreshold) {
                    segPaint.color = Color.parseColor("#2A1C0A") // Cold safety zone
                } else {
                    segPaint.color = Color.parseColor("#11151A") // Matte Dark Track
                }
            }

            canvas.drawRect(segLeft, segTop, segRight, segBottom, segPaint)
        }

        // --- 2. DOWNSHIFT REV-MATCH GHOST MARKER ---
        val ghostRpm = targetDownshiftRpm
        if (ghostRpm != null && ghostRpm in 100f..8000f) {
            val ghostRatio = (ghostRpm / 8000f).coerceIn(0f, 1f)
            val ghostX = innerLeft + ghostRatio * tachWidth
            val segTop = topY + 4f
            val segBottom = segTop + segHeight

            when {
                isOverRevRisk -> {
                    if (animFlashState) {
                        ghostPaint.color = Color.parseColor("#FF1744")
                        canvas.drawRect(ghostX - 4f, segTop - 4f, ghostX + 4f, segBottom + 4f, ghostPaint)
                    }
                }
                isRevMatchReady -> {
                    ghostPaint.color = Color.parseColor("#00E676") // Neon Green
                    canvas.drawRect(ghostX - 5f, segTop - 5f, ghostX + 5f, segBottom + 5f, ghostPaint)
                }
                else -> {
                    ghostPaint.color = Color.parseColor("#FFEA00")
                    canvas.drawRect(ghostX - 3f, segTop - 3f, ghostX + 3f, segBottom + 3f, ghostPaint)
                }
            }
        }

        // --- 3. S2000 TACHOMETER SCALE TICK DIGITS (0..8) & x1000r/min BADGE ---
        val tickNumbers = listOf("0", "1", "2", "3", "4", "5", "6", "7", "8")
        val tickY = topY + segHeight + 24f

        for (i in tickNumbers.indices) {
            val ratio = i / 8.0f
            val tx = innerLeft + ratio * tachWidth
            canvas.drawText(tickNumbers[i], tx, tickY, tickTextPaint)
        }

        // Shifted "x1000r/min" unit label 40dp to the right of "0" to prevent collision
        canvas.drawText("x1000r/min", innerLeft + 40f, tickY, unitTextPaint)
    }
}
