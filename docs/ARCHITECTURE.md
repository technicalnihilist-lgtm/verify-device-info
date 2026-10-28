# Architecture

## Goals

1. Every displayed value is traceable to a source.
2. Analysis logic is testable without an Android device, and portable to iOS.
3. Adding a collector or a check never requires touching the UI.
4. The build has no annotation processors, so it stays fast and debuggable.

## Layers

```
┌─────────────────────────────────────────────────────────────┐
│  ui/  Jetpack Compose                                       │
│  Dashboard · Overview · Specs · Hardware · Authenticity     │
│  Parts · Battery · Security · Age · Diagnostics · Report    │
│  Interactive test screens                                   │
└───────────────────────────┬─────────────────────────────────┘
                            │ UiState (StateFlow)
┌───────────────────────────▼─────────────────────────────────┐
│  ui/AppViewModel                                            │
│  orchestrates scan → analysis → report → export             │
└───────────────────────────┬─────────────────────────────────┘
                            │ domain interfaces
┌───────────────────────────▼─────────────────────────────────┐
│  domain/  (pure Kotlin — no android.* imports)              │
│  DeviceInformationProvider   AuthenticityAnalysisEngine     │
│  HardwareDiagnosticsEngine   PartsVerificationProvider      │
│  SystemIntegrityAnalyzer     DeviceAgeEstimator             │
│  EvidenceAndConfidenceEngine ReportGenerator                │
│  PermissionManager           LocalDiagnosticHistory         │
│  ReferenceRepository                                        │
└───────────────────────────┬─────────────────────────────────┘
                            │ implementations
┌───────────────────────────▼─────────────────────────────────┐
│  data/android/                                              │
│  Collectors: Identity · Hardware · Battery · Camera ·       │
│              Sensor · Connectivity · Audio · Security ·     │
│              Software                                       │
│  Probes:     Sensor · Audio · Camera                        │
│  Adapters:   AssetReferenceRepository · PdfReportGenerator  │
│              FileDiagnosticHistory · AndroidPermissionManager│
└─────────────────────────────────────────────────────────────┘
                            │
┌───────────────────────────▼─────────────────────────────────┐
│  core/model  SpecField · Evidence · DeviceSnapshot ·        │
│              DeviceReport · enums                           │
└─────────────────────────────────────────────────────────────┘
```

`core` and `domain` contain no Android types, which is what makes the unit tests run on the JVM in
milliseconds and what makes an iOS port a matter of implementing the same interfaces.

## Data flow of a scan

1. `AppViewModel.scan()` calls `DeviceInformationProvider.collect { progress -> … }`.
2. `AndroidDeviceInformationProvider` runs eight stages in order on `Dispatchers.IO`, emitting
   `ScanProgress` **after** each stage completes. The UI's progress bar is driven by that callback, so
   it cannot outrun the real work.
3. Each stage appends `SpecField` rows (value + status + confidence + verification + source) and
   structured facts to the snapshot.
4. `ReferenceRepository.match()` looks the model up in the bundled asset.
5. Four engines run over the snapshot: authenticity, parts, age, integrity. A fifth (`ScoreEngine`)
   consumes their output plus the diagnostic session.
6. The assembled `DeviceReport` is stored to local history and rendered by the report screens.

Because `rebuildReport()` re-runs the parts, age, integrity and score engines whenever the diagnostic
session changes, running a test immediately updates the scores and the exported report.

## Key model types

| Type | Purpose |
|---|---|
| `SpecField` | One collected property with `group`, `status`, `confidence`, `verification`, `source`, `note`, `sensitive`. |
| `Evidence` | One auditable fact behind a finding, tagged with its `EvidenceSource`. |
| `DiagnosticStatus` | Closed set: VERIFIED, PASS, WARNING, FAILED, UNVERIFIED, NOT_SUPPORTED, PERMISSION_REQUIRED. |
| `ComponentState` | Five-state parts classification. |
| `ScoreResult` | Score + band + inputs + **coverage** + summary. `score` is null when coverage < 30%. |
| `DeviceReport` | The whole exportable artefact. |

## Why the scores cannot mislead

`DefaultScoreEngine` accumulates weighted inputs through a small `Accumulator`:

* a missing input is added to the input list for transparency, but contributes to neither the
  numerator nor `availableWeight` — it only reduces `totalWeight` coverage;
* coverage = `availableWeight / totalWeight`; below 30% the score is `null` and the band `UNKNOWN`;
* there is no single headline number, by design, so one strong finding cannot be averaged away.

## Analysis engines

**Authenticity** (`DefaultAuthenticityEngine`) evaluates ~12 checks. Each returns a `Finding` with a
status, a risk level and a confidence. `AnalysisSupport.aggregateRisk` escalates only on strong
evidence: a single low-confidence indicator can never produce a HIGH risk level. With no reference
entry the result is `UNVERIFIED` with risk `UNKNOWN` — a coverage limit, not a verdict.

**Parts** (`DefaultPartsProvider`) infers per-component state from spec mismatches, battery health,
enumerated camera count and sensor presence, plus the diagnostic session. A failed functional test is
reported as a **fault**, explicitly not as evidence of a replaced part.

**Age** (`DefaultAgeEstimator`) keeps five sources separate and states in its notes that a launch date
is not a manufacturing date.

**Integrity** (`DefaultIntegrityAnalyzer`) reports what the platform discloses and marks everything
else `NOT_SUPPORTED`, including Play Integrity (which needs credentials this build does not ship).

## Threading

All collection and I/O run on `Dispatchers.IO`; the PDF writer runs on `Dispatchers.IO` and the JSON
encoder on `Dispatchers.Default`. Sensor and camera probes marshal to the main looper, as the
platform requires. Nothing blocks the UI thread.

## Extension points

| To add… | Do this |
|---|---|
| A new device property | Append a `SpecField` in the relevant collector. It appears in the A-to-Z list and both exports automatically. |
| A new authenticity check | Add a branch in `DefaultAuthenticityEngine` producing a `Finding` with evidence. |
| A new reference model | Add an entry to `assets/device_reference.json` (schema below). |
| A new interactive test | Add a `DiagnosticTestDescriptor`, a screen, and route it in `AppNav`. |
| A new score | Add an `Accumulator` block in `DefaultScoreEngine`. |

### Reference entry schema

```json
{
  "modelPatterns": ["SM-S911", "Galaxy S23"],
  "brand": "Samsung",
  "marketingName": "Galaxy S23",
  "soc": "Snapdragon 8 Gen 2 SM8550",
  "socManufacturer": "Qualcomm",
  "releaseDate": "2023-02-17",
  "ramOptionsGb": [8],
  "storageOptionsGb": [128, 256, 512],
  "displayWidthPx": 1080, "displayHeightPx": 2340,
  "densityDpi": 420, "refreshRateHz": 120,
  "rearCameraCount": 3, "frontCameraCount": 1, "mainCameraMegapixels": 50.0,
  "batteryMah": 3900,
  "expectedSensors": ["accelerometer", "gyroscope", "magnetometer", "proximity", "light"],
  "sourceUrl": "https://…", "sourceDate": "2026-10-28", "sourceName": "Samsung official specifications"
}
```

`soc` may list several acceptable values separated by `/` (for regional variants), and may include the
part number, because Android reports either a part number (`SM8550`) or a marketing name depending on
the vendor.

## iOS readiness

The `core` and `domain` packages compile without Android. A Swift implementation of the same
interfaces (with iOS-appropriate providers using `sysctl`, `UIDevice`, `ProcessInfo`,
`LAContext`, `AVFoundation`, `CoreMotion`, and Apple's parts-and-service information where the user has
access to it) could reuse the report schema and the analysis rules verbatim. **This repository does not
contain that implementation and the Android build never presents Android data as iOS data.**
