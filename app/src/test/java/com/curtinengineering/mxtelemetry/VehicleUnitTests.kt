package com.curtinengineering.mxtelemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader

class VehicleUnitTests {

    @Test
    fun testIsoTpMultiFrameParsing() {
        // Multi-frame response: 00A0:410C0C6E0D001:11210F41000000
        val raw1 = "00A0:410C0C6E0D001:11210F41000000"
        val parsed1 = ObdParser.parseResponse(raw1)
        assertNotNull(parsed1[ObdParser.PID_RPM])
        assertNotNull(parsed1[ObdParser.PID_SPEED])
        assertNotNull(parsed1[ObdParser.PID_THROTTLE])
        assertNotNull(parsed1[ObdParser.PID_INTAKE_TEMP])

        val rpmBytes = parsed1[ObdParser.PID_RPM]!!
        val rpm = (rpmBytes[0] * 256 + rpmBytes[1]) / 4f
        assertEquals(795.5f, rpm, 0.1f)

        assertEquals(0, parsed1[ObdParser.PID_SPEED]!![0])
        assertEquals(0x21, parsed1[ObdParser.PID_THROTTLE]!![0])
        assertEquals(0x41, parsed1[ObdParser.PID_INTAKE_TEMP]!![0])

        // Multi-frame response 2: 00B0:410C0C780D001:11211000FE0000
        val raw2 = "00B0:410C0C780D001:11211000FE0000"
        val parsed2 = ObdParser.parseResponse(raw2)
        assertNotNull(parsed2[ObdParser.PID_MAF])
        val mafBytes = parsed2[ObdParser.PID_MAF]!!
        val maf = (mafBytes[0] * 256 + mafBytes[1]) / 100f
        assertEquals(2.54f, maf, 0.01f) // 0x00FE = 254 -> 2.54 g/s
    }

    @Test
    fun testGearCalculatorMatch() {
        // (1959 RPM, 30 MPH, 20% pedal) -> 4th gear
        assertEquals(4, GearCalculator.match(1959f, 30f, 20f))

        // (755 RPM, 20 MPH, 0% pedal) -> null
        assertNull(GearCalculator.match(755f, 20f, 0f))

        // (800 RPM, 20 MPH, 0% pedal) -> null (idle-band exclusion)
        assertNull(GearCalculator.match(800f, 20f, 0f))

        // (800 RPM, 20 MPH, 20% pedal) -> 6th gear (valid locked cruise)
        assertEquals(6, GearCalculator.match(800f, 20f, 20f))

        // Stopped (0 RPM, 0 MPH, 0% pedal) -> null
        assertNull(GearCalculator.match(0f, 0f, 0f))
    }

    @Test
    fun testGearTrackerDebounce() {
        val tracker = GearTracker()
        val now = System.currentTimeMillis()

        // First sample of 4th gear (30 MPH, 1959 RPM, 20% pedal)
        val g1 = tracker.update(now, 1959f, 30f, 20f)
        assertNull(g1) // Single sample should not switch displayGear

        // Second sample of 4th gear
        val g2 = tracker.update(now + 65, 1959f, 30f, 20f)
        assertEquals(4, g2) // Two consecutive samples switch displayGear
        assertEquals(4, tracker.displayGear)
    }

    @Test
    fun testStallDetector() {
        val detector = StallDetector()

        // RPM 650 (< 680 STALL_RPM), 10 MPH, pedal 0%, falling RPM, locked gear 4th
        assertFalse(detector.update(670f, 10f, 0f, 4)) // Sample 1
        assertFalse(detector.update(665f, 10f, 0f, 4)) // Sample 2
        assertFalse(detector.update(660f, 10f, 0f, 4)) // Sample 3
        assertFalse(detector.update(655f, 10f, 0f, 4)) // Sample 4
        assertTrue(detector.update(650f, 10f, 0f, 4))  // Sample 5 -> Fires stall alert!

        // RPM 755 at 20 MPH with pedal 0% -> Should never fire
        val normalDetector = StallDetector()
        assertFalse(normalDetector.update(755f, 20f, 0f, 4))
        assertFalse(normalDetector.update(755f, 20f, 0f, 4))
        assertFalse(normalDetector.update(755f, 20f, 0f, 4))
        assertFalse(normalDetector.update(755f, 20f, 0f, 4))
        assertFalse(normalDetector.update(755f, 20f, 0f, 4))
    }

    @Test
    fun testRevMatchTracker() {
        val tracker = RevMatchTracker()
        val now = System.currentTimeMillis()

        // Setup slowing samples while locked/decoupled at 40 MPH after 4th gear
        tracker.update(now, 43f, true, 4)
        tracker.update(now + 100, 42f, true, 4)
        tracker.update(now + 200, 41f, true, 4)
        val target = tracker.update(now + 300, 40f, true, 4)

        assertNotNull(target)
        assertEquals(3, target?.gear)
        // 40 MPH * 83.4 (3rd gear ratio) = 3336 RPM
        assertEquals(3336f, target?.targetRpm ?: 0f, 1.0f)

        // At 85 MPH after 4th gear: target = 85 * 83.4 = 7089 RPM (> 6800 DOWNSHIFT_MAX_RPM)
        val highSpeedTracker = RevMatchTracker()
        highSpeedTracker.update(now, 88f, true, 4)
        highSpeedTracker.update(now + 100, 87f, true, 4)
        highSpeedTracker.update(now + 200, 86f, true, 4)
        val highTarget = highSpeedTracker.update(now + 300, 85f, true, 4)
        assertNull(highTarget)
    }

    @Test
    fun testPidScheduler() {
        val supportedWith5A = setOf(0x0C, 0x0D, 0x5A, 0x05, 0x0F, 0x10, 0x04, 0x11)
        val scheduler = PidScheduler(supportedWith5A)

        val batch1 = scheduler.nextBatch()
        // Must contain 0C, 0D, 5A and 1 slow PID (4 PIDs total)
        assertEquals(4, batch1.size)
        assertTrue(0x0C in batch1)
        assertTrue(0x0D in batch1)
        assertTrue(0x5A in batch1)
        assertFalse(0x5C in batch1)
        assertFalse(0x4A in batch1)

        val slowPidsEncountered = mutableSetOf<Int>()
        repeat(10) {
            val b = scheduler.nextBatch()
            assertEquals(4, b.size)
            slowPidsEncountered.add(b.last())
        }
        // Cycles through supported slow PIDs
        assertTrue(slowPidsEncountered.containsAll(listOf(0x05, 0x0F, 0x10, 0x04, 0x11)))
    }

    @Test
    fun testCoastDownRegression() {
        ObdParser.resetTrackers()
        var nowMs = System.currentTimeMillis()

        // 5 samples: rpm 1763, speed 27 mph, pedal 20 (locked 4th)
        repeat(5) {
            val s = TelemetryData(timestampMs = nowMs, rpm = 1763f, speedKmh = 27f * 1.609344f, pedalPct = 20f)
            ObdParser.onSample(s, nowMs)
            nowMs += 65L
        }

        assertEquals(4, ObdParser.gearTracker.lastLockedGear)

        // 6 samples with pedal 0:
        val rpms = floatArrayOf(1650f, 1500f, 1380f, 1270f, 1190f, 1120f)
        val mphs = floatArrayOf(27.0f, 26.7f, 26.3f, 26.0f, 25.7f, 25.3f)

        var lastSample = TelemetryData(nowMs, 1763f, 27f * 1.609344f)
        for (i in rpms.indices) {
            nowMs += 65L
            val s = TelemetryData(timestampMs = nowMs, rpm = rpms[i], speedKmh = mphs[i] * 1.609344f, pedalPct = 0f)
            lastSample = ObdParser.onSample(s, nowMs)
        }

        assertEquals("4th gear should remain latched as lastLockedGear", 4, ObdParser.gearTracker.lastLockedGear)
        assertEquals("displayGear should be null ('N') at the end", "N", lastSample.calculatedGear)
        assertTrue("isDecoupled should be true", lastSample.isDecoupled)
        assertNotNull("revMatchTargetGear should be set", lastSample.revMatchTargetGear)
        assertEquals(3, lastSample.revMatchTargetGear)

        val expectedTargetRpm = 25.3f * 83.4f // ~2110.02 RPM
        assertNotNull("targetDownshiftRpm should be set", lastSample.targetDownshiftRpm)
        assertEquals(expectedTargetRpm, lastSample.targetDownshiftRpm ?: 0f, 2.0f)
    }

    @Test
    fun testRealCsvLogRegression() {
        val stream = javaClass.classLoader?.getResourceAsStream("obd_20261101_184425.csv")
            ?: javaClass.getResourceAsStream("/obd_20261101_184425.csv")
        assertNotNull("CSV log resource obd_20261101_184425.csv must be present", stream)

        val reader = BufferedReader(InputStreamReader(stream!!))
        val header = reader.readLine()
        assertNotNull(header)

        ObdParser.resetTrackers()

        var totalMovingRows = 0
        var nonNullGearRows = 0
        var stallAlertCount = 0
        var ghostActivationCount = 0
        var saw6thGearLatch = false

        var line = reader.readLine()
        while (line != null) {
            val cols = line.split(",")
            if (cols.size >= 4) {
                val timestampMs = cols[0].toLongOrNull() ?: 0L
                val rpm = cols[1].toFloatOrNull() ?: 0f
                val speedKmh = cols[2].toFloatOrNull() ?: 0f
                val pedal = cols[5].toFloatOrNull() ?: 0f

                val rawSample = TelemetryData(
                    timestampMs = timestampMs,
                    rpm = rpm,
                    speedKmh = speedKmh,
                    pedalPct = pedal,
                    source = "obd"
                )

                val processed = ObdParser.onSample(rawSample, timestampMs)

                if (ObdParser.gearTracker.lastLockedGear == 6) {
                    saw6thGearLatch = true
                }

                if (speedKmh >= 12f) {
                    totalMovingRows++
                    if (processed.calculatedGear != "N") {
                        nonNullGearRows++
                    }
                }
                if (processed.isStallWarning) {
                    stallAlertCount++
                }
                if (processed.targetDownshiftRpm != null && processed.isDecoupled) {
                    ghostActivationCount++
                }
            }
            line = reader.readLine()
        }
        reader.close()

        // The log contains no sustained 6th-gear driving (max 42 mph); the latch must not jump to 6 during coast-down.
        assertFalse("The log contains no sustained 6th-gear driving (max 42 mph); the latch must not jump to 6 during coast-down.", saw6thGearLatch)

        assertTrue("Moving rows count should be > 0", totalMovingRows > 0)
        val gearMatchRatio = nonNullGearRows.toDouble() / totalMovingRows.toDouble()
        println("Replay test measured non-null gear percentage: ${(gearMatchRatio * 100).toInt()}%")
        assertTrue(
            "Gear matching ratio on real log should be >= 83% (actual: ${(gearMatchRatio * 100).toInt()}%)",
            gearMatchRatio >= 0.83
        )

        assertEquals("StallDetector must produce ZERO alerts on this real log", 0, stallAlertCount)
        println("Ghost activations during log replay: $ghostActivationCount")
    }
}
