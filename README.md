# MXTelemetry

**Custom Android Auto Performance Cluster & High-Frequency OBD-II Kinematic Telemetry Engine**  
*Calibrated and validated on the ND2 Mazda MX-5 platform (SkyActiv-G 2.0L 6MT).*

![MXTelemetry Android Auto Cluster](app/src/main/res/drawable/cluster_preview.png)
*(Drop your clean DHU screenshot here!)*

---

## Technical Overview

Modern OEM instrumentation often abstracts or lags critical powertrain metrics. Standard OBD-II mobile applications (e.g., Torque Pro) suffer from display latency, inconsistent polling jitter, and mobile-screen distraction unsuited for performance driving. 

**MXTelemetry** bridges raw vehicle bus diagnostics directly to the vehicle's infotainment screen via an independent **Android Auto `NavigationTemplate` / `SurfaceRenderer`** pipeline. It features an S2000-inspired horizontal sweep tachometer, tiered PID scheduling, real-time kinematic transmission gear tracking, and anti-stall/rev-match telemetry.

---

## Core Engineering Features

### 1. Kinematic Gear Determination (`GearTracker.kt`)
Because manual transmission vehicles lack direct CAN gear position sensors, gear states are solved dynamically via driveline kinematics:

$$R_{\text{effective}} = \frac{N_{\text{engine}}\ [\text{RPM}]}{V_{\text{vehicle}}\ [\text{MPH}]}$$

* **Hysteresis Latching:** Implements sample-window latching to eliminate false transient shifts during driveline shock and clutch transition phases.
* **Idle Exclusion Band:** Suppresses false neutral/first-gear determinations when $N_{\text{engine}} < 755\ \text{RPM}$ (idle) or ground speed $V_{\text{vehicle}} \approx 0\ \text{MPH}$.
* **Lock State Evaluation:** Distinguishes between locked driveline engagement (`DRV LOCK`) and free-wheeling/neutral coast-down (`FREE (est.)`).

### 2. Predictive Downshift Target Display (`RevMatchTracker.kt`)
* Tracks deceleration events and calculates the target engine speed required to rev-match into the next lower ratio:
  $$N_{\text{target}} = V_{\text{vehicle}} \times R_{\text{lower\_gear}}$$
* Drives a transient ghost needle/marker on the S2000-style LED bar, providing the driver with visual heel-and-toe RPM synchronization targets before clutch engagement.

### 3. Tiered Diagnostic Polling Pipeline (`ObdParser.kt`)
Standard ELM327 / STN OBD adapters bottleneck if polling every diagnostic PID uniformly. MXTelemetry uses a prioritized sample loop to maximize responsiveness:
* **High-Rate Stream (~20–30 Hz):** Engine RPM (`0x0C`) and Vehicle Speed (`0x0D`) for low-latency tachometer and gear calculation.
* **Mid-Rate Stream (~5–10 Hz):** Throttle Position (`0x11`) and Deceleration/Brake load.
* **Low-Rate Background Stream (1 Hz):** Thermals and mass airflow (Engine Coolant Temp `0x05`, Intake Air Temp `0x0F`, MAF Rate `0x10`).

### 4. Custom Low-Latency Canvas Renderer (`CarClusterRenderer.kt`)
* Implements direct hardware-accelerated drawing on Android Auto's `SurfaceCallback` canvas.
* S2000-style segmented sweep tachometer scaled $0\text{--}8000\ \text{RPM}$ with amber LED progression and progressive redline markers ($7000\text{--}7500\ \text{RPM}$).
* Multi-Function Display (MFD) supporting live 2-axis G-Force load vectors (lateral/longitudinal) and dual-column imperial/metric powertrain thermals.

---

## Driveline Calibration Table (ND2 6MT)

| Gear | Internal Ratio | Overall Driveline Ratio ($R_{\text{total}} = R_g \times R_{\text{final}}$) | Calibrated Kinematic Band (RPM/MPH) |
| :---: | :---: | :---: | :---: |
| **1st** | 5.087 : 1 | 14.244 : 1 | ~200 – 215 |
| **2nd** | 2.991 : 1 | 8.375 : 1 | ~118 – 128 |
| **3rd** | 2.035 : 1 | 5.698 : 1 | ~80 – 88 |
| **4th** | 1.594 : 1 | 4.463 : 1 | ~62 – 68 |
| **5th** | 1.286 : 1 | 3.601 : 1 | ~50 – 55 |
| **6th** | 1.000 : 1 | 2.800 : 1 | ~39 – 43 |
*Final Drive Ratio: 2.800 : 1 (OEM Limited-Slip Differential).*

---

## Unit Testing & Validation

All state machines and kinematic solvers are accompanied by comprehensive regression suites running across recorded track/road telemetry logs (`obd_*.csv`):

* `testKinematicGearDetection_lockedUpshifts()`: Verifies zero-lag sequential detection across redline shifts.
* `testDownshiftRevMatch_ghostNeedleTrigger()`: Verifies mathematical convergence of target rev blips.
* `testAntiStallThreshold_suppression()`: Confirms low-RPM coast-down does not trigger false stall flags above $680\ \text{RPM}$.
* `testTachSweepBoundaryBounds()`: Confirms LED segment arrays clamp strictly within display boundaries without canvas clipping.

---

## Tech Stack & Architecture

* **Language:** Kotlin 100%
* **Platform:** Android Automotive / Android Auto App Library (`androidx.car.app:app:1.4.0`)
* **Concurrency:** Kotlin Coroutines & `StateFlow` reactive stream pipelines
* **Diagnostics Interface:** Bluetooth SPP / OBD-II ELM327 protocol handler with multi-frame CAN framing support

---

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
