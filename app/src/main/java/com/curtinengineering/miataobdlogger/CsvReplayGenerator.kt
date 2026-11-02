package com.curtinengineering.miataobdlogger

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Replays actual recorded CSV log obd_20261101_184425.csv at its recorded timing.
 * When reaching the end, calls ObdParser.resetTrackers() and offsets timestamps cleanly.
 */
class CsvReplayGenerator(context: Context) {

    private val samples = mutableListOf<TelemetryData>()
    private var currentIndex = 0
    private var timeOffsetMs = 0L

    init {
        try {
            val stream = context.assets.open("obd_20261101_184425.csv")
            val reader = BufferedReader(InputStreamReader(stream))
            reader.readLine() // skip header
            var line = reader.readLine()
            while (line != null) {
                val cols = line.split(",")
                if (cols.size >= 4) {
                    val ts = cols[0].toLongOrNull() ?: 0L
                    val rpm = cols[1].toFloatOrNull() ?: 0f
                    val speedKmh = cols[2].toFloatOrNull() ?: 0f
                    val pedal = cols[5].toFloatOrNull() ?: 0f
                    val plate = cols[4].toFloatOrNull() ?: 0f
                    val ect = cols[10].toFloatOrNull()
                    val iat = cols[11].toFloatOrNull()
                    val maf = cols[9].toFloatOrNull()
                    val load = cols[8].toFloatOrNull()
                    val latG = cols[18].toFloatOrNull() ?: 0f
                    val longG = cols[19].toFloatOrNull() ?: 0f

                    samples.add(
                        TelemetryData(
                            timestampMs = ts,
                            rpm = rpm,
                            speedKmh = speedKmh,
                            throttlePlatePct = plate,
                            pedalPct = pedal,
                            engineLoadPct = load,
                            mafGs = maf,
                            coolantTempC = ect,
                            intakeTempC = iat,
                            lateralG = latG,
                            longitudinalG = longG,
                            source = "replay"
                        )
                    )
                }
                line = reader.readLine()
            }
            reader.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun step(nowMs: Long): TelemetryData? {
        if (samples.isEmpty()) return null
        if (currentIndex >= samples.size) {
            currentIndex = 0
            val lastTs = samples.last().timestampMs
            val firstTs = samples.first().timestampMs
            timeOffsetMs += (lastTs - firstTs + 1000L)
            ObdParser.resetTrackers()
        }

        val baseSample = samples[currentIndex++]
        val adjustedTs = baseSample.timestampMs + timeOffsetMs
        val sampleToIngest = baseSample.copy(timestampMs = adjustedTs)
        return ObdParser.onSample(sampleToIngest, adjustedTs)
    }
}
