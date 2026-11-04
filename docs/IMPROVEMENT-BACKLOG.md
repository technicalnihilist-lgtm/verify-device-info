# Improvement backlog — 1 to 20 November

Standing brief for the daily optimisation runs.

**Hard rules**

1. **The UI must not change.** No new screens, no new controls, no restyling, no copy changes that
   alter what the user reads on screen. Rendering output must stay identical.
2. **No new user-facing features.** This period is about making what exists faster, smaller, more
   correct and harder to crash.
3. Every change must be verified: `gradle assembleDebug` succeeds and `testDebugUnitTest` passes
   (unit tests only; no device is available).
4. No fake work. If an item turns out to be already done or not worth doing, mark it and move on.
   A run that changes nothing is a valid outcome and must say so.
5. Commits go through the GitHub MCP Git Data API (`GITHUB_CREATE_A_BLOB` → `GITHUB_CREATE_A_TREE`
   with `base_tree` → `GITHUB_CREATE_A_COMMIT` → `GITHUB_UPDATE_A_REFERENCE`), because the git
   credential on this repository is read-only.

**How a run picks work:** take the first unchecked item. One item per run, done properly, with the
build and tests green before committing.

---

## A. Performance — allocation and formatting hot paths

- [x] **A1. Precompile the regex in `AnalysisSupport.normalise`.** `Regex("[^a-z0-9]")` is compiled on
  every call and this function runs in tight loops over model, brand and SoC strings. Hoist it to a
  `private val`. *Verify:* unit tests unchanged; no behaviour difference.
- [x] **A2. Replace `String.format` in `Formatters`.** `Formatters.bytes` and friends allocate a
  `Formatter` per call and are used in list rendering. Implement the decimal formatting by hand.
  *Verify:* add unit tests pinning the exact output strings before changing anything.
- [x] **A3. Cache the normalised reference patterns.** `AssetReferenceRepository.match` normalises every
  pattern on every scan. Normalise once at load time. *Done:* brand and pattern keys are normalised
  once when the asset is parsed; `match` now normalises only the three query strings and walks the
  pre-built index, which also removes the per-call `Triple` list and sort. Same winner on every
  query; 124–324 µs → 2.5–3.4 µs per `match` in a standalone JVM benchmark over the bundled set.
- [x] **A4. Avoid rebuilding the identity blob per scan.** `DefaultAuthenticityEngine` lowercases and
  joins six strings on each run; build it once per call and reuse for all marker checks. *Done:*
  already the case — `identityBlob` is built once per `analyze` call and shared by the marker scan
  and the evidence it emits; nothing to change.
- [x] **A5. Reuse `Paint` objects in the PDF writer.** `PageWriter` allocates paints per instance but
  re-creates some per page; hoist to fields and verify the PDF still renders identically. *Done:*
  already the case — all six paints (`title`, `section`, `body`, `small`, `label`, `rule`) are
  instance fields and nothing is allocated per page; nothing to change. (Note for a later pass:
  `finishPage` mutates `smallPaint.textSize` for the footer and never restores it, so small text on
  page 2+ renders at 7.5pt instead of 8pt. Fixing it would change the rendered PDF, so it needs a
  deliberate decision, not a drive-by edit.)

## B. Correctness — real bugs and edge cases

- [x] **B1. Prune old report files.** `FileDiagnosticHistory` caps the *index* at 50 entries but never
  deletes the JSON files in `history/reports/`, so storage grows forever. Delete reports that fall out
  of the index. *Done:* `save` now writes the trimmed index and then deletes the report files whose id
  is no longer in it (`staleReportFiles`, pure and unit-tested). Only names matching the
  `<uuid>.json` scheme the class writes are ever removed, deletion failures are swallowed so a save
  still succeeds, and `clear()` is unchanged. Six tests in `HistoryPruneTest` cover kept, dropped,
  empty-index, non-report files and uppercase ids.
- [x] **B2. Write the history index atomically.** A crash mid-write leaves a truncated JSON file that
  silently drops all history. Write to a temp file and `rename`. *Done:* `writeIndex` now goes through
  a new `atomicWriteText` helper: the bytes are written to a sibling `index.json.tmp`, flushed and
  `fd.sync()`ed, and only then renamed over the index, so a reader sees either the complete previous
  file or the complete new one. A failed write leaves the existing index untouched and removes only
  its own partial temp file. Seven tests in `HistoryIndexWriteTest` cover first write, replacing a
  longer value, UTF-8 round-trip, no leftover temp file, parent-directory creation, previous contents
  surviving a failed write, and recovery once the obstruction is gone.
- [x] **B3. Guard against an empty report list on load.** *Not done:* the one genuinely wrong state is
  the perpetual "Loading stored report…" shown when a report file is missing, and fixing it needs a
  new message — user-visible copy, which the hard rule forbids. The corrupt-file path already
  degrades without an exception (`loadRaw` returns the text or null; the screen only renders it).
  Belongs to a design pass.
- [x] **B4. Fix the `sincePatch` off-by-one.** *Nothing to fix:* there is no `sincePatch`; `parsePatchDays`
  measures from the first of the patch month, which is the oldest possible date for a `YYYY-MM` value
  and therefore the conservative reading, and its 365/730 boundaries agree with `DefaultScoreEngine`
  (`<= 365 → 80`, `<= 730 → 50`).
- [x] **B5. Handle a zero-size sensor list.** *Done:* the "Sensors enumerated" input in the Hardware
  health score now yields `null` instead of `0` when `hardware.sensors` is empty, so a device that
  reports no sensors loses coverage instead of being scored as failed hardware — the rule the engine
  already states, and what the "Sensor count" input in Display and sensors already did. On the test
  fixture the sensorless device moves from 67/100 at 100% coverage to 89/100 at 75% coverage. The
  rendered row is unchanged (`"0 sensors"`, same label); `ScoreInput.note` is not displayed anywhere
  in the UI. New test in `ScoreEngineTest` pins both the old and new numbers.
- [x] **B6. `HardwareCollector.readDisplay` fallback.** If `getRealMetrics` throws (multi-display
  devices), fall back to `DisplayMetrics` from resources instead of returning null. *Done:*
  `readDisplay` now tries `getRealMetrics`, and on a throw (or when no default display exists) copies
  `resources.displayMetrics` instead of abandoning the section, so the six display rows stay populated
  with window-sized values rather than collapsing to the single "could not be read" row. The arithmetic
  moved into `DisplayFactsFactory`, a framework-free object so the diagonal, the refresh-rate guard and
  the mode/hdr de-duplication are unit-tested without a device (7 tests in `DisplayFactsFactoryTest`),
  plus `HardwareCollectorDisplayTest` asserting the section is collected and reaches the report.
  Rendering is unchanged: same rows, same labels, same formatting — the fallback only fills values that
  used to be missing. Also hardened while in there: a throwing `defaultDisplay`, a NaN/zero refresh rate,
  a missing `hdrCapabilities`, and an infinite dpi no longer propagate.
- [x] **B7. Camera characteristics race.** Enumerating cameras while another app holds the camera can
  throw `CameraAccessException`; make sure it is caught per camera id, not per whole enumeration.
  *Done:* already the case — `CameraCollector.collect` wraps the whole per-id body (characteristics
  read, stream map, physical ids) in its own `try`, so a camera that throws while another app holds
  it becomes a single `camera.<id>.error` field and the remaining ids are still enumerated; only
  `manager.cameraIdList` itself sits under the outer catch, which is the one failure that genuinely
  leaves nothing to iterate. Nothing to change.
- [x] **B8. Battery `healthPercent` sanity.** Clamp against a plausible range and refuse to report a
  percentage when the charge counter is clearly not at full charge.
  *Done:* the arithmetic moved out of `BatteryCollector` into `BatteryHealthEstimator`, a
  framework-free object with 11 unit tests. The full-charge guard is unchanged (99%), but the old
  `coerceIn(1, 150)` is gone: it turned an unusable measurement into a plausible-looking percentage.
  A charge counter reported in mAh instead of µAh (3700 → 3 mAh, 0.08% of a 3900 mAh design) used to
  be reported as **1%** and is now withheld; an under-reported design capacity (3700 mAh against a
  1000 mAh design, 370%) used to be reported as **150%** and is now withheld. Ratios inside 20–150%
  are reported unchanged, so a worn pack at 60% and a replacement cell at 121% still show. Counters
  that round to 0 mAh, missing/zero/negative counters and a missing design capacity all yield null,
  which the screen already rendered as "Not measurable now".

## C. Memory and lifecycle

- [x] **C1. Single `SystemPropertyReader` instance.** It is currently constructed in three places, so
  the `getprop` dump is parsed up to three times per scan. Share one instance from `AppContainer`.
  *Done:* there were two instances, not three — `AndroidDeviceInformationProvider` (shared by
  `IdentityCollector` and `SecurityCollector`) and a private `SystemPropertySafe` object inside
  `HardwareCollector`, so a scan spawned `getprop` and parsed the same dump twice. `AppContainer` now
  owns one `systemPropertyReader`, passes it to the provider, and the provider passes it to
  `HardwareCollector`; both constructors keep a default so the existing test that builds a collector
  directly still compiles. Per scan: 2 `getprop` processes and 2 dump parses → 1 of each. A 900-line
  dump parses in 247 µs best / 1.9 ms average in a JVM benchmark (300 runs), so the saving is one
  process spawn plus that parse, per scan. The parse also moved into a companion `parseDump` behind an
  injectable loader, which made two things testable and fixed one: a truncated line (`[k]: [`) used to
  throw out of `substring` and abandon the entire dump — it is now skipped, like any other malformed
  line. Seven tests in `SystemPropertyReaderTest` pin parse-once per reader, two parses for two
  readers, malformed-line survival, blank/missing values, typed accessors and a failing loader.
  Behaviour of every accessor is unchanged.
- [x] **C2. Cancel an in-flight scan when a new one starts.** `AppViewModel.scan` returns early if
  `scanning` is true; a queued second tap should either be ignored cleanly or cancel and restart.
  *Done:* newest request wins. The `if (scanning) return` guard is gone; a new `ScanCoordinator`
  (`core/util`) cancels the running job and starts the new scan, so a scan wedged in a blocking
  collector — a Binder call into a camera another app holds, a `getprop` that never exits — can no
  longer lock the app out of scanning for the rest of the process. Cancellation is cooperative, so
  each request carries a generation number and every state write and side effect is gated on
  `isCurrent`: a superseded scan that is still winding down cannot overwrite the progress, the
  report or the history entry of the scan that replaced it, and `CancellationException` is re-thrown
  instead of surfacing as "Scan failed". `publish` now goes through `MutableStateFlow.update`, so
  the read-modify-write is atomic across the IO collector thread and the main thread. Five tests in
  `ScanCoordinatorTest` pin supersede-cancels-the-old-scan, a superseded scan stopping its work,
  generation currency, and that a finished scan does not lock out the next one. No rendering, copy
  or navigation change; `assembleDebug` and all 84 unit tests are green.
- [x] **C3. Debounce `rebuildReport`.** *Not worth doing:* there is no burst to coalesce. Every
  `recordResult` call site is a single button tap followed immediately by `onBack()`, and the one path
  that applies several results at once (`runAutomatedDiagnostics`) already applies them all and
  rebuilds once. A debounce would add a scheduler, a window in which `state.report` is stale, and a
  flush-before-export obligation, to save one rebuild per tap — measured at 299 µs best / 539 µs
  average for the whole four-engine rebuild in a JVM benchmark over the test fixture (parts 48 µs,
  age 48 µs, integrity 7 µs, scores 146 µs). Not worth the staleness risk unless a multi-result
  recording path is ever added.
- [x] **C4. Verify probe teardown.** Confirm `SensorProbe`, `AudioProbe` and `CameraProbe` release
  everything on every path, including when a test screen is left via the system back gesture.
  *Done:* audited all three probes against the screens that own them. The screens hold each probe in
  `remember` and release it from `onDispose`, and `AppNav` renders only `stack.last()`, so both the
  back button and the system back gesture drop the composable and run that block — that half was
  sound, and `SensorProbe.stop()` and `AudioProbe.release()` are symmetric on every path they reach.
  Three release paths were not, and are fixed:
  1. **Camera held forever after a fast exit.** `CameraManager.openCamera` is asynchronous, so leaving
     the camera test before `onOpened` ran meant `close()` saw a null `device` and did nothing; the
     device then arrived, was stored, and started a preview with no owner left to close it — the
     camera (and the torch, if on) stayed claimed until the process died. `CameraProbe` now takes a
     token per open from `StaleCloseGuard` and closes any device or session that arrives under a
     stale one. Tokens taken after `close()` are current again, so reopening still works.
  2. **The failed session was never closed.** `onConfigureFailed` reported the error but left the
     `CameraCaptureSession` open; it is now closed before the error is raised.
  3. **`AudioProbe` leaked on a failed start.** `measureMicrophone` assigned the field only after
     `start()`, so a throw from `setAudioSource`/`prepare()`/`start()` left the `MediaRecorder`
     unreleased, holding the microphone until the finalizer ran; the field is now set at
     construction so the existing catch releases it. `playBeep` likewise held its `ToneGenerator` only
     after `startTone`, and now releases it on failure.
  A stale `onDisconnected`/`onError` also no longer clears a newer device or raises an error for a
  screen that has already gone. Six tests in `StaleCloseGuardTest` pin the guard: current token
  admits, stale token closes, a reopened probe admits again, repeated closes, a close that throws,
  and token stability. No rendering, copy, navigation or feature change; `assembleDebug` and all 90
  unit tests are green. Unverified: the leak itself cannot be reproduced without a device, so the
  evidence is the guard's unit tests plus the callback ordering above, not an observed camera.
- [x] **C5. Avoid holding `Context` in long-lived objects.** Audit `AppContainer` and collectors for
  anything that keeps an `Activity` reference. *Done:* audited the graph. `AppContainer` holds the
  `Application` and nothing else; every collector, the device provider, the diagnostics engine, the
  PDF writer, the history and the asset repository receive that application context, so none of them
  can pin an activity. The one activity reference was in `AndroidPermissionManager`, which
  `MainActivity` wired up with `activityProvider = { this }`. That manager lives in `AppContainer`,
  which lives in the `AppViewModel` and therefore survives configuration changes, so every rotation
  left the destroyed activity — with its whole Compose view tree — reachable until the next rotation
  replaced it. The manager now holds the activity in a new `WeakRefHolder` (`core/util`), bound with
  `attach(activity)` and unbound with `detach(activity)` from `MainActivity.onDestroy`; `detach` is
  identity-checked, so a late detach from a destroyed activity cannot unbind the one that replaced it.
  `getPreferences`/`requestPermissions` call sites are unchanged, so permission behaviour and every
  on-screen state are identical. Seven tests in `WeakRefHolderTest` pin set/get, identity-checked
  clear, replacement, a repeated clear, and that the holder does not keep its referent alive across a
  GC. `assembleDebug` and all 97 unit tests are green (90 before, 7 new). Unverified: the leak itself
  cannot be observed without a device — the evidence is the reference graph above plus the holder's
  unit tests. A permission request that is still pending when the process-scoped manager is detached
  (app finished, not rotated) still leaves its coroutine awaiting, exactly as before; completing it
  needs a decision about what to report to a screen that no longer exists.

## D. Robustness — graceful degradation

- [x] **D1. Timeout the whole scan.** A stuck camera or property read should not hang the scan forever;
  add a per-stage timeout with a clear "timed out" status instead of a silent hang.
  *Done:* every one of the eight collection stages in `AndroidDeviceInformationProvider` now runs
  under a `StageTimer` (`core/util`): 20 s per stage, 90 s for the whole scan, whichever is smaller,
  so a scan always finishes. A stage that misses its deadline is recorded in the existing
  collection-errors list (the same list, and the same place on screen, that a collector failure
  already used: "Camera collection failed: timed out after 20 s") and the remaining stages still
  run, so the scan completes with the facts it could read instead of leaving the screen on
  "Scanning" with no way out. Two details matter: the stage work runs in a provider-owned scope
  rather than as a child of the scan coroutine, because a coroutine blocked inside a Java call
  ignores cancellation and would otherwise keep the caller's structured scope waiting forever; and
  a timed-out stage is cancelled, so a cooperative one stops at its next suspension point while a
  blocked one is simply abandoned. Worst case per scan goes from unbounded to 90 s (8 × 20 s = 160 s
  before the whole-scan cap applies). Cost: one dispatcher hop per stage, measured at 2.2 ms per
  stage and 17.3 ms for all eight (best of 20 runs of eight, 200 warm-up runs, 2-core sandbox),
  against collectors that each take milliseconds to tens of milliseconds. Eight tests in
  `StageTimerTest` pin: done including a null value, failed with the message preserved, timed out
  with the next stage still running, the abandoned stage cancelled, the whole-scan cap capping a
  later stage, no stage started once the budget is spent, and the cap arithmetic. No screen,
  control, style, navigation or normal-path copy change; the only new string is the timeout detail
  inside the existing error line. Unverified: the hang itself needs a device to reproduce, so the
  evidence is the unit tests plus the wiring, not an observed stuck camera.
- [x] **D2. Fuzz the reference JSON loader.** A malformed entry currently aborts the whole load; skip
  the bad entry and keep the rest. *Done:* the parse loop moved into a new `ReferenceAssetParser`,
  and each element of `devices` is now read inside its own `try`. An element that is not a JSON
  object (`"text"`, `null`, `true`, `42`, a nested array) is skipped, and anything unexpected thrown
  while a single entry is read costs that entry only; the rest of the set still loads. A document
  that is not JSON at all, or has no `devices` array, still degrades to an empty set rather than
  throwing. Field extraction is byte-for-byte the code the repository used before, so a well-formed
  entry parses to exactly the same `ReferenceDevice` — including the `optInt`/`optDouble` defaults —
  which a temporary test asserted by comparing the old inline loop against the new parser over all
  16 bundled entries (`assertEquals` on the full lists) before it was removed. Under the old control
  flow the exception left the loop, so an entry at index *i* of *n* cost entries *i…n−1*: one bad
  element early in the set could reduce the app's reference data to a couple of models, silently and
  with the on-screen count shrinking to match. Seven tests in `ReferenceAssetParserTest` pin:
  non-object elements skipped with the neighbours intact, `null`/nested-array/number elements
  skipped, a wrong-typed field defaulted instead of dropping the entry, every field of the entry
  after a bad element preserved, absent numeric fields keeping their defaults, four
  unparseable/missing-`devices` documents yielding an empty list, and the shipped asset still
  parsing to its 16 entries with the first one's values unchanged. `assembleDebug` and all 112 unit
  tests are green (105 before, 7 new).
- [x] **D3. Empty-state audit.** Every screen must render a sensible state when `snapshot` is null and
  when `report` is null — verify each one, no crash on first launch.
  *Done:* every composable `AppNav` can reach (the twelve destinations and the six interactive test
  screens) was rendered against `UiState()` — no snapshot, no report, no stored history — and the
  snapshot-dependent and report-dependent ones again against a snapshot with no report. Nothing
  crashed and no screen needed a change: the ten snapshot- or report-dependent screens already fall
  through to the shared `EmptyState` card, the dashboard renders "No scan yet" with its scan button
  and coerces a null `progress` to 0 % / "Starting", `DiagnosticsScreen` renders from the test
  catalogue alone, `HistoryScreen` has its own no-history copy, and the six test screens degrade to
  "Not available" / "Sensor not present" / waiting states because each reads `state.snapshot?`
  defensively and every probe constructor is side-effect free. The audit is now permanent:
  `EmptyStateTest` (22 Robolectric tests) renders all eighteen screens in those states and asserts
  the specific placeholder text, not merely that composition did not throw. One finding deliberately
  left alone: the report-dependent screens say "Run a scan first." even when a scan has run but no
  report exists. That state is not reachable today — the view model writes `snapshot` and `report` in
  the same update — and correcting the copy is user-visible, so it belongs to a design pass. No UI,
  copy, layout or behaviour change; `assembleDebug` and all 134 unit tests are green (112 before,
  22 new).
- [x] **D4. `PermissionManager` preferences.** `activity.getPreferences(0)` opens a file per call;
  cache the `SharedPreferences` handle.
  *Done:* the handle now lives in a new `PermissionRequestLog` (`data/android`), resolved lazily on
  the first check and kept for the process, so `state` and `request` no longer resolve the file name
  and look the file up on every call. The name is still the activity's — `getLocalClassName`, which
  is what `Activity.getPreferences(0)` used — taken from the first attached activity, so the flags an
  earlier version wrote into `shared_prefs/<Activity>.xml` are still read: a permission refused
  before the update is still reported as permanently refused after it, which a test pins by writing
  the flag through `activity.getPreferences(0)` and asserting `PERMANENTLY_DENIED`. The handle is
  opened from the application context (the file belongs to the package, not to the activity), so it
  survives rotation and keeps no activity alive. `isGranted` also resolved the state twice for
  anything that was not granted — `state(...) == GRANTED || state(...) == NOT_REQUIRED` — and now
  resolves it once. Measured per call, not in microseconds: `state` went from 1 preferences lookup to
  0 after the first, and `isGranted` on a refused permission from 2 `checkSelfPermission` calls and 2
  lookups to 1 of each; on a granted permission and on a permission the platform does not have
  (below API 31) it stays at 1 check and 0 lookups. Eight Robolectric tests in `PermissionManagerTest`
  count the lookups through a `ContextWrapper` and pin the permanent-refusal file name, survival
  across activity recreation, the flag being written before the request is raised, and the granted
  and not-required paths. No UI, copy, layout or permission-behaviour change; `assembleDebug` and all
  142 unit tests are green (134 before, 8 new).

## E. Tests — raise confidence without a device

- [x] **E1. JSON report round-trip test.** Assert every documented key exists and that redaction
  replaces exactly the sensitive fields. *Done:* `ReportJsonTest`, 13 tests over a report built from
  the real four engines, parsed back with `kotlinx.serialization` and compared leaf by leaf. The
  contract is pinned at every level: the twenty top-level keys, the key set of each nested object
  (`platform`, `identity`, `operatingSystem`, a specification, a finding, an evidence item, a
  component, `age`, a check, a score, a score input, a diagnostic, an `unknownOrInaccessible`
  entry), the seven documented score names, the exact contents of `unknownOrInaccessible`
  (NOT_SUPPORTED + UNVERIFIED + PERMISSION_REQUIRED, nothing else), `collectionErrors` present only
  when non-empty, nulls emitted as JSON null rather than dropped, and byte-identical repeat encoding.
  Redaction is asserted as a diff: both encodings are flattened to path → leaf maps, the key sets
  must be equal, and the set of leaves that differ must be exactly `redacted` plus `identity.serial`
  plus the `specifications[i].value` of every field flagged `sensitive` — nothing more, nothing less
  — with every changed value being `REDACTED`. A separate test asserts the fixture's sensitive keys
  are exactly the three identifiers `docs/PRIVACY.md` promises to redact (serial, Bluetooth adapter
  name, signing-certificate digest), and that none of their raw values appears anywhere in the
  redacted text.
  **The test found a real bug and it is fixed:** `identity.serial` was written as `"REDACTED"`
  whenever `redacted` was true, even when the serial was never collected — which is the normal case,
  since Android 10+ hides the serial from unprivileged apps. Every ordinary export therefore claimed
  that an identifier existed and had been withheld. A null serial is now exported as `null` in both
  encodings, so the redacted and unredacted exports of a serial-less device differ only in the
  `redacted` flag itself; the value is still `REDACTED` when a serial was actually read. Export
  content only — no screen, control, style, navigation, copy or feature change. `assembleDebug` and
  all 155 unit tests are green (142 before, 13 new).
- [x] **E2. Parts provider matrix test.** One test per `ComponentState`, asserting the state chosen for
  a given evidence set. *Done:* `PartsProviderMatrixTest`, 17 tests, one or more per state the provider
  can actually emit, each naming the evidence set and asserting the resulting state rather than merely
  that `assess` returned. UNABLE_TO_INSPECT: no reference, an unreadable display configuration, and the
  functional-only row before any test has run — all three also pin `Confidence.NONE` and the single
  `HEURISTIC` placeholder evidence. NO_ANOMALY_UNVERIFIED: a device matching its reference on every row
  at once, with confidence pinned to LOW so a matching number can never be promoted to proof.
  POSSIBLE_REPLACEMENT: a lower-resolution panel, a panel differing only on the short side, a non-good
  battery health code, capacity under 80% with good reported health (LOW confidence, not MEDIUM),
  fewer camera modules than the reference, storage outside the 12% tolerance, a reference sensor that
  was not enumerated, and inconsistent identity strings with matching hardware. POSSIBLE_COUNTERFEIT:
  identity disagreement plus two independent hardware mismatches, and the boundary below it — one
  mismatch plus the same identity warning stays POSSIBLE_REPLACEMENT, and three hardware mismatches
  with consistent identity never reach counterfeit at all. ORIGINALITY_VERIFIED is asserted **never to
  be emitted** across eleven scenarios, which is the invariant the five-state model depends on: Android
  exposes no component identity, so the app must never claim a part is genuine. The same sweep pins
  that every component carries a non-blank explanation and at least one evidence item, and that
  `Confidence.NONE` appears exactly on UNABLE_TO_INSPECT. A separate test pins the seven components and
  their order. Test-only change: no production file touched, no rendering, copy, layout or behaviour
  change. `assembleDebug` and all 172 unit tests are green (155 before, 17 new).
- [x] **E3. Age estimator boundary tests.** Launch date unknown, build date unknown, purchase date in
  the future, cycle count zero. *Done:* `AgeEstimatorTest`, 8 tests added (4 before, 12 total). A
  reference entry with a blank release date and one with an unparseable `releaseDate` (`Q1 2023`) both
  leave `modelLaunchAgeDays` null while the firmware age survives, and the note says no reference
  launch date is available. A null `buildTimeMillis` leaves `firmwareBuildAgeDays` and
  `firmwareBuildDate` null — pinned explicitly, because the report keeps the date null rather than a
  placeholder and `AgeScreen` is what renders `"Unknown"` — with confidence still LOW since the launch
  date is known. With launch date, build date and purchase date all missing, no cycle count and a blank
  security patch, every age field is null and confidence is NONE. A purchase date 30 days in the future
  gives `ownershipAgeDays` 0, never a negative duration, and the usage age is 0 rather than negative
  when it falls back to that ownership value. A cycle count of 0 is treated as no cycle count: usage
  age falls back to ownership age when a purchase date exists and is null otherwise, while a count of 1
  does use the cycle-derived value (1 day) instead of a 400-day ownership fallback. Test-only change:
  no production file touched, no rendering, copy, layout or behaviour change. `assembleDebug` and all
  180 unit tests are green (172 before, 8 new).
- [x] **E4. Reference matching tests.** Sub-brand match, longest-pattern preference, no match, and a
  model string that contains two patterns. *Done:* `ReferenceMatchingTest`, 9 tests. The matching loop
  moved verbatim out of `AssetReferenceRepository` into an internal `ReferenceIndex` built from a
  `List<ReferenceDevice>`, so the rules can be driven with hand-built entries rather than only the
  bundled asset; the repository now just loads the asset and delegates, and the loop, the
  normalisation and the tie-breaking are the same code as before. Pinned: a model string containing
  two patterns of one entry reports that entry's first listed pattern; when two entries both match,
  the longer pattern wins; a brand-equal entry beats a longer pattern from another brand (asserted
  both ways on the same model string, so only the brand input differs); an exact tie keeps the earlier
  entry; case, spaces and punctuation are ignored on both sides; the device string must equal a
  pattern rather than merely contain it (`SM-S911` matches, `SM-S911B` does not, with an empty model);
  a model outside the set and three empty strings match nothing; an entry with no patterns, or with a
  blank one, never matches; and the real bundled asset still resolves `SM-S911B` to Galaxy S23 through
  the repository, rejects `Nokia 3310`, and loads all 16 entries. No screen, control, style, copy,
  navigation or feature change. `assembleDebug` and all 189 unit tests are green (180 before, 9 new).
- [x] **E5. Score coverage tests.** Coverage exactly at the 30% floor, and just below it.
  *Done:* `ScoreCoverageTest`, 8 tests, each driven through `DefaultScoreEngine.score` with an explicit
  parts and integrity report so only the weights that decide coverage vary. **The item's first clause
  is unreachable as written:** coverage is `(availableWeight / totalWeight * 100)` rounded, and no
  reachable subset of any group's weights produces a ratio that rounds to 30 — the tightest pair is
  22% below and 33% above. The floor is therefore pinned at those, which is the strongest statement
  the engine supports without a behaviour change. Pinned: battery at 22% (charge cycles only) withholds
  the score, band UNKNOWN, summary naming the 22%; at 33% (reported health only) it scores 100/GOOD.
  The same pair for Display and sensors (22% = a lone sensor test, 33% = configuration plus refresh
  rate) and for Performance (22% = the low-RAM flag and the core count, which are never missing, 44%
  once the thermal reading is present). An empty parts report withholds both parts-derived groups at
  0%, and Repair evidence completeness scores 20/POOR at 33% from a single recorded diagnostic when no
  component could be inspected. Two groups can never be withheld, which is now pinned rather than
  assumed: Hardware health bottoms out at 63% because four of its inputs are never null, and Software
  and security at 50% for the same reason. The seven group names and their order are asserted as a
  list, which is what the report screen's score count line renders. `TestFixtures` gained nullable
  `temperatureCelsius`, `securityPatch` and `thermalStatusSupported` parameters to build the sparse
  devices. Test-only change: no production file touched, no rendering, copy, layout or behaviour
  change. `assembleDebug` and all 197 unit tests are green (189 before, 8 new).
- [ ] **E6. Screenshot regression.** The existing `ScreenshotTest` renders seven screens; assert the
  images are non-blank and that a missing fixture does not throw.

## F. Build, size and static analysis

- [ ] **F1. Enable R8 for release.** `isMinifyEnabled = true` with a keep rule for the serialisation
  and reflection paths; measure the APK size before and after and record both in the commit.
- [ ] **F2. Fix all compiler warnings.** Deprecated `Icons.Filled.ArrowBack`/`List` → AutoMirrored
  equivalents (visually identical), deprecated `createCaptureSession` → the `SessionConfiguration`
  overload, and unused imports.
- [ ] **F3. Run `gradle lint` and triage.** Fix real findings, suppress the noise with reasons.
- [ ] **F4. Turn on `allWarningsAsErrors` for the `core` and `domain` packages only** if it is clean
  enough to be sustainable.
- [ ] **F5. Trim unused dependencies.** Confirm nothing pulls in `material-icons-extended` or other
  large artefacts by accident; report the dependency tree size.

## G. Documentation of the work

- [ ] **G1. Add `docs/PERFORMANCE.md`** recording each measured improvement (before/after numbers) as
  items land. Numbers, not adjectives.
- [ ] **G2. Keep `docs/LIMITATIONS.md` current** if any fix changes what the app can or cannot claim.

---

## What "better" means here

Not more features and not a prettier screen. Faster to first result, smaller APK, fewer allocations per
scan, no leaks, no silent failures, no crash on a weird device, and a test that would have caught the
bug. If a day produces one real fix that survives review, that is a good day — ten cosmetic commits
would be worse than one honest one.

## H. Data accuracy and platform coverage

- [ ] **H1. Vendor market-name fallbacks.** Read `ro.product.marketname` across the `odm`/`vendor`
  namespaces and pick the first non-empty; some OEMs only populate one.
- [ ] **H2. SoC manufacturer cross-check.** Compare `Build.SOC_MANUFACTURER` with the vendor implied by
  `ro.soc.model` and report a mismatch as `UNVERIFIED`, not as a warning.
- [ ] **H3. Battery health code 0 means unknown, not good.** `BATTERY_HEALTH_UNKNOWN` is currently
  scored like a healthy pack; treat it as missing evidence.
- [ ] **H4. De-duplicate wake-up sensors.** `getSensorList(TYPE_ALL)` returns both variants on many
  devices, inflating the count. Count logical sensors once.
- [ ] **H5. Megapixel source precedence.** When the stream configuration and the pixel array disagree,
  document which one is reported and why, and expose the other value.
- [ ] **H6. Replace deprecated `getRealMetrics`.** Use `WindowMetrics` on API 30+ with the old path as
  fallback; behaviour must stay identical on every API level.
- [ ] **H7. VPN-aware transport reporting.** With a VPN active, report the underlying transport as well
  as the VPN instead of only "VPN".
- [ ] **H8. Count packages once.** `getInstalledPackages` is called and walked twice in places; derive
  system and user counts from a single pass.
- [ ] **H9. Verified-boot fallbacks.** Fall back to `ro.boot.veritymode` when
  `ro.boot.verifiedbootstate` is absent, and say which property answered.
- [ ] **H10. Locale audit.** Every `String.format` must pass `Locale.US`; a device set to Arabic or
  Hindi digits currently renders numbers inconsistently in the report.
- [ ] **H11. Per-volume storage.** Use `StorageManager` to report internal versus adoptable volume free
  space instead of only the data partition.
- [ ] **H12. Per-core CPU frequencies.** Read max frequency for every core, not just `cpu0`, and report
  the cluster layout when it is readable.
- [ ] **H13. Elapsed-realtime distinction.** Report both `elapsedRealtime` (includes deep sleep) and
  `uptimeMillis` (awake only) so uptime is unambiguous.
- [ ] **H14. Reference codenames.** Add codename patterns to the reference entries so matching works
  for builds whose `Build.MODEL` is a carrier variant.

## I. Tooling and tests that protect the work

- [ ] **I1. Reference-loader test.** Feed a malformed entry and assert the rest of the set still loads.
- [ ] **I2. Formatter edge-case tests.** Zero, negative, exactly 1023/1024, and very large byte counts.
- [ ] **I3. Status exhaustiveness guard.** A unit test asserting every `DiagnosticStatus` and
  `ComponentState` maps to a colour and a label, so a future enum value cannot ship unrendered.
- [ ] **I4. `tools/check-no-ui-change.sh`.** Diffs `ui/` and `res/` against a reference commit and exits
  non-zero if they changed — the hard rule becomes enforceable instead of aspirational.
- [ ] **I5. `tools/measure.sh`.** Records APK size, method count and a timed debug build into
  `docs/PERFORMANCE.md`, so every claim of "faster" has a number behind it.
- [ ] **I6. Redaction test.** Assert that a report with a serial number, a Bluetooth name and a
  certificate digest contains none of them when `redacted = true`.
- [ ] **I7. GitHub Actions CI.** Build and test on every push so a regression is caught by the
  repository itself, not only by a nightly run.
- [ ] **I8. Lint triage.** Run `gradle lint`, fix real findings, suppress the rest with a written reason.
- [ ] **I9. Screenshot non-blank assertion.** Fail the render test if an image comes back uniform.
- [ ] **I10. Dependency audit.** Record the resolved dependency tree and flag anything unexpectedly
  large.

---

## When the list runs out

If fewer than ten unchecked items remain, do not lower the bar and do not invent work. Instead:

1. Re-read the code with the same lens as the sections above — allocations on the scan path, Binder
   calls that could be cached, error paths that could be silent, missing tests around a hot path.
2. Add **specific, verifiable** items to this file (name the function and the change), then implement
   the first one in the same run.
3. Never add an item that requires a visible change. Those belong to a future design pass, not to this
   one.
