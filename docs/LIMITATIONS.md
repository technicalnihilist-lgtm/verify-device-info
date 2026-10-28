# Known limitations

This document is intentionally blunt. It is the difference between a tool that helps and one that
misleads.

## What software fundamentally cannot establish

| Claim | Why it cannot be made |
|---|---|
| "This phone is 100% original" | Originality lives in physical part identity. Android exposes no public part-serial or part-authentication API to apps. |
| "This screen/battery/motherboard was replaced" | A replaced part that reports the original's specifications is indistinguishable from the original. Only an official service tool can confirm. |
| "This is a counterfeit device" | Software can show *inconsistencies*. Counterfeiting is a physical determination made by a manufacturer or a laboratory. |
| "This device was manufactured on <date>" | Android does not expose a manufacturing date. Serial-number decoding is not officially documented for current formats and is not attempted. |
| "The repair history is …" | Service records are held by manufacturers and their partners. No public API exists. |
| "This battery has N cycles" | Cycle count is only exposed from Android 14 (API 34), and only if the vendor's battery service reports it. |

## Per-API availability

| Data | Availability | What the app does otherwise |
|---|---|---|
| SoC model/manufacturer | Android 12+ (`Build.SOC_MODEL`) | `UNVERIFIED`, chipset comparison skipped |
| Battery cycle count | Android 14+, vendor-dependent | `NOT_SUPPORTED` with the reason |
| Design battery capacity | Hidden `PowerProfile` via reflection | `LOW` confidence, or unavailable |
| Hardware serial | Android 10+ restricted to privileged apps | `NOT_SUPPORTED`; never guessed |
| Thermal status | Android 10+ | `NOT_SUPPORTED` |
| Bootloader lock / Verified Boot state | Property-dependent (`ro.boot.*`) | `UNVERIFIED` |
| Bluetooth adapter state | Android 12+ needs `BLUETOOTH_CONNECT` | `PERMISSION_REQUIRED` until granted |
| Data network type | Privileged permission | Not requested; reported as unavailable |
| Installed app list | Android 11+ package visibility filtering | Count is a **lower bound**, and says so |
| System update history | No public API | `NOT_SUPPORTED` |
| Play Integrity verdict | Requires a Google Cloud project + server verification | `NOT_SUPPORTED`; nothing is simulated |

## Reference data coverage

The bundled set contains 16 popular models, curated from manufacturer specification pages (each entry
records its source URL and curation date). **Everything outside that set is `UNVERIFIED`, not
suspicious.** Regional variants, carrier models and newer devices are frequently absent, and a missing
entry will suppress chipset, display, memory and camera comparisons.

Reference data is offline by design. There is no automatic online refresh, because that would require
sending device identifiers to a server.

## Detection limits in practice

* **Root hiding.** A determined root (Magisk with DenyList, KernelSU, a custom kernel) can hide from
  every user-space check this app performs. A clean result means "no common indicators were found".
* **Spoofing.** Build properties can be edited to match a genuine device. This app detects *internal
  inconsistency*, which a careful spoofer can avoid.
* **Dead pixels.** Visual inspection cannot guarantee that every defective sub-pixel is found.
* **Touch coverage.** The grid records which cells were touched; it cannot detect a cell that reports
  touch but with wrong coordinates.
* **Cameras.** The app enumerates what Camera2 exposes. Vendors sometimes hide auxiliary cameras from
  the public camera list, so a lower count is an indicator, not proof.
* **Sensors.** Vendor strings in the sensor list come from firmware and can be spoofed.
* **Battery health.** Estimated capacity compares the charge counter at full charge with the platform's
  design capacity. It is an estimate, and it is only meaningful near 100% charge.
* **Scores are point-in-time.** Free memory, thermal state and battery temperature change constantly.

## Deliberate design choices

* **No single overall score.** Collapsing seven dimensions into one number would hide exactly the
  issues a buyer needs to see.
* **No cloud, no telemetry, no model downloads.** Everything works offline; the app makes no network
  requests at all.
* **Mock data is debug-only.** The fixture is compiled into release builds but unreachable, because it
  is gated behind `BuildConfig.DEBUG`. It can never appear in a real scan.
* **No security bypass.** The app never exploits a vulnerability, escalates privilege, or reads data it
  has no permission for. Where the platform withholds something, the report says so.

## iOS

Not implemented in this repository. An iOS build would need its own providers (and would face its own
limits: Apple restricts battery-health, part-history and hardware-identifier access to varying degrees
across versions). This Android build never displays Android facts as iOS facts.
