package com.curtinengineering.mxtelemetry

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalTime
import java.time.format.DateTimeFormatter

enum class Mode { IDLE, DEMO, BLUETOOTH }

/**
 * App-wide state the service writes and the UI reads. In Phase 2 the dynamics engine and the HUD
 * canvas view can collect [telemetry] from here without knowing whether the data is real or demo.
 */
object TelemetryHub {
    val telemetry = MutableStateFlow<TelemetryData?>(null)
    val mode = MutableStateFlow(Mode.IDLE)
    val status = MutableStateFlow("Idle")
    val supportedPids = MutableStateFlow<Set<Int>>(emptySet())
    val pollHz = MutableStateFlow(0f)
    val logging = MutableStateFlow(false)
    val logPath = MutableStateFlow<String?>(null)

    private const val MAX_RAW_LINES = 400
    private val _raw = MutableStateFlow<List<String>>(emptyList())
    val raw: StateFlow<List<String>> = _raw
    private val stamp = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    fun log(line: String) {
        val l = LocalTime.now().format(stamp) + "  " + line
        _raw.update { (it + l).takeLast(MAX_RAW_LINES) }
    }

    fun clearRaw() { _raw.value = emptyList() }
}
