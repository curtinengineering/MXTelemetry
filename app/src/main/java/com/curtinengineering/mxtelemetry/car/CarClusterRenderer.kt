package com.curtinengineering.mxtelemetry.car

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.Surface
import androidx.car.app.CarContext
import androidx.car.app.SurfaceContainer
import com.curtinengineering.mxtelemetry.RevMatchTarget
import com.curtinengineering.mxtelemetry.TelemetryData
import com.curtinengineering.mxtelemetry.TelemetryHub
import com.curtinengineering.mxtelemetry.VehicleConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * High-performance 30 FPS canvas rendering engine tailored for the ND2 Mazda Miata 800x480 screen.
 * Dynamically scales all layout rects, fonts, and borders with top offset for Android Auto system pills.
 * Serves as a real-time manual transmission coaching tool with instant clutch bite-point feedback,
 * 3-pedal footwork coordination bars (THR, DEC, DRV), and visual anti-stall warnings.
 */
object CarClusterRenderer {

    private var renderJob: Job? = null
    private var surfaceContainer: SurfaceContainer? = null
    private var activeCarContext: CarContext? = null

    private var mfdPageIndex = 0 // 0 = G-Force, 1 = Thermals & Intake

    private var peakLatG = 0f
    private var peakLongG = 0f
    private var maxSessionLatG = 0f
    private var maxSessionDecelG = 0f
    private var sessionPeakRpm = 0f
    private var peakHoldTimeMs = 0L

    // Pre-allocated Paints & Paths
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }

    private val cardBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0D1117")
        style = Paint.Style.FILL
    }

    private val cardBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#30363D")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val gearBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val segPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val speedTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val speedUnitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val gearTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val gearLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#78909C")
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val rpmTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val stallWarningBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D50000")
        style = Paint.Style.FILL
    }

    private val stallWarningTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFEA00")
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val tickTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val unitTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8B949E")
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.LEFT
    }

    private val mfdHeaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val mfdLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFAAAAAA.toInt() // Dim secondary gray for label
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.LEFT
    }

    private val mfdValuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt() // Crisp white
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.RIGHT
    }

    private val pedalLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val pedalBarBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#161B22")
        style = Paint.Style.FILL
    }

    private val pedalBarFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val gGridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8B949E")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val gGridDashedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
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
        color = Color.WHITE
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    private val peakStatusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFA000")
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val ghostStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val ghostFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val ghostPath = Path()
    private val ghostText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private var animFlashState = false
    private var lastFlashTimeMs = 0L

    fun cycleMfdPage(forward: Boolean = true) {
        mfdPageIndex = if (forward) (mfdPageIndex + 1) % 2 else (mfdPageIndex + 1) % 2
    }

    fun toggleMfdPage() {
        mfdPageIndex = (mfdPageIndex + 1) % 2
    }

    fun resetPeaks() {
        maxSessionLatG = 0f
        maxSessionDecelG = 0f
        sessionPeakRpm = 0f
        peakLatG = 0f
        peakLongG = 0f
    }

    fun startRendering(container: SurfaceContainer, scope: CoroutineScope, carContext: CarContext? = null) {
        this.surfaceContainer = container
        this.activeCarContext = carContext
        renderJob?.cancel()
        renderJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                renderFrame()
                delay(33L) // ~30 FPS
            }
        }
    }

    fun stopRendering() {
        renderJob?.cancel()
        renderJob = null
        surfaceContainer = null
        activeCarContext = null
    }

    private fun renderFrame() {
        val container = surfaceContainer ?: return
        val surface: Surface = container.surface ?: return
        if (!surface.isValid) return

        var canvas: Canvas? = null
        try {
            canvas = surface.lockCanvas(null)
            if (canvas != null) {
                drawCluster(canvas, container.width.toFloat(), container.height.toFloat())
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            if (canvas != null) {
                try {
                    surface.unlockCanvasAndPost(canvas)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun drawRevMatchGhost(c: Canvas, t: RevMatchTarget, rpm: Float, barLeft: Float, barRight: Float, barTop: Float, barBottom: Float) {
        val x = barLeft + (barRight - barLeft) * (t.targetRpm / VehicleConstants.TACH_MAX_RPM)
        val matched = abs(rpm - t.targetRpm) <= VehicleConstants.REV_MATCH_TOLERANCE_RPM
        val colorInt = if (matched) 0xFF00E676.toInt() else 0xFFFFFFFF.toInt()
        ghostStroke.color = colorInt
        ghostFill.color = colorInt
        ghostText.color = colorInt
        ghostText.textSize = (barBottom - barTop) * 0.8f

        c.drawLine(x, barTop - 4f, x, barBottom + 10f, ghostStroke)
        ghostPath.reset()
        ghostPath.moveTo(x, barBottom + 10f)
        ghostPath.lineTo(x - 9f, barBottom + 26f)
        ghostPath.lineTo(x + 9f, barBottom + 26f)
        ghostPath.close()
        c.drawPath(ghostPath, ghostFill)
        c.drawText("\u2192${t.gear}", x, barBottom + 48f, ghostText)
    }

    private fun drawCluster(canvas: Canvas, w: Float, h: Float) {
        if (w <= 0f || h <= 0f) return

        val now = System.currentTimeMillis()
        if (now - lastFlashTimeMs > 150) {
            animFlashState = !animFlashState
            lastFlashTimeMs = now
        }

        canvas.drawRect(0f, 0f, w, h, bgPaint)

        val t = TelemetryHub.telemetry.value ?: TelemetryData(
            timestampMs = now,
            rpm = 3198f,
            speedKmh = 100f,
            throttlePlatePct = 42f,
            pedalPct = 38f,
            coolantTempC = 88f,
            intakeTempC = 28f,
            oilTempC = 92f,
            calculatedGear = "3",
            lateralG = -0.24f,
            longitudinalG = 0.35f,
            source = "demo"
        )

        // Peak Hold Tracking
        val currentAbsLat = abs(t.lateralG)
        val currentDecel = if (t.longitudinalG < 0f) abs(t.longitudinalG) else 0f
        if (currentAbsLat > maxSessionLatG) maxSessionLatG = currentAbsLat
        if (currentDecel > maxSessionDecelG) maxSessionDecelG = currentDecel
        if (t.rpm > sessionPeakRpm) sessionPeakRpm = t.rpm

        val gMag = hypot(t.lateralG, t.longitudinalG)
        val peakMag = hypot(peakLatG, peakLongG)
        if (gMag > peakMag || now - peakHoldTimeMs > 3000) {
            peakLatG = t.lateralG
            peakLongG = t.longitudinalG
            peakHoldTimeMs = now
        }

        val pad = 14f // Safe 14px outer margin for ND2 800x480 screen bounds

        // --- 1. FULL-WIDTH TACHOMETER (Right edge shortened to w - 140f to clear floating MFD pill) ---
        val tachTop = pad + 24f
        val tachHeight = h * 0.15f
        val tachLeft = pad + 12f
        val tachRight = w - 140f // Stop before the ActionStrip pill envelope (140px clear margin)
        val totalTachWidth = tachRight - tachLeft
        val totalSegments = 40
        val segGap = 2f
        val singleSegWidth = (totalTachWidth - (totalSegments - 1) * segGap) / totalSegments
        val segHeight = tachHeight * 0.52f
        val redlineThreshold = t.dynamicRedlineRpm

        for (i in 0 until totalSegments) {
            val segRpmStart = (i * 8000f) / totalSegments
            val segRpmEnd = ((i + 1) * 8000f) / totalSegments

            val segL = tachLeft + i * (singleSegWidth + segGap)
            val segR = segL + singleSegWidth
            val segB = tachTop + segHeight

            val isActive = t.rpm >= segRpmStart

            if (isActive) {
                when {
                    segRpmEnd > redlineThreshold && t.rpm >= redlineThreshold && animFlashState -> segPaint.color = Color.parseColor("#FF1744")
                    segRpmStart >= redlineThreshold -> segPaint.color = Color.parseColor("#FF1744")
                    segRpmStart >= (redlineThreshold - 1500f) -> segPaint.color = Color.parseColor("#FFAB00")
                    else -> segPaint.color = Color.parseColor("#FF9100")
                }
            } else {
                if (segRpmStart >= redlineThreshold) {
                    segPaint.color = Color.parseColor("#331015")
                } else {
                    segPaint.color = Color.parseColor("#11151A")
                }
            }
            canvas.drawRect(segL, tachTop, segR, segB, segPaint)
        }

        // Rev-Match Ghost Indicator
        val ghostTargetRpm = t.targetDownshiftRpm
        val ghostTargetGear = t.revMatchTargetGear ?: (t.calculatedGear.toIntOrNull()?.let { if (it > 1) it - 1 else null })
        if (ghostTargetRpm != null && ghostTargetGear != null && ghostTargetRpm <= VehicleConstants.DOWNSHIFT_MAX_RPM) {
            drawRevMatchGhost(canvas, RevMatchTarget(ghostTargetGear, ghostTargetRpm), t.rpm, tachLeft, tachRight, tachTop, tachTop + segHeight)
        }

        // Tachometer Scale Tick Numbers
        tickTextPaint.textSize = h * 0.050f
        unitTextPaint.textSize = h * 0.030f
        val tickY = tachTop + segHeight + (h * 0.052f)
        val tickNumbers = listOf("0", "1", "2", "3", "4", "5", "6", "7", "8")
        for (i in tickNumbers.indices) {
            val ratio = i / 8.0f
            val tx = tachLeft + ratio * totalTachWidth
            canvas.drawText(tickNumbers[i], tx, tickY, tickTextPaint)
        }

        // Draw "RPM x1000" in header space above sweep bar at tachLeft to avoid collision with tick "1"
        unitTextPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("RPM x1000", tachLeft, tachTop - 6f, unitTextPaint)


        // --- 2. RE-BALANCED 3-COLUMN COCKPIT DECK (Middle 18%..88% height) ---
        val deckTop = tickY + (h * 0.02f)
        val deckBottom = h * 0.88f
        val deckHeight = deckBottom - deckTop

        val col1Width = w * 0.40f // Speed/Gear/RPM: 40% width
        val col2Width = w * 0.24f // Center Pedal Cluster: Widened to 24% width
        val col3Width = w - col1Width - col2Width - (pad * 4) // MFD Box: 36% width

        // CARD 1 (LEFT - 40% WIDTH): PRIMARY SPEED + GEAR + RPM
        val c1L = pad
        val c1R = c1L + col1Width
        val card1Rect = RectF(c1L, deckTop, c1R, deckBottom)
        canvas.drawRoundRect(card1Rect, 12f, 12f, cardBgPaint)
        canvas.drawRoundRect(card1Rect, 12f, 12f, cardBorderPaint)

        val speedMph = t.speedMph
        val speedStr = String.format(Locale.US, "%.0f", speedMph)

        speedTextPaint.textSize = deckHeight * 0.46f // Reduced to 0.46f so digits "100+" never touch borders
        speedUnitPaint.textSize = deckHeight * 0.12f
        gearTextPaint.textSize = deckHeight * 0.28f
        gearLabelPaint.textSize = deckHeight * 0.08f
        rpmTextPaint.textSize = deckHeight * 0.22f

        val cy1 = deckTop + deckHeight * 0.42f

        // Gear Badge Card docked on right edge of Card 1
        val gearBoxSize = deckHeight * 0.38f
        val gearL = c1R - gearBoxSize - 12f
        val gearT = cy1 - gearBoxSize * 0.82f
        val gearRect = RectF(gearL, gearT, gearL + gearBoxSize, gearT + gearBoxSize)

        // Speed digits dynamically centered in the area between card's left edge and gear badge's left edge
        val speedAreaLeft = c1L + 12f
        val speedAreaRight = gearL - 8f
        val speedCenterX = (speedAreaLeft + speedAreaRight) / 2f

        speedTextPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(speedStr, speedCenterX, cy1, speedTextPaint)

        // "MPH" label centered directly beneath speedCenterX (zero collision with gear badge)
        speedUnitPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("MPH", speedCenterX, cy1 + deckHeight * 0.18f, speedUnitPaint)

        val isStallRisk = t.isStallWarning

        if (isStallRisk && animFlashState) {
            segPaint.color = Color.parseColor("#D50000")
            canvas.drawRoundRect(gearRect, 10f, 10f, segPaint)
        } else if (t.rpm >= t.dynamicRedlineRpm) {
            segPaint.color = Color.parseColor("#D50000")
            canvas.drawRoundRect(gearRect, 10f, 10f, segPaint)
        } else {
            canvas.drawRoundRect(gearRect, 10f, 10f, cardBgPaint)
        }
        canvas.drawRoundRect(gearRect, 10f, 10f, gearBorderPaint)

        canvas.drawText("GEAR", gearL + gearBoxSize / 2f, gearT + gearBoxSize * 0.28f, gearLabelPaint)
        canvas.drawText(t.calculatedGear, gearL + gearBoxSize / 2f, gearT + gearBoxSize * 0.82f, gearTextPaint)

        // RPM Readout or Anti-Stall Warning Banner
        if (isStallRisk) {
            val stallRect = RectF(c1L + 8f, deckTop + deckHeight * 0.72f, c1R - 8f, deckBottom - 8f)
            if (animFlashState) {
                canvas.drawRoundRect(stallRect, 8f, 8f, stallWarningBgPaint)
            }
            stallWarningTextPaint.textSize = deckHeight * 0.13f
            canvas.drawText("STALL - CLUTCH IN!", c1L + col1Width / 2f, deckTop + deckHeight * 0.87f, stallWarningTextPaint)
        } else {
            val rpmStr = String.format(Locale.US, "%,.0f RPM", t.rpm)
            canvas.drawText(rpmStr, c1L + col1Width / 2f, deckTop + deckHeight * 0.85f, rpmTextPaint)
        }


        // CARD 2 (CENTER - 24% WIDTH): PERMANENT 3-PEDAL UNIFIED CLUSTER (THR, DEC, DRV)
        val c2L = c1R + pad
        val c2R = c2L + col2Width
        val card2Rect = RectF(c2L, deckTop, c2R, deckBottom)
        canvas.drawRoundRect(card2Rect, 12f, 12f, cardBgPaint)
        canvas.drawRoundRect(card2Rect, 12f, 12f, cardBorderPaint)

        pedalLabelPaint.textSize = deckHeight * 0.082f
        val barTop = deckTop + deckHeight * 0.22f
        val barBottom = deckBottom - deckHeight * 0.08f
        val barMaxHeight = barBottom - barTop
        val barWidth = col2Width * 0.22f
        val barSlotWidth = col2Width / 3f

        // Universal dynamic getters evaluation:
        val throttlePctVal = (t.pedalPct ?: t.throttlePlatePct ?: 0f).coerceIn(0f, 100f)
        val brakePctVal = t.dynamicBrakePct.coerceIn(0f, 100f)
        val clutchPctVal = t.dynamicClutchPct.coerceIn(0f, 100f)

        val barGauges = listOf(
            Triple("THR", String.format(Locale.US, "%.0f%%", throttlePctVal), Triple(throttlePctVal, Color.parseColor("#00E676"), pedalBarBgPaint)),
            Triple("DEC", String.format(Locale.US, "%.0f%%", brakePctVal), Triple(brakePctVal, Color.parseColor("#FF1744"), pedalBarBgPaint)),
            Triple("DRV", if (clutchPctVal > 50f) "FREE" else "LOCK", Triple(if (clutchPctVal > 50f) 100f else 0f, Color.parseColor("#00B0FF"), pedalBarBgPaint))
        )

        for (i in barGauges.indices) {
            val (label, valText, barInfo) = barGauges[i]
            val (pct, color, _) = barInfo
            val slotCenterX = c2L + (i + 0.5f) * barSlotWidth
            val bL = slotCenterX - barWidth / 2f
            val bR = slotCenterX + barWidth / 2f

            pedalLabelPaint.color = color
            pedalLabelPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(label, slotCenterX, deckTop + deckHeight * 0.10f, pedalLabelPaint)
            canvas.drawText(valText, slotCenterX, deckTop + deckHeight * 0.18f, pedalLabelPaint)

            // Empty Track
            val trackRect = RectF(bL, barTop, bR, barBottom)
            canvas.drawRoundRect(trackRect, 6f, 6f, pedalBarBgPaint)

            // Fill
            val fillHeight = (pct.coerceIn(0f, 100f) / 100f) * barMaxHeight
            if (fillHeight > 0f) {
                val fillRect = RectF(bL, barBottom - fillHeight, bR, barBottom)
                pedalBarFillPaint.color = color
                canvas.drawRoundRect(fillRect, 6f, 6f, pedalBarFillPaint)
            }
        }


        // CARD 3 (RIGHT - 36% WIDTH): MULTI-FUNCTION DISPLAY (MFD BOX)
        val c3L = c2R + pad
        val c3R = w - pad // Fitted cleanly inside screen width bounds
        val card3Rect = RectF(c3L, deckTop, c3R, deckBottom)
        canvas.drawRoundRect(card3Rect, 12f, 12f, cardBgPaint)
        canvas.drawRoundRect(card3Rect, 12f, 12f, cardBorderPaint)

        // Integrated MFD Header
        mfdHeaderPaint.textSize = deckHeight * 0.080f
        val headerTitleX = c3L + col3Width / 2f

        if (mfdPageIndex == 0) { // PAGE 0: LIVE 2D G-FORCE CIRCLE
            canvas.drawText("◀ MFD: G-FORCE (1/2) ▶", headerTitleX, deckTop + deckHeight * 0.12f, mfdHeaderPaint)

            val gCx = c3L + col3Width / 2f
            val gCy = deckTop + deckHeight * 0.54f
            val gRadius = min(col3Width, deckHeight * 0.70f) / 2f - 10f

            if (gRadius > 10f) {
                val r10G = gRadius * (1.0f / 1.2f)
                val r05G = gRadius * (0.5f / 1.2f)

                canvas.drawCircle(gCx, gCy, r10G, gGridPaint)
                canvas.drawCircle(gCx, gCy, r05G, gGridDashedPaint)
                canvas.drawLine(gCx - r10G, gCy, gCx + r10G, gCy, gGridPaint)
                canvas.drawLine(gCx, gCy - r10G, gCx, gCy + r10G, gGridPaint)

                // Peak Hold Marker
                val peakDx = (peakLatG / 1.2f) * gRadius
                val peakDy = -(peakLongG / 1.2f) * gRadius
                canvas.drawCircle(gCx + peakDx, gCy + peakDy, 6f, peakDotPaint)

                // Live Vector Dot & Line
                val liveDx = (t.lateralG / 1.2f) * gRadius
                val liveDy = -(t.longitudinalG / 1.2f) * gRadius
                val dotX = gCx + liveDx
                val dotY = gCy + liveDy

                canvas.drawLine(gCx, gCy, dotX, dotY, liveDotPaint)
                canvas.drawCircle(dotX, dotY, 10f, liveDotGlowPaint)
                canvas.drawCircle(dotX, dotY, 6f, liveDotPaint)

                gValueTextPaint.textSize = deckHeight * 0.070f
                val latStr = String.format(Locale.US, "LAT:%+.2fG", t.lateralG)
                val accStr = String.format(Locale.US, "ACC:%+.2fG", t.longitudinalG)

                gValueTextPaint.textAlign = Paint.Align.LEFT
                canvas.drawText(latStr, card3Rect.left + 14f, deckBottom - 8f, gValueTextPaint)
                gValueTextPaint.textAlign = Paint.Align.RIGHT
                canvas.drawText(accStr, card3Rect.right - 14f, deckBottom - 8f, gValueTextPaint)
            }
        } else { // PAGE 1: THERMALS & INTAKE STATS
            canvas.drawText("◀ THERMALS (2/2) ▶", headerTitleX, deckTop + deckHeight * 0.12f, mfdHeaderPaint)

            mfdLabelPaint.textSize = deckHeight * 0.082f
            mfdValuePaint.textSize = deckHeight * 0.088f

            val row1Y = deckTop + deckHeight * 0.35f
            val row2Y = row1Y + deckHeight * 0.22f
            val row3Y = row1Y + deckHeight * 0.44f

            val leftMargin = card3Rect.left + 16f
            val rightMargin = card3Rect.right - 16f

            // 1. Label paint: Left-aligned
            mfdLabelPaint.textAlign = Paint.Align.LEFT
            mfdLabelPaint.color = 0xFFAAAAAA.toInt() // Muted Gray

            // 2. Value paint: Right-aligned
            mfdValuePaint.textAlign = Paint.Align.RIGHT
            mfdValuePaint.color = 0xFFFFFFFF.toInt() // Bright White
            mfdValuePaint.typeface = Typeface.DEFAULT_BOLD

            // Row 1: Coolant
            canvas.drawText("Coolant", leftMargin, row1Y, mfdLabelPaint)
            canvas.drawText("${t.coolantTempF.roundToInt()}°F (${t.coolantTempC?.roundToInt() ?: 0}°C)", rightMargin, row1Y, mfdValuePaint)

            // Row 2: Intake
            canvas.drawText("Intake", leftMargin, row2Y, mfdLabelPaint)
            canvas.drawText("${t.intakeTempF.roundToInt()}°F (${t.intakeTempC?.roundToInt() ?: 0}°C)", rightMargin, row2Y, mfdValuePaint)

            // Row 3: MAF
            canvas.drawText("MAF Air", leftMargin, row3Y, mfdLabelPaint)
            canvas.drawText(String.format(Locale.US, "%.1f g/s", t.mafGs ?: 0f), rightMargin, row3Y, mfdValuePaint)
        }


        // --- 3. BOTTOM FOOTER SESSION PEAK STATUS BAR (Shifted up by 4dp) ---
        val peakTop = deckBottom + 2f
        val peakBottom = h - pad - 4f
        val peakRect = RectF(pad, peakTop, w - pad, peakBottom)
        canvas.drawRoundRect(peakRect, 8f, 8f, cardBgPaint)
        canvas.drawRoundRect(peakRect, 8f, 8f, cardBorderPaint)

        peakStatusPaint.textSize = (peakBottom - peakTop) * 0.58f
        val peakStr = String.format(
            Locale.US,
            "MAX LAT: %.2fG  |  MAX DECEL: %.2fG  |  PEAK RPM: %,.0f",
            maxSessionLatG,
            maxSessionDecelG,
            sessionPeakRpm
        )
        canvas.drawText(peakStr, w / 2f, peakTop + (peakBottom - peakTop) * 0.72f, peakStatusPaint)
    }
}
