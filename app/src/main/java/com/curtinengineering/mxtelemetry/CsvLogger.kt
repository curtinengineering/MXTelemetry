package com.curtinengineering.mxtelemetry

import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Thread-safe CSV writer. One file per logging session, flushed every 20 rows. */
class CsvLogger(private val dir: File) {
    private var writer: BufferedWriter? = null
    private var rowsSinceFlush = 0

    @get:Synchronized
    var currentFile: File? = null
        private set

    @get:Synchronized
    @Suppress("unused")
    val isLogging: Boolean get() = writer != null

    @Synchronized
    fun startLogging(): File {
        stopLogging()
        dir.mkdirs()
        val name = "obd_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".csv"
        val file = File(dir, name)
        val w = BufferedWriter(FileWriter(file))
        w.write("timestamp_ms,rpm,speed_kmh,speed_mph,throttle_plate_pct,pedal_pct,pedal_d_pct,pedal_e_pct,engine_load_pct,maf_gs,coolant_temp_c,intake_temp_c,oil_temp_c,calculated_gear,clutch_pedal_pct,estimated_brake_pct,target_downshift_rpm,rev_match_delta_rpm,calibrated_lat_g,calibrated_long_g,peak_lat_g,peak_rpm,instant_mpg,fuel_rate_gph,suggested_gear,rev_match_status,source,raw,ratio_rpm_per_mph,display_gear,latched_gear,ghost_active\n")
        writer = w
        currentFile = file
        rowsSinceFlush = 0
        return file
    }

    @Synchronized
    fun log(t: TelemetryData, raw: String, peakLatG: Float = 0f, peakRpm: Float = 0f, revMatchStatus: String = "") {
        val w = writer ?: return
        val safeRaw = raw.replace("\"", "\"\"").replace('\r', '/').replace('\n', '/')
        val statusToLog = if (revMatchStatus.isNotEmpty()) revMatchStatus else if (t.revMatchStatus != "N/A") t.revMatchStatus else ""
        val ratioStr = if (t.speedMph >= 3.0f) "%.2f".format(Locale.US, t.rpm / t.speedMph) else ""
        val displayGearStr = if (t.calculatedGear != "N") t.calculatedGear else ""
        val latchedGearStr = ObdParser.gearTracker.lastLockedGear?.toString() ?: ""
        val ghostActiveStr = if (t.targetDownshiftRpm != null && t.isDecoupled) "1" else ""

        w.write(
            "${t.timestampMs}," +
                "${"%.2f".format(Locale.US, t.rpm)}," +
                "${"%.0f".format(Locale.US, t.speedKmh)}," +
                "${"%.1f".format(Locale.US, t.speedMph)}," +
                "${t.throttlePlatePct?.let { "%.1f".format(Locale.US, it) } ?: ""}," +
                "${t.pedalPct?.let { "%.1f".format(Locale.US, it) } ?: ""}," +
                "${t.pedalDPct?.let { "%.1f".format(Locale.US, it) } ?: ""}," +
                "${t.pedalEPct?.let { "%.1f".format(Locale.US, it) } ?: ""}," +
                "${t.engineLoadPct?.let { "%.1f".format(Locale.US, it) } ?: ""}," +
                "${t.mafGs?.let { "%.2f".format(Locale.US, it) } ?: ""}," +
                "${t.coolantTempC?.let { "%.0f".format(Locale.US, it) } ?: ""}," +
                "${t.intakeTempC?.let { "%.0f".format(Locale.US, it) } ?: ""}," +
                "${t.oilTempC?.let { "%.0f".format(Locale.US, it) } ?: ""}," +
                "${t.calculatedGear}," +
                "${"%.0f".format(Locale.US, t.clutchPedalPct)}," +
                "${"%.0f".format(Locale.US, t.estimatedBrakePct)}," +
                "${t.targetDownshiftRpm?.let { "%.0f".format(Locale.US, it) } ?: ""}," +
                "${t.revMatchDeltaRpm?.let { "%.0f".format(Locale.US, it) } ?: ""}," +
                "${"%.2f".format(Locale.US, t.lateralG)}," +
                "${"%.2f".format(Locale.US, t.longitudinalG)}," +
                "${"%.2f".format(Locale.US, peakLatG)}," +
                "${"%.0f".format(Locale.US, peakRpm)}," +
                "${t.instantMpg.let { "%.1f".format(Locale.US, it) }}," +
                "${t.fuelRateGph.let { "%.2f".format(Locale.US, it) }}," +
                "${t.suggestedGear ?: ""}," +
                "\"$statusToLog\"," +
                "${t.source},\"$safeRaw\"," +
                "$ratioStr," +
                "$displayGearStr," +
                "$latchedGearStr," +
                "$ghostActiveStr\n",
        )
        if (++rowsSinceFlush >= 20) { w.flush(); rowsSinceFlush = 0 }
    }

    @Synchronized
    fun stopLogging() {
        writer?.let { runCatching { it.flush(); it.close() } }
        writer = null
    }
}
