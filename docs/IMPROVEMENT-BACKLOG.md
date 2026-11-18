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
- [x] **E6. Screenshot regression.** The existing `ScreenshotTest` renders seven screens; assert the
  images are non-blank and that a missing fixture does not throw.
  *Done:* `ScreenshotTest`, 15 tests (7 before, 8 new). Every render is now measured instead of
  merely written: the window's decor view is drawn into a bitmap, sampled on a 2-pixel stride, and
  the screen must be 1078 x 2338 px with at least 100 distinct colours and at least 2% of sampled
  pixels off the dominant colour. The thresholds sit far from both ends — the densest screen here
  (dashboard) measures 2500 colours and 64% ink, the sparsest (specifications) 1087 and 7.7%, while
  a frame with nothing drawn measures one colour and 0% ink — so a capture that silently lost its
  content fails while an ordinary edit does not. `aBlankFrameIsRejected` renders an empty frame and
  asserts the check rejects it, so the seven screen assertions cannot pass for a reason unrelated to
  the screen having been drawn. `captureToImage()` was tried first and does not work here: under
  Robolectric it times out in `forceRedraw` (`ComposeTimeoutException`, a draw callback the shadow
  view root never delivers), so the rule is now `createAndroidComposeRule<ComponentActivity>()` and
  the decor view is drawn directly — the same path Roborazzi's own capture takes. Seven further
  tests render each screen with a bare `UiState()` — no snapshot, no report, no session, i.e. a
  missing fixture — and assert a real screen came out (348 to 410 colours, 8.2% to 8.5% ink) rather
  than only that nothing threw; they publish no PNG, since those states are not part of the
  documented screenshot set. This is the pixel-level companion to `EmptyStateTest`, which asserts
  the empty-state text. Test-only change: no production file touched, no rendering, copy, layout or
  behaviour change, and no published screenshot altered. `assembleDebug` and all 205 unit tests are
  green (197 before, 8 new).

## F. Build, size and static analysis

- [ ] **F1. Enable R8 for release.** `isMinifyEnabled = true` with a keep rule for the serialisation
  and reflection paths; measure the APK size before and after and record both in the commit.
  *Deferred (5 Nov run):* not shipped unverified. The baseline unsigned release APK was built and
  measured at 6,570,591 bytes, and the rules were written and parse cleanly, but `minifyReleaseWithR8`
  does not finish inside one 300 s sandbox call (tried with 1024/1400/1600 MB heaps, serial GC, legacy
  and full mode, and with and without `-dontoptimize`), so no minified APK could be produced and
  nothing in the release path could be checked. Re-run this on a machine that can finish the R8 task.
  *Re-tried (5 Nov, second run):* the earlier slowness was partly self-inflicted — this sandbox exports
  `JAVA_TOOL_OPTIONS=-XX:CompressedClassSpaceSize=64m`, which starves D8 and the Kotlin compiler. With
  that unset, a cold `assembleDebug` drops from "never finishes" to ~6 min and `mergeExtDexDebug` from
  >280 s to 1m46. The baseline unsigned release APK was rebuilt and reproduces at exactly 6,570,591
  bytes. `minifyReleaseWithR8` alone still exceeds a 240 s call, both with
  `proguard-android-optimize.txt` and with `proguard-android.txt` (no optimisation passes) at a 1.5 GB
  heap, and the task is not resumable across calls. Note for the next attempt: this app has **no**
  `@Serializable` classes — `ReportJson` only encodes `JsonElement` through a statically referenced
  serializer — so the keep-rule risk that held this item back is smaller than assumed; the only
  reflective lookups are framework classes (`android.os.SystemProperties`,
  `com.android.internal.os.PowerProfile`) that R8 never renames.
  *Re-tried (5 Nov, third run):* `isMinifyEnabled = true` plus keep rules were applied again on a
  host with warm transform caches and a 1750 MB daemon heap — the heap everything else in the build
  now needs on this 2 GB / 2-core sandbox. `:app:minifyReleaseWithR8` alone exceeded a 271 s call on
  both attempts and the task restarts from zero when the call is killed, so no minified APK was ever
  produced and nothing in the release path could be measured or checked. Reverted; the item stays
  open until it runs on a host with more memory than a single Gradle call can use.
  *Passed over (6 Nov run):* unchanged — this sandbox is still 2 cores / 2 GB, where a cold
  `assembleDebug` alone needs four 300 s calls, so `minifyReleaseWithR8` cannot be run or checked here.
  *Passed over (6 Nov, second pass):* unchanged — still 2 cores / 2 GB, and a cold `assembleDebug`
  still needs four calls, so there is no room for a non-resumable `minifyReleaseWithR8`.
  *Passed over (6 Nov, third pass):* unchanged again — `nproc` = 2, `free` = 2,098 MB. A cold
  `assembleDebug` this run took five calls (about 25 minutes, `mergeExtDexDebug` alone 4m08s at a
  1750 MB heap), and `minifyReleaseWithR8` still does not fit in a single 300 s call and restarts
  from zero when the call is killed, so no minified APK can be produced or measured here.
  *Passed over (6 Nov, fourth pass):* unchanged — still 2 cores / 2,098 MB, and the cold
  `assembleDebug` for this run again needed eight calls (about 40 minutes), so the non-resumable
  `minifyReleaseWithR8` still cannot be produced, let alone measured.
  *Passed over (6 Nov, fifth pass):* unchanged hardware (2 cores / 2,098 MB). One new fact for the
  next attempt: killing the Gradle client at the 300 s call limit does **not** leave the daemon
  working on the task — every later call in this run reported "Starting a Gradle Daemon" again, so a
  killed `minifyReleaseWithR8` still loses all of its work. The one exception seen was a task that
  finished ~9 s after the client was killed; that is a race, not a way to run a 15-minute task. Also
  confirmed this run: the repo's `-XX:MaxMetaspaceSize=320m` is too small for the Compose compile —
  the daemon expires out of metaspace and the build fails — so the release path needs a raised
  metaspace ceiling *and* a 1750 MB heap, which is 2 GB of a 2 GB machine before R8 starts.
  *Passed over (10 Nov run):* unchanged — `nproc` = 2, `free` = 2,098 MB, the same host as every
  attempt above, so a non-resumable `minifyReleaseWithR8` still cannot be produced or measured here.
  *Passed over (6 Nov, sixth pass):* unchanged — `nproc` = 2, 2,098 MB. This run's cold
  `assembleDebug` needed six calls and a further one for `testDebugUnitTest`, so the non-resumable
  `minifyReleaseWithR8` still has nowhere to run. Nothing in the release path can be checked here.
  *Passed over (7 Nov run):* unchanged — `nproc` = 2, 2,098 MB. This run's cold `assembleDebug`
  needed seven calls and `testDebugUnitTest` two more, so a non-resumable `minifyReleaseWithR8`
  still has nowhere to run here.
  *Attempted and passed over (7 Nov, second pass):* this run did try again rather than assume.
  `isMinifyEnabled = true` was applied and the whole release input chain built
  (`:app:compileReleaseKotlin` alone needed two calls, 3m57s in the second), then
  `:app:minifyReleaseWithR8` was run twice with **every** prerequisite UP-TO-DATE and the
  configuration cache reused, so the calls went straight into R8: it still did not finish inside a
  288 s call (~265 s of pure R8 on 2 cores / 2,098 MB), and it restarts from zero when the call is
  killed, so no minified APK exists to measure. Reverted to `isMinifyEnabled = false`; the
  release path is untouched. This task needs a host where one Gradle invocation can run longer
  than 300 s, not another retry here.
  *Passed over (7 Nov, third pass):* not retried again — `nproc` = 2, 2,098 MB, the same host on
  which R8 was already attempted twice today and where a killed call still discards all of its work.
  This run's cold `assembleDebug` needed five 300 s calls, so there is no window for a
  non-resumable `minifyReleaseWithR8`.
  *Passed over (8 Nov run):* unchanged — `nproc` = 2, 2,098 MB. This run's cold build again took
  several 300 s calls, and `minifyReleaseWithR8` still cannot finish inside one call or resume
  across calls, so no minified APK exists to measure.
  *Passed over (8 Nov, second pass):* unchanged — `nproc` = 2, 2,098 MB, and this run's cold
  `assembleDebug` needed six 300 s calls on its own, so the non-resumable `minifyReleaseWithR8`
  still has nowhere to run.
  *Attempted and passed over (8 Nov, third pass):* tried again, with one new lever. Every earlier
  attempt lost ~100 s of each 300 s call to configuration, because a killed build discards the
  configuration-cache entry and the next call recomputes it; pre-warming it with a `--dry-run` first
  makes the next call reuse it (`Reusing configuration cache`) and go straight into R8. Even so
  `:app:minifyReleaseWithR8` did not finish inside a 288 s call in **three** runs: with
  `proguard-android-optimize.txt`, again with `-dontoptimize` added to the rules, and once more with
  a warm configuration cache and every prerequisite UP-TO-DATE. The unminified baseline was rebuilt
  first and reproduces at exactly **6,570,591 bytes**, so the "before" is confirmed; there is no
  "after" to report, and `isMinifyEnabled` is back to `false`. The keep rules that were tried are
  recorded in this entry's history, not in the tree: this app has no `@Serializable` classes, so the
  only reflective lookups are `android.os.SystemProperties` and
  `com.android.internal.os.PowerProfile`, both framework classes R8 never renames. This task needs a
  host where one Gradle invocation can run longer than 300 s, not another retry here.
  *Passed over (9 Nov run):* unchanged — `nproc` = 2, 2,098 MB, and `minifyReleaseWithR8` is not resumable
  across calls, so no minified APK can be produced or measured here.
  *Passed over (9 Nov, second pass):* unchanged — `nproc` = 2, 2,098 MB, and this pass's cold
  `assembleDebug` took eight 300 s calls before it first went green, so the non-resumable
  `minifyReleaseWithR8` again had nowhere to run.
  *Passed over (11 Nov run):* unchanged — `nproc` = 2, 2,098 MB, and this run's cold `assembleDebug`
  needed seven 300 s calls before it first went green, so the non-resumable `minifyReleaseWithR8`
  still has nowhere to run here.
  *Passed over (11 Nov, third pass):* unchanged — `nproc` = 2, 2,098 MB, and this run's cold
  `assembleDebug` needed four 300 s calls before it went green, so the non-resumable
  `minifyReleaseWithR8` still has nowhere to run here.
  *Passed over (12 Nov run):* unchanged — `nproc` = 2, 2,098 MB. This run's cold `assembleDebug`
  needed six 300 s calls before it went green (and `testDebugUnitTest` needed three more), so a task
  that has to finish inside one call and restarts from zero when it is killed still has no window
  here. Not retried again for the same reason as the three attempts on 7-8 November.
  *Passed over (12 Nov, second pass):* unchanged — `nproc` = 2, `free` = 2,098 MB, the same host as
  every attempt above. This pass's cold `assembleDebug` needed six 300 s calls before it first went
  green, so the non-resumable `minifyReleaseWithR8` still has no window here.
  *Passed over (13 Nov run):* unchanged — `nproc` = 2, `free` = 2,098 MB; this run's cold
  `assembleDebug` needed four 300 s calls, so the non-resumable `minifyReleaseWithR8` still has no
  window here.
  *Passed over (14 Nov, third pass):* unchanged — still out of reach, and now for a measured reason
  rather than a guessed one: the earlier attempts put ~265 s of *pure R8* in a 288 s call on a host
  that is faster than this one, so even with this run's build fix (see the OO section, which makes a
  cold `:app:compileDebugKotlin` fit inside one call) `minifyReleaseWithR8` has no window here.
  *Passed over (16 Nov run):* unchanged — `nproc` = 2, `free` = 2,098 MB. Not retried: this run
  spent its build budget getting `:app:lintDebug` (F3) to complete for the first time, and a release
  R8 run needs the whole release input chain built first (`:app:compileReleaseKotlin` and its
  dependency graph, several calls on this host) before the non-resumable `minifyReleaseWithR8` even
  starts. Nothing about that changed today.
  *Passed over (16 Nov, second pass):* unchanged — `nproc` = 2, `free` = 2,098 MB. This pass's cold
  `assembleDebug` again needed five 300 s calls before it linked (a sixth finished the link in
  1m13s), and `minifyReleaseWithR8` still cannot finish inside one 300 s call or resume across
  calls, so there is no minified APK to produce, measure or check here. See the ZZ section for this
  pass's build-environment findings.

- [x] **F2. Fix all compiler warnings.** Deprecated `Icons.Filled.ArrowBack`/`List` → AutoMirrored
  equivalents (visually identical), deprecated `createCaptureSession` → the `SessionConfiguration`
  overload, and unused imports. *Done:* a full non-incremental compile
  (`gradle :app:compileDebugKotlin -Pkotlin.incremental=false`, and the same for the unit-test source
  set) now reports **0 warnings**, down from 10 in `main` (9 sites) and 10 in `test`. Migrated:
  `Icons.AutoMirrored.Filled.ArrowBack`/`List` (same glyphs; the seven screen-capture assertions in
  `ScreenshotTest` are unchanged); `createCaptureSession(SessionConfiguration(SESSION_REGULAR, …))` on
  API 28+ with the old three-argument call kept for API 26-27, same outputs and the same main-thread
  handler behind an `Executor`; `BluetoothAdapter.getDefaultAdapter()` dropped as the fallback behind
  `BluetoothManager.adapter` (same source, null in the same cases); `HdrCapabilities.supportedHdrTypes`
  read from `Display.mode.supportedHdrTypes` on API 34+ with the deprecated call below it. Suppressed
  with a comment where a "fix" would change behaviour: `onRequestPermissionsResult` (moving to the
  Activity Result API rewires the process-scoped permission manager and its request log),
  `Settings.System.HAPTIC_FEEDBACK_ENABLED` (deprecated without a replacement) and
  `KeyInfo.isInsideSecureHardware` (its successor `securityLevel` reports nothing below API 31 and is
  not the same boolean, so adopting it would change the reported evidence). 17 unused imports removed;
  `@OptIn(ExperimentalCoroutinesApi::class)` added to `ScanCoordinatorTest`. `assembleDebug` and all
  205 unit tests green. No UI, copy or layout change.
- [x] **F3. Run `gradle lint` and triage.** Fix real findings, suppress the noise with reasons.
  *Deferred (5 Nov, second run):* lint cannot complete here. `:app:lintDebug` runs the main, androidTest
  and unit-test analyses in parallel on 2 cores and is killed at 240 s; even `:app:lintAnalyzeDebug`
  alone (after the dependency downloads were cached) exceeds a 236 s call, and the analysis is not
  resumable. Nothing was triaged, so nothing was suppressed: a baseline written from a half-finished
  analysis would hide real findings. Needs a machine with more cores, or `lint` run per-module in CI.
  *Passed over (6 Nov run):* same constraint — a single `lintAnalyzeDebug` does not fit in a 300 s call
  on 2 cores, and a half-finished analysis cannot be triaged honestly.
  *Passed over (6 Nov, second pass):* same constraint, unchanged hardware.
  *Passed over (6 Nov, third pass):* same constraint — a single `lintAnalyzeDebug` does not fit in a
  300 s call on 2 cores, and a half-finished analysis cannot be triaged honestly.
  *Passed over (6 Nov, fourth pass):* same constraint, unchanged hardware; nothing was suppressed,
  because a baseline from a killed analysis would hide real findings.
  *Passed over (6 Nov, fifth pass):* same constraint, unchanged hardware. This run's cold
  `assembleDebug` needed six calls, one of them a single 4m28s `mergeExtDexDebug`, so a non-resumable
  `lintAnalyzeDebug` has no room to finish either.
  *Passed over (6 Nov, sixth pass):* same constraint — this run's cold `assembleDebug` alone took six
  300 s calls on 2 cores, so a single non-resumable `lintAnalyzeDebug` cannot be finished or triaged.
  *Passed over (10 Nov run):* same constraint, unchanged hardware (2 cores / 2,098 MB); a
  half-finished analysis still cannot be triaged honestly, so nothing was suppressed.
  *Passed over (7 Nov run):* unchanged — 2 cores, and a single non-resumable `lintAnalyzeDebug`
  still does not fit in a 300 s call, so nothing could be triaged honestly.
  *Passed over (7 Nov, third pass):* unchanged — same 2 cores / 2,098 MB, and this run's cold
  `assembleDebug` took five 300 s calls on its own, so the analysis cannot be finished or triaged.
  *Passed over (8 Nov run):* unchanged — 2 cores, and a single non-resumable `lintAnalyzeDebug`
  still does not fit in a 300 s call, so nothing could be triaged honestly.
  *Passed over (8 Nov, second pass):* unchanged — same 2 cores, and this run's cold `assembleDebug`
  alone took six 300 s calls, so the analysis cannot be finished or triaged.
  *Passed over (9 Nov run):* unchanged — a single non-resumable `lintAnalyzeDebug` does not fit in a 300 s
  call on 2 cores, and a half-finished analysis cannot be triaged honestly.
  *Passed over (9 Nov, second pass):* unchanged — same 2 cores, and the cold build alone consumed most of
  this pass's 300 s calls, so the analysis still cannot be finished or triaged honestly.
  *Passed over (11 Nov run):* unchanged — same 2 cores / 2,098 MB, and this run's cold `assembleDebug`
  took seven 300 s calls, so a non-resumable `lintAnalyzeDebug` still cannot be finished or triaged.
  *Passed over (12 Nov, second pass):* unchanged — same 2 cores / 2,098 MB, and this pass's cold
  `assembleDebug` took six 300 s calls, so a single non-resumable `lintAnalyzeDebug` still cannot be
  finished, and a half-finished analysis still cannot be triaged honestly.
  *Passed over (13 Nov run):* unchanged — same 2 cores / 2,098 MB, so a single non-resumable
  `lintAnalyzeDebug` still cannot be finished inside a 300 s call, and a half-finished analysis still
  cannot be triaged honestly.
  *Passed over (14 Nov, third pass):* not attempted again. The build fix recorded in the OO section
  (`-XX:TieredStopAtLevel=1`) makes a one-shot JVM task much cheaper, but `lintAnalyzeDebug` is a
  multi-minute analysis on the same two cores, and this run spent its build budget proving that
  `assembleDebug` and the suite can run here at all. Worth retrying first on the next run, now that
  a cold Kotlin compile no longer eats the whole call.

  *Done (16 Nov run):* `gradle :app:lintDebug` completed for the first time in this campaign and the
  report was triaged issue by issue. Getting it to finish took two levers, both environment-only:
  `-XX:TieredStopAtLevel=1 -XX:+UseSerialGC` on the Gradle JVM (the OO section's fix), and the fact
  that AGP runs one analysis task per source set (`lintAnalyzeDebug`, `…DebugAndroidTest`,
  `…DebugUnitTest`), each of which caches separately — so a run killed at the 300 s call limit keeps
  whatever analyses had already finished, and the report appears on a later call. The complete
  report (6 errors, 13 warnings, 13 issue types) is the input to everything below.

  *Real defects found and fixed:*
  1. **`SecurityCollector` called `BiometricManager.canAuthenticate(int)` behind an API 29 guard, but
     the method is API 30.** On Android 10 the call reached a method the platform does not have, and
     `NoSuchMethodError` — a `LinkageError`, which `StageTimer` deliberately contains as a *failed
     stage* — took the whole security stage down: every Security-screen row fell back to
     "Unavailable", and `DefaultIntegrityAnalyzer` went on to report "Root access: no indicators
     found" from an empty list it never collected. The guard is now `SDK_INT >= 30`, the calls sit
     inside it (lint's flow analysis follows the call site, not a null-checked handle), and the two
     local `AUTH_STRONG`/`AUTH_WEAK` masks are replaced by `BiometricManager.Authenticators.*`, which
     is what the WrongConstant finding asked for (the values were already identical). **Proven, not
     argued:** `SecurityCollectorBiometricApiLevelTest` fails with
     `NoSuchMethodError: 'int android.hardware.biometrics.BiometricManager.canAuthenticate(int)'` at
     `SecurityCollector.kt:209` against the old code and passes against the new one; the API-34 case
     pins that the call is reachable where it exists. It uses sdk 34 rather than 30 so the suite does
     not build an extra Robolectric sandbox.
  2. **`USE_BIOMETRIC` was never declared**, which is what the two `MissingPermission` errors said:
     the app calls `BiometricManager.canAuthenticate`, whose contract requires it. It is a normal
     permission — granted at install, no prompt, no new screen or copy — so it is now in the manifest
     with a comment saying why. This is the root fix rather than a suppression.
  3. **`IdentityCollector.readSerial` tested `SDK_INT >= 26` with `minSdk` 26**, so the branch was
     always true and the deprecated `Build.SERIAL` fallback behind it was unreachable
     (`ObsoleteSdkInt`). The dead branch is gone; the call and its `SecurityException` handling are
     unchanged, so every supported level reads exactly what it read before.
  4. **`MainActivity` repeated the application label verbatim** (`RedundantLabel`). The attribute is
     removed; the launcher and recents card resolve to the same `@string/app_name` either way.

  *Noise suppressed with a written reason* (all in the new `app/lint.xml`, each entry carrying why,
  plus two site-level `@SuppressLint` annotations where an issue-level ignore would have been too
  broad): `PrivateApi` (`SystemProperties`, `PowerProfile` — both already try/catch-guarded),
  `QueryPermissionsNeeded` (`getInstalledPackages`; the row already says the count is a lower bound,
  and `QUERY_ALL_PACKAGES` is a Play-policy problem, not a fix), `InlinedApi` (compile-time constants
  that are present and safe on every level), `GradleDependency` (dependency upgrades are I10's job, not
  a mid-campaign change with no device to re-test on), `MissingPermission` on `adapter.name` (already
  inside `attempt {}`, which turns the refusal into the row's "Unreadable" note) and
  `HardwareIds`/`MissingPermission` on `Build.getSerial()` (the serial is this app's subject and is
  redacted by default). Four findings live under `ui/` or `res/` (`ModifierParameter`,
  `AutoboxingStateCreation` ×2, `MonochromeLauncherIcon`, `ObsoleteSdkInt` in `MiscTestScreen`) and
  are ignored by path with the reason that the UI freeze forbids touching them — they are deferred to
  the design pass, not declared acceptable.

  *Verification:* `assembleDebug` green. `testDebugUnitTest` green — **474 tests, 69 classes, 0
  failures, 0 skipped** (472 before, plus the two new cases). `tools/check-no-ui-change.sh` reports
  `OK — nothing under ui/ or res/ differs from HEAD`. No screen, control, style, spacing, colour,
  layout or user-visible copy changed; the only reported values that move are on API 29, where the
  security rows previously did not exist at all because the stage had failed.
  *Not verified:* the confirmation re-run of `lintDebug` after these edits did not complete inside a
  300 s call — `lintAnalyzeDebug` has to redo its whole analysis once the main sources change, and on
  this two-core host it does not fit again (three attempts). Each of the six errors is addressed by
  construction (the exact constant lint asked for, the call inside the level check, the permission
  declared, the dead branch removed, the redundant attribute removed, two reasoned suppressions), but
  "zero findings" is unconfirmed; re-run `gradle :app:lintDebug` on a host with more cores to confirm.
  *Environment note for the next run:* the unit suite only fits inside one 300 s call here with
  `-Drobolectric.usePreinstrumentedJars=true` on the test JVM (set from an init script, not from the
  tree) — without it every Robolectric class re-instruments the framework classes and the suite stops
  finishing. With it the whole suite ran in 4m46s.

- [x] **F4. Turn on `allWarningsAsErrors` for the `core` and `domain` packages only** if it is clean
  enough to be sustainable. *Not applicable as scoped:* kotlinc has no per-package or per-source-set
  `-Werror`; the flag is module-wide, and `-Werror` on the whole `:app` module is exactly what this item
  avoided — it would turn any future third-party deprecation into a build break rather than protecting
  `core`/`domain`. F2 already holds the module at 0 warnings, so there is nothing this would catch
  today. Re-scope as a CI warning-count check if it is wanted.
- [x] **F5. Trim unused dependencies.** Confirm nothing pulls in `material-icons-extended` or other
  large artefacts by accident; report the dependency tree size. *Audited, nothing to trim:*
  `releaseRuntimeClasspath` resolves to **149 modules**. The icon dependency is `material-icons-core`
  only; `material-icons-extended` appears nowhere in the tree, and every other top-level entry maps to a
  declared dependency (compose-bom, core-ktx, activity-compose, lifecycle-runtime/viewmodel-compose, the
  compose artefacts, kotlinx-coroutines-android, kotlinx-serialization-json). Robolectric, Roborazzi and
  `ui-test-junit4` stay confined to `testImplementation`, and `ui-tooling`/`ui-test-manifest` to
  `debugImplementation`, so none of them reach the release APK.

## G. Documentation of the work

- [x] **G1. Add `docs/PERFORMANCE.md`** recording each measured improvement (before/after numbers) as
  items land. Numbers, not adjectives.
  *Deferred (5 Nov, second run):* every before/after number the A–E items produced is already written
  into this file next to the item, so a second copy today would only duplicate them and drift. Write it
  when an item lands whose measurement is not already recorded here.
  *Passed over (6 Nov run):* H4's numbers are recorded in its own entry, so the duplication argument
  still holds; this waits for an item whose measurement has no home in this file.
  *Passed over (6 Nov, second pass):* still duplication — every number it would hold is already next to
  the item that produced it, in this file. Write it once the numbers are stable, at the end of the window.
  *Passed over (8 Nov, second pass):* unchanged — still duplication; L4's numbers are recorded in its
  own entry.
  *Passed over (6 Nov, third pass):* unchanged — this run produced a tool (I4), not a measurement, so
  there is still no number without a home in this file.
  *Passed over (6 Nov, fourth pass):* this run's one measurement (core 0's ceiling vs the part's) is
  recorded in H12's own entry, so the duplication argument still holds.
  *Partly delivered (6 Nov, fifth pass):* I5 landed and with it `docs/PERFORMANCE.md`, holding the
  reproducible artefact baseline (9,811,608 bytes, 102,504 methods, 14,071 classes), how to take a
  new row, and the two hardware-independent per-item numbers. The item stays open only for the rest:
  per-item before/after figures still live next to their items in this file, and copying them across
  would be the duplication this entry has been avoiding. Close it at the end of the window, when the
  numbers are stable.
  *Passed over (6 Nov, sixth pass):* this run produced no measurement — a CI workflow and two
  already-covered backlog items — so there is still no number without a home in this file.
  *Passed over (7 Nov run):* this run's finding (an interrupted keystore probe poisoning every
  later one) is recorded in full in J1's own entry, so a second copy still would not pay for itself.
  *Passed over (8 Nov run):* this run's measurement (the `getprop` deadline, L3) is recorded in
  L3's own entry, so the duplication argument still holds.
  *Passed over (9 Nov run):* this run's measurement (the wrap layout, Q1) is recorded in Q1's own entry, so
  the duplication argument still holds.
  *Passed over (11 Nov run):* this run produced no measurement (the change below is an error path, not a
  timing), so there is still no number without a home in this file.
  *Done (14 Nov, fourth pass):* the file exists — it was added on 6 November and carries the
  artefact-level baseline row (9,811,608 bytes, 102,504 methods, 14,071 classes, 12 dex files) plus
  the two hardware-independent per-item numbers (H4, H12) and the note that every other item's
  before/after lives next to that item in this file. `tools/measure.sh` appends a row per run. The
  checkbox was simply never ticked; nothing was written today, and no new number is claimed.

- [x] **G2. Keep `docs/LIMITATIONS.md` current** if any fix changes what the app can or cannot claim.
  *Checked, no change needed:* H1 below only widens where an already-published value is read from; it
  makes no new claim, and `LIMITATIONS.md` already covers reference-data coverage and per-API
  availability.

---

## What "better" means here

Not more features and not a prettier screen. Faster to first result, smaller APK, fewer allocations per
scan, no leaks, no silent failures, no crash on a weird device, and a test that would have caught the
bug. If a day produces one real fix that survives review, that is a good day — ten cosmetic commits
would be worse than one honest one.

## H. Data accuracy and platform coverage

- [x] **H1. Vendor market-name fallbacks.** Read `ro.product.marketname` across the `odm`/`vendor`
  namespaces and pick the first non-empty; some OEMs only populate one. *Done:* the old lookup read
  `ro.product.marketname`, `ro.product.odm.marketname` and `ro.vendor.product.marketname`, so it missed
  the **standard partition-qualified spelling** `ro.product.vendor.marketname` (and `system`,
  `system_ext`, `product`) entirely — a device that publishes the marketing name only from its vendor
  partition reported "Unavailable"/`UNVERIFIED`. `IdentityCollector.MARKETING_NAME_KEYS` now lists all
  eight keys in the order `init` resolves them (system, system_ext, product, vendor, odm, then the
  pre-partition `ro.<partition>.product.*` spellings last, so a standard key always wins), and
  `marketingName(props)` returns the value **with the key that answered**, which the field now reports
  as its `source` instead of always claiming `ro.product.marketname`. Blank values are still skipped by
  `SystemPropertyReader.get`, so "first non-empty" holds. Seven new tests in
  `IdentityMarketingNameTest` pin the precedence, the blank case, the null case and the key table.
  No new screen, control, style or copy: the only rendering difference is that a device which publishes
  the name from a partition other than `system` now shows the name (and the true property) where it
  previously showed "Unavailable". `assembleDebug` and all 212 unit tests green (205 before, 7 new).
- [ ] **H2. SoC manufacturer cross-check.** Compare `Build.SOC_MANUFACTURER` with the vendor implied by
  `ro.soc.model` and report a mismatch as `UNVERIFIED`, not as a warning.
  *Skipped (5 Nov, third run):* every shape of this lands in `DefaultAuthenticityEngine` as a new
  finding — a new card with new title, explanation and evidence lines on the authenticity screen —
  so it needs a visible change. It also needs a vendor-implied-by-SoC-name table, which is a data
  set this app does not carry. Belongs to a design pass, not this one.
  *Passed over (6 Nov, fourth pass):* unchanged — a new authenticity finding is a new card, which the
  UI freeze forbids, and the vendor table is still data the app does not carry.
  *Skipped (7 Nov run):* unchanged — a new authenticity finding is a new card, which the UI freeze
  forbids, and the vendor table is still data the app does not carry.
  *Passed over (8 Nov run):* unchanged — a new authenticity finding is a new card, which the UI
  freeze forbids, and the vendor table is still data the app does not carry.
  *Passed over (8 Nov, second pass):* unchanged — same reason.
  *Passed over (9 Nov run):* unchanged — a new authenticity finding is a new card, which the UI freeze
  forbids, and the vendor table is still data the app does not carry.

- [x] **H3. Battery health code 0 means unknown, not good.** `BATTERY_HEALTH_UNKNOWN` is currently
  scored like a healthy pack; treat it as missing evidence. *Done:* in `DefaultPartsProvider` the
  battery branch tested `healthCode != 2 && healthCode > 0`, which sent code 1 (`BATTERY_HEALTH_UNKNOWN`)
  to `POSSIBLE_REPLACEMENT` — a warning about a pack the platform never assessed — while codes 0 and 1
  fell through to the `else` branch that says "The battery reports good health". The replacement branch
  now requires `healthCode > 1`, the good-health branch requires `healthCode == 2` exactly, and an
  absent or unknown code reaches the existing `unable(...)` helper as `UNABLE_TO_INSPECT` with
  `Confidence.NONE`. A measured capacity below 80% still wins over a missing code, as before, and
  `DefaultScoreEngine` already excluded codes 0 and 1 from the "Reported health" input, so no score
  input changes; the component-confidence score now counts the pack as uninspected rather than as
  inspected-and-clean, which is what it means. Three tests added: absent code, unknown code, and a
  guard that an explicit code 2 still reports `NO_ANOMALY_UNVERIFIED`. `assembleDebug` and all 215 unit
  tests green (212 before, 3 new). No screen, control, style, layout or copy added.
- [x] **H4. De-duplicate wake-up sensors.** `getSensorList(TYPE_ALL)` returns both variants on many
  devices, inflating the count. Count logical sensors once. *Done:* a wake-up sensor is not a second
  sensor — it is the same physical device published by the HAL under a second handle of the **same
  type** so it can batch and deliver while the AP is suspended, so a device with 22 sensors enumerates
  around 40 handles. New `core/util/SensorInventory.kt` holds `logicalSensorCount(sensors)`: a type that
  publishes at least one ordinary handle is counted by those handles alone (two chips of one type still
  count as two), and a wake-up handle is counted only for a type that publishes nothing else, because
  some sensors — significant motion, for one — are wake-up only and must not vanish. It keys on
  `SensorFacts.type` and `wakeUpSensor`, not on vendor or name strings, which OEMs spell differently
  ("… Wakeup" vs the same name twice), and it is a nested scan over a few dozen entries rather than a
  `HashSet`, so it allocates nothing. `SensorCollector` now reports it as "Sensors enumerated", and
  `DefaultScoreEngine` uses the same count for its "Sensors enumerated" score input and its `< 4`
  threshold — the two screens said 40 and 22 about the same device before. Sensor detail rows, the
  per-category rows and the `SensorFacts` list are untouched, so the enumeration, the report JSON and
  every other derived value are unchanged; only the two counts move, and only on devices that publish
  wake-up variants. Seven tests added (`SensorInventoryTest` ×6: pair collapsed, wake-up-only type kept,
  two chips of one type kept at two, wake-up handle dropped wherever it is enumerated, empty list,
  all-ordinary list; `ScoreEngineTest` ×1: 4 handles = 2 sensors = "2 sensors" and below the
  four-sensor threshold). `assembleDebug` and all 222 unit tests green (215 before). No screen, control,
  style, layout or copy change. Not measured on hardware: no device is available here, so the
  before/after is the fixture case (4 handles → 2 sensors), not a handset reading.
- [ ] **H5. Megapixel source precedence.** When the stream configuration and the pixel array disagree,
  document which one is reported and why, and expose the other value.
  *Skipped (6 Nov, second pass):* "expose the other value" means a new row or a second value on the
  camera screen — a visible change. The precedence itself is worth documenting in the code later.
  *Passed over (6 Nov, fourth pass):* unchanged — "expose the other value" is a second value on the
  camera screen.
  *Passed over (7 Nov run):* unchanged — "expose the other value" is a second value on the camera
  screen.
  *Passed over (8 Nov run):* unchanged — "expose the other value" is a second value on the
  camera screen.
  *Passed over (8 Nov, second pass):* unchanged — same reason.
  *Passed over (9 Nov run):* unchanged — "expose the other value" is a second value on the camera screen.

- [x] **H6. Replace deprecated `getRealMetrics`.** Use `WindowMetrics` on API 30+ with the old path as
  fallback; behaviour must stay identical on every API level. *Done:* `HardwareCollector.readDisplay`
  no longer calls `Display.getRealMetrics` above API 29. A new private
  `readRealMetrics(windowManager, display, out)` takes the pixel rectangle from
  `WindowManager.getMaximumWindowMetrics().bounds` — the documented replacement, which reports the same
  full panel, system-bar area included, in physical pixels — and keeps the deprecated call for API 26-29
  and as the fallback when the window metrics are absent, zero-sized or throw. Density, xdpi and ydpi
  still come from `context.resources.displayMetrics`: the same `DisplayMetrics` fields of the same
  default display, from a source deprecated on no level. They have to, because `WindowMetrics.getDensity()`
  is API 34+ and xdpi/ydpi are not on `WindowMetrics` at all — a first version that read the density from
  `WindowMetrics` compiled cleanly and threw `NoSuchMethodError` on API 30-33, which the Robolectric run
  caught and the compiler could not. The window-metrics answer is taken only when those resource fields
  are readable, so no device loses a value it reported before. Two tests in
  `HardwareCollectorWindowMetricsTest`: API 33 reports the maximum-window-metrics rectangle and the
  resource density, and API 29 still reports exactly what `getRealMetrics` gives — 320x470 px at 160 dpi
  under Robolectric, byte-identical to the value before the change. Not verified on hardware, and the
  API 30+ branch itself is **not** exercised by the test run: Robolectric's `WindowManager` reports a
  0x0 maximum rectangle, so every test here takes the fallback path and the new branch is verified by
  inspection only. `assembleDebug` green with 0 compiler warnings; all 224 unit tests pass (222 before).
  No screen, control, style, layout or copy change.
- [ ] **H7. VPN-aware transport reporting.** With a VPN active, report the underlying transport as well
  as the VPN instead of only "VPN".
  *Skipped (6 Nov, third pass):* the only way to report the underlying transport is a new row or a
  changed value in the connectivity list — a visible change. It also needs `NetworkCapabilities`
  observed under a live VPN, which nothing here can exercise.
  *Passed over (6 Nov, fourth pass):* unchanged — still a new row, and still no live VPN to observe.
  *Passed over (7 Nov run):* unchanged — a new row in the connectivity list, and still no live VPN
  to observe.
  *Passed over (8 Nov run):* unchanged — a new row in the connectivity list, and still no live
  VPN to observe.
  *Passed over (8 Nov, second pass):* unchanged — same reason.
  *Passed over (9 Nov run):* unchanged — a new row in the connectivity list, and still no live VPN to
  observe.

- [x] **H8. Count packages once.** `getInstalledPackages` is called and walked twice in places; derive
  system and user counts from a single pass. *Already done:* `getInstalledPackages(0)` is called once
  in `main`, in `SoftwareCollector.collect`, and the single pass over that list increments both the
  system and the user counter. There is no second call and no second walk left to merge.
- [ ] **H9. Verified-boot fallbacks.** Fall back to `ro.boot.veritymode` when
  `ro.boot.verifiedbootstate` is absent, and say which property answered.
  *Skipped (6 Nov, third pass):* the fallback alone would be invisible on devices that publish
  `ro.boot.verifiedbootstate`, but the item also asks to "say which property answered", which rewrites
  the `security.verifiedBoot` source line and the integrity check's explanation and evidence on the
  security screen — a copy change. The copy-free half could be split out.
  *Passed over (6 Nov, fourth pass):* unchanged — the fallback alone buys nothing on devices that
  publish `ro.boot.verifiedbootstate`, and "say which property answered" is copy.
  *Passed over (7 Nov run):* unchanged — "say which property answered" is copy, and the fallback
  alone changes nothing on a device that publishes `ro.boot.verifiedbootstate`.
  *Passed over (8 Nov run):* unchanged — "say which property answered" rewrites the security
  screen's source line and copy, and the fallback alone changes nothing on a device that publishes
  `ro.boot.verifiedbootstate`.
  *Passed over (8 Nov, second pass):* unchanged — same reason.
  *Passed over (9 Nov run):* unchanged — "say which property answered" is copy.

- [x] **H10. Locale audit.** Every `String.format` must pass `Locale.US`; a device set to Arabic or
  Hindi digits currently renders numbers inconsistently in the report. *Already done:* every
  `String.format` in `main` passes `Locale.US` explicitly — all 35 sites, no exceptions.
  The unlocalised `.format` calls that remain are `SimpleDateFormat.format(Date)` on formatters that
  were themselves built with `Locale.US`, plus `"%02X".format(byte)` in the certificate digest, which
  is not locale-sensitive: checked on JDK 17 that `%X` stays ASCII under `ar-EG`,
  `hi-IN-u-nu-deva` and `th-TH-u-nu-thai` while `%d` and `%.1f` switch to native digits.
- [ ] **H11. Per-volume storage.** Use `StorageManager` to report internal versus adoptable volume free
  space instead of only the data partition.
  *Passed over (6 Nov, fourth pass):* reporting a second volume means new rows ("Internal"/"Adoptable")
  in the storage group — a visible change. The copy-free half would be reporting only the primary
  volume, which is exactly what it already does.
  *Passed over (7 Nov run):* unchanged — a second volume means new rows in the storage group.
  *Passed over (8 Nov run):* unchanged — a second volume means new rows in the storage group.
  *Passed over (8 Nov, second pass):* unchanged — same reason.
  *Passed over (9 Nov run):* unchanged — a second volume means new rows in the storage group.

- [x] **H12. Per-core CPU frequencies.** Read max frequency for every core, not just `cpu0`, and report
  the cluster layout when it is readable. *Done (frequency half; cluster layout dropped):* `cpu0` is a
  LITTLE core on every ARM big.LITTLE/DynamIQ part, so the old `cpu0/cpufreq/cpuinfo_max_freq` lookup
  answered 1.80 GHz for a chip whose prime core runs 2.84 GHz — the field said "Maximum CPU
  frequency" and reported a cluster ceiling. New `data/android/collect/CpuFrequency.kt` scans every
  `cpuN` directory under `/sys/devices/system/cpu`, takes the largest positive `cpuinfo_max_freq` and
  skips a core whose node is absent (offline), blank, `0` or non-numeric instead of failing the whole
  read, so one odd core cannot take the value away from the others. On a homogeneous part it returns
  the same number as before, which is why the change is invisible there. `HardwareCollector` calls it
  instead of its private `readCpuMaxFreq()`, and the field's source annotation became
  `/sys/devices/system/cpu/cpu*/cpufreq/cpuinfo_max_freq` so the line no longer claims a `cpu0`-only
  read — the one rendered text delta in this commit, a source path, not a label, note or value format
  (H1 set the same precedent when it reported the property that actually answered). Nine tests in
  `CpuFrequencyTest` over fixture directories shaped like the real sysfs: homogeneous part, the
  big.LITTLE regression (cpu0 1.8 GHz, cpu7 2.84 GHz → 2,841,600 kHz), directory-order independence,
  an offline core without a `cpufreq` node, blank/`0`/non-numeric values, no usable value, a missing
  root, non-core entries (`cpufreq`, `cpuidle`, `cpuonline`, `online`) and a directory named like the
  node. Before/after is the fixture case, not a handset: no device is available here, and this
  sandbox's own `/sys/devices/system/cpu` publishes no `cpufreq` nodes at all. The cluster-layout half
  of the item is dropped, not deferred quietly: per-cluster rows are new rows on the processor
  screen. `assembleDebug` green, APK 9,811,608 bytes (unchanged), all 233 unit tests pass (224 before,
  9 new), `tools/check-no-ui-change.sh` clean.
- [ ] **H13. Elapsed-realtime distinction.** Report both `elapsedRealtime` (includes deep sleep) and
  `uptimeMillis` (awake only) so uptime is unambiguous.
  *Passed over (6 Nov, fourth pass):* a second uptime value is a second row (or a changed label) on the
  overview and in the report — a visible change. The copy-free part, renaming the misleading internal
  `uptimeMillis` field, is a rename with no behaviour to test.
  *Passed over (7 Nov run):* unchanged — a second uptime value is a second row.
  *Passed over (8 Nov run):* unchanged — a second uptime value is a second row on the overview.
  *Passed over (8 Nov, second pass):* unchanged — same reason.
  *Passed over (9 Nov run):* unchanged — a second uptime value is a second row on the overview.

- [ ] **H14. Reference codenames.** Add codename patterns to the reference entries so matching works
  for builds whose `Build.MODEL` is a carrier variant.
  *Skipped (6 Nov, fifth pass):* the mechanism is already there — `ReferenceIndex.match` takes the
  device string and matches a pattern that equals it (`normalisedDevice == candidate.key`) as well as
  one contained in the model — so what is missing is data, not code: 16 entries would each need the
  codenames of every carrier variant. This set is curated from manufacturer specification pages and
  every entry records its own `sourceUrl` and `sourceDate`; codenames are not on those pages, so
  adding them from memory would put unsourced identifiers into a set whose whole point is that each
  entry can be checked. Re-scope as "add codenames for the entries whose codename is documented on
  the recorded source", or add a source for codenames first.
  *Passed over (8 Nov, second pass):* unchanged — still data the app does not carry and cannot source.
  *Skipped (7 Nov run):* unchanged — this is missing data, not missing code, and the codenames are
  still not on the recorded `sourceUrl` pages.

  *Passed over (8 Nov run):* unchanged — missing data, not missing code; the codenames are still
  not on the recorded `sourceUrl` pages.
  *Passed over (9 Nov run):* unchanged — still data the app does not carry and cannot source.

## I. Tooling and tests that protect the work

- [x] **I1. Reference-loader test.** Feed a malformed entry and assert the rest of the set still loads.
  *Already done:* `ReferenceAssetParserTest`, 7 tests — a string, `null`, a nested array and a bare
  number between good entries; a wrong-typed field defaulted rather than dropping the entry; the entry
  after a bad element keeping every field; absent numerics keeping their defaults; four malformed
  documents yielding an empty list; and the bundled asset parsing all 16 entries.
- [x] **I2. Formatter edge-case tests.** Zero, negative, exactly 1023/1024, and very large byte counts.
  *Already done:* `FormattersTest` pins zero, negative, 1023, 1024, 1_048_575/6, `Long.MAX_VALUE` and
  `Long.MIN_VALUE` as literal strings, then sweeps 0…200 000, ±2 000 around every power of 1024,
  ±300 around 41 powers of two and 20 000 random values in each direction against the old
  `String.format` implementation as the oracle.
- [x] **I3. Status exhaustiveness guard.** A unit test asserting every `DiagnosticStatus` and
  `ComponentState` maps to a colour and a label, so a future enum value cannot ship unrendered.
  *Already enforced:* `statusColor` and `statusLabel` (`ui/theme/Theme.kt`) and `stateColor`
  (`ui/screens/PartsScreen.kt`) are exhaustive `when` expressions over the enums, so adding a
  `DiagnosticStatus` or `ComponentState` constant is a compile error rather than an unrendered screen.
  A test here could only restate what the compiler already rejects.
- [x] **I4. `tools/check-no-ui-change.sh`.** Diffs `ui/` and `res/` against a reference commit and exits
  non-zero if they changed — the hard rule becomes enforceable instead of aspirational. *Done:* new
  `tools/check-no-ui-change.sh` (mode 100755, no SDK, Gradle or network needed). It resolves the repo
  from its own path, so it works from any directory, and compares two paths —
  `app/src/main/kotlin/com/verifydeviceinfo/ui` and `app/src/main/res` — against a reference that
  defaults to `HEAD` and can be given as `$1` or `UI_BASELINE` (e.g. `origin/main` for a branch).
  Tracked changes (staged and unstaged) come from `git diff --name-only <ref>` and untracked files
  from `git ls-files --others --exclude-standard`, so a new screen file that has not been added yet is
  caught too. Exit 0 prints the count of tracked files under those paths, exit 1 lists every offending
  file plus the campaign rule, exit 2 refuses to answer when it cannot compare (not a git checkout, or
  an unknown reference). Six scenarios were run against this checkout: clean tree → 0; an appended
  line in `ui/theme/Theme.kt` → 1 naming that file; a new untracked `res/values/probe.xml` → 1; a
  change to `docs/LIMITATIONS.md` only → 0; `no-such-ref` → 2; `HEAD~1` → 0. A copy of the script in a
  directory that is not a git checkout → 2. `docs/BUILDING.md` gained a "UI freeze check" section
  documenting the three invocations. Tooling and docs only: no production file, no `ui/` or `res/`
  file touched, so `assembleDebug` output is unchanged (`app-debug.apk` 9,811,608 bytes) and all 224
  unit tests pass.
- [x] **I5. `tools/measure.sh`.** Records APK size, method count and a timed debug build into
  `docs/PERFORMANCE.md`, so every claim of "faster" has a number behind it. *Done:* new
  `tools/measure.sh` (mode 100755, `unzip` and `python3` only — no SDK tool, no Gradle needed unless
  asked). It reads the APK's size, then sums `method_ids_size` and `class_defs_size` from the DEX
  headers of every `classesN.dex` (min SDK 26 packages native multidex, so this build ships 12), and
  appends one fixed-column row to `docs/PERFORMANCE.md`: date, APK name, bytes, methods, classes, dex
  files, dex bytes, build seconds. `--build` times `gradle assembleDebug` first and fills the last
  column; `--apk <path>` measures the release artefact instead; `--print` prints the row without
  writing; a missing APK exits 2 with the command to build one, so a run that measured nothing cannot
  record a zero. First measured row, debug variant on this checkout: **9,811,608 bytes, 102,504
  methods, 14,071 classes, 12 dex files, 25,462,860 bytes of dex**; the method total was
  cross-checked against `dexdump` from build-tools 35.0.1 and agrees exactly. `docs/PERFORMANCE.md`
  is new: the table plus what the baseline row means, how to reproduce it, and why the build-seconds
  column is host-specific while the size and count columns are not. `assembleDebug` green, APK
  9,811,608 bytes (unchanged — tooling and docs only), all 233 unit tests pass,
  `tools/check-no-ui-change.sh` clean.
- [x] **I6. Redaction test.** Assert that a report with a serial number, a Bluetooth name and a
  certificate digest contains none of them when `redacted = true`. *Already done:* `ReportJsonTest`
  carries `no raw identifier survives in a redacted export` (encodes the fixture with
  `redacted = true` and asserts the serial, the Bluetooth name and the certificate digest appear
  nowhere in the text), `an unredacted export keeps the identifiers` (the same three strings are
  present when redaction is off, so the first test cannot pass by exporting nothing) and
  `a null serial stays null when redacted`, plus `redaction replaces exactly the serial and the
  flagged specification values`. Nothing was missing, so nothing was added.
- [x] **I7. GitHub Actions CI.** Build and test on every push so a regression is caught by the
  repository itself, not only by a nightly run. *Done:* new `.github/workflows/ci.yml` (one job,
  `ubuntu-latest`, 60-minute timeout) runs on every push to `main`, every pull request and
  `workflow_dispatch`, with `concurrency` cancelling a superseded run and `permissions: contents:
  read` — it needs nothing to write. It installs the toolchain with the repository's own
  `tools/bootstrap-build.sh` (JDK 17, Gradle 8.9, Android SDK 35) into `$RUNNER_TEMP/build` and
  republishes its exports through `$GITHUB_ENV`/`$GITHUB_PATH`, so CI builds through exactly the path
  `docs/BUILDING.md` documents instead of a second, drifting set of commands. It unsets
  `JAVA_TOOL_OPTIONS` before building, because the small class-space ceiling some images export is
  what starves D8 and kotlinc (the same trap recorded under F1). The two gates are the two gates used
  locally, `gradle assembleDebug` and `gradle testDebugUnitTest`; the toolchain, SDK and Gradle caches
  are cached under a key built from `tools/bootstrap-build.sh` and the four build files, so a
  toolchain change invalidates them rather than silently reusing a stale one. The JUnit report and
  test results are uploaded `if: always()` and the debug APK as an artefact (7-day retention).
  `docs/BUILDING.md`'s "CI sketch" section, which described a workflow that did not exist, is
  replaced by a "Continuous integration" section describing this one. Verified here: the YAML parses
  and its `on`/step list are as intended, and the toolchain step was executed for real in this sandbox
  against a stub `$GITHUB_ENV`/`$GITHUB_PATH` — it exports `JAVA_HOME`, `ANDROID_HOME`,
  `ANDROID_SDK_ROOT`, `GRADLE_USER_HOME` and three PATH entries, and `gradle` then resolves from that
  PATH. **Not verified: the workflow has never run on a GitHub runner** — this sandbox cannot reach
  Actions, so the checkout/cache/upload steps and the runner's own limits are unexercised. Build and
  tests green locally: `assembleDebug` succeeded, `testDebugUnitTest` passed all 233 tests, APK
  unchanged at 9,811,608 bytes, `tools/check-no-ui-change.sh` clean (no `ui/` or `res/` file touched).
- [x] **I8. Lint triage.** Run `gradle lint`, fix real findings, suppress the rest with a written reason.
  *Done (16 Nov, second pass) — covered by F3:* this item asks for exactly what F3 ran today. The
  complete report (6 errors, 13 warnings, 13 issue types) was triaged issue by issue; the real
  defects were fixed (`SecurityCollector`'s API-30 biometric call behind an API-29 guard, the
  missing `USE_BIOMETRIC` declaration), and every remaining finding is either fixed or suppressed in
  `app/lint.xml` with its reason written beside it. Nothing was left untriaged, so there is nothing
  this item can add; ticked as covered rather than re-run.
  *Passed over (7 Nov run):* unchanged — same constraint as F3: `lintAnalyzeDebug` does not finish
  in one 300 s call on 2 cores.
  *Passed over (8 Nov run):* unchanged — same constraint as F3.
  *Passed over (8 Nov, second pass):* unchanged — same constraint as F3.
  *Passed over (9 Nov run):* unchanged — same constraint as F3.
  *Passed over (10 Nov run):* unchanged — same constraint as F3.

- [x] **I9. Screenshot non-blank assertion.** Fail the render test if an image comes back uniform.
  *Already done:* `ScreenshotTest` measures every capture (distinct colours and ink fraction) and
  fails with the measured numbers when a screen comes back blank, and `aBlankFrameIsRejected` renders
  an empty frame and asserts the check rejects it — so the seven screen assertions cannot pass for a
  reason unrelated to the screen having been drawn. Recorded in the E-section entry above; the
  checkbox was simply never ticked.
- [x] **I10. Dependency audit.** Record the resolved dependency tree and flag anything unexpectedly
  large.
  *Done (F5) — duplicate:* the release runtime classpath was resolved and audited under F5 (149
  modules, every top-level entry mapped to a declared dependency) and re-measured on 10 Nov; a
  second recorded tree would be the same tree. The dependency-*upgrade* decision that `app/lint.xml`
  points here for is a separate call (no device is available to re-test an upgrade), not what this
  item asks.
  *Passed over (7 Nov run):* F5 already resolved and audited the release classpath (149 modules,
  every top-level entry mapped to a declared dependency), so a second recorded tree would only
  duplicate it. Re-scope as per-artefact byte sizes if that is what is wanted.

  *Passed over (8 Nov run):* unchanged — F5 already resolved and audited the release classpath
  (149 modules), so a second recorded tree would only duplicate it.
  *Passed over (8 Nov, second pass):* unchanged — same duplication.
  *Passed over (9 Nov run):* unchanged — F5 already resolved and audited the release classpath.

## J. Audit findings — 7 November run

The twelve items left open above are all either blocked by this sandbox (F1, F3, I8: 2 cores and
2,098 MB, where a cold `assembleDebug` alone needs seven 300 s calls and `minifyReleaseWithR8` and
`lintAnalyzeDebug` are not resumable), duplicates of work already recorded (G1, I10), or they need a
visible change (H2, H5, H7, H9, H11, H13) or data the app does not carry (H14). So the list was
re-read against the code, as "When the list runs out" asks, and the three findings below were added.
J1 was implemented in this run.

- [x] **J1. A leftover probe key disables the hardware-keystore evidence for good.**
  `SecurityCollector.probeKeystore` generates a throw-away AES key under the fixed alias
  `verify_device_info_probe`, reads `KeyInfo.isInsideSecureHardware` and the certificate chain, and
  then deletes the key. The delete was not in a `finally` and nothing cleared the alias *before*
  generating, so any probe interrupted between `generateKey()` and `deleteEntry()` — process death
  mid-scan, or an exception from `getKeySpec`/`getCertificateChain` — left that key in the user's
  keystore permanently. A keystore refuses to overwrite an existing alias, so `generator.init(spec)`
  then threw on *every* later scan, the surrounding `catch` turned it into `null to false`, and the
  app reported "Hardware-backed keystore: Unavailable" and "Hardware attestation: Not available"
  for the life of that install, with nothing on screen to say why. One bad probe, then: permanent
  silent loss of two security facts, plus a key left in the user's keystore. *Done:* the
  generate-read-delete body moved behind a new
  `withThrowAwayKey(keyStore, alias) { … }` (`data/android/collect/SecurityCollector.kt`)
  that deletes `alias` before the block runs and again in a `finally`, ignoring a failed delete
  because a store that cannot delete must not cost the reading. The alias is now a
  `PROBE_ALIAS` constant. Nothing else changed: same key parameters, same two values read, same
  field text, same statuses, so a device whose probe succeeds reports exactly what it reported
  before — the change only affects a device that was already reporting nothing. Six tests in
  `SecurityProbeKeyTest` run the helper against a plain JVM `PKCS12` keystore (no device, no
  Robolectric): a stale entry is gone when the block starts, the key the block creates is gone when
  it returns, it is gone when the block throws, a stale key does not survive a block that fails
  before generating its own, the block's value is returned unchanged, and an absent alias is not an
  error. Verified that the test catches the bug: with the pre-clear line removed, `a key left behind
  by an earlier probe is removed before the probe runs` fails with the alias still occupied;
  restored, it passes. `assembleDebug` green, no compiler warnings in this run's build logs; all 239
  unit tests pass (233 before, 6 new); `tools/check-no-ui-change.sh` clean. APK: 9,910,289 bytes
  for the previous source and 9,910,999 for this one, rebuilt back to back on this host —
  **+710 bytes**, the code alone. (The same source built cold at the start of this run produced
  9,811,608 bytes, so the debug APK is not
  byte-reproducible across builds on this toolchain; the back-to-back pair is the honest comparison.)
  Not verified on a device: the sticky-failure path needs a real keystore, so what is pinned here is
  the helper's contract, and the call site is verified by inspection.
- [x] **J2. `PdfReportGenerator` lowercases the spec label once per comparison.**
  `report.snapshot.fields.sortedBy { it.label.lowercase() }` (line 82) calls the selector O(n log n)
  times, allocating a fresh lowercased string on both sides of every comparison — a few
  thousand throw-away strings for the A–Z section of one export — and `lowercase()` uses
  the default locale, so a device set to Turkish sorts `I` as `ı` and the report's A–Z section comes
  out in a different order. *Change:* decorate-sort-undecorate (map to `label.lowercase(Locale.US)`
  once, sort on that key, map back), which keeps the sort stable and therefore byte-identical for
  every non-Turkish locale. *Test:* a fixture with mixed-case labels asserts the same order under
  `tr-TR` and `en-US`; count the selector calls to prove it runs once per field.
  *Done (7 Nov, second pass) — with a correction to this item's premise:* the allocation half was
  real, the locale half was not. Kotlin's `String.lowercase()` is the **invariant** mapping
  (`toLowerCase(Locale.ROOT)`), not the default-locale one, so the "Turkish `I` sorts as `ı`"
  claim above is wrong: the A–Z section never depended on device locale and nothing needed
  fixing there. *Change:* the sort moved into `List<SpecField>.alphabeticalByLabel(labelOf)`, a
  top-level internal function in `data/android/PdfReportGenerator.kt`, which builds the lowercased
  key **once per field**, sorts on those keys with the stable `sortedWith(compareBy { it.first })`
  and maps back. The key still uses invariant `lowercase()`, and the sort is still stable, so the
  rendered order is identical to before on every locale; the call site is now
  `.alphabeticalByLabel()`. *Measured* (selector evaluations counted on the JDK's TimSort, the
  sort Kotlin's `sortedBy` uses): 10 fields 46 → 10, 50 fields 446 → 50, 60 fields 566 → 60,
  100 fields 1,078 → 100, 1,000 fields 15,484 → 1,000 — i.e. two lowercased strings per
  comparison (O(n log n)) down to one per field. Debug APK rebuilt back to back on this host:
  9,874,112 bytes before, 9,874,853 after, **+741 bytes** (the helper plus its call; the debug APK
  is not byte-reproducible across builds, so the back-to-back pair is the honest comparison).
  *Tests:* six in `PdfReportAlphabeticalOrderTest` — order identical to the old selector, order
  unchanged with the default locale forced to `tr-TR` (a regression guard should anyone later
  switch to a default-locale lowercase), the key function called exactly once per field (counted),
  the old selector shown to run more than 4n times on the same fixture, equal keys keeping their
  input order (stability), and an empty list staying empty. `assembleDebug` green, all 245 unit
  tests pass (239 before, 6 new), `tools/check-no-ui-change.sh` clean — no file under `ui/` or
  `res/` touched.
- [x] **J3. `Formatters.date`/`dateOnly`/`utcDate` build a `SimpleDateFormat` per call.** Twelve call
  sites, including one per history row (`HistoryScreen`) and per row of the PDF, so a 50-entry
  history render constructs 50 formatters, each with its own `Calendar` and locale data. *Change:*
  hold one formatter per format string in a `ThreadLocal` (they are not thread-safe and the call
  sites are on both the main thread and `Dispatchers.IO`), keeping the same patterns, `Locale.US` and
  the `utcDate` time zone so every rendered string is unchanged. *Test:* the existing
  `FormattersTest` strings must stay identical, plus a two-thread test that the cached formatter
  never returns another thread's date.
  *Done (7 Nov, third pass):* the three formatters now live in a private `DateFormats` holder kept in
  one `ThreadLocal` (`core/util/Formatters.kt`), built on first use per thread; `date`/`dateOnly`/
  `utcDate` are one `format` call each on the cached instance. Patterns, `Locale.US`, the quoted
  `'UTC'` literal and the `utcDate` zone are unchanged, and the `<= 0` → `"Unavailable"` guard is
  untouched, so every rendered string is the same. One thing the item did not name: a cached
  formatter freezes the time zone it was built with, and a *new* `SimpleDateFormat` picks up the
  current default zone, so a cache alone would keep rendering local times in the old zone after the
  user travels or changes it in Settings, until the process restarted. `formats()` therefore
  compares `TimeZone.getDefault()` against the zone the holder was built with and re-points
  `dateTime`/`dateOnly` when it differs; `utc` is pinned to UTC and never re-pointed. That check is
  the whole cost of the correctness fix — measured below, ~7 ns.
  *Measured* (JDK 17, 2,000,000 formatted calls, best of 5 rounds, 64 timestamps; the same JVM the
  unit tests run on, not a device): `new SimpleDateFormat(pattern, Locale.US).format(..)` per call
  **2,006.3 ns** and **2,256 bytes allocated** per call; cached per thread **886.3 ns** and **688
  bytes** — **2.26× faster, 1,568 fewer bytes per call**; with the default-zone check **892.9 ns**,
  i.e. the check costs **6.6 ns, 0.7%**. A 50-row history render therefore goes from **100.3 µs to
  44.3 µs** of formatting. Outputs verified identical to the old implementation on all 64
  timestamps before the code was changed. No APK size claim: this run's pre-change and post-change
  debug builds were 9,811,608 and 9,875,628 bytes, a 64 KB gap that is far larger than one small
  class and matches the non-reproducibility already recorded under J1/J2, so the pair says nothing
  about this change.
  *Tests:* nine new cases in `FormattersDateTest` (the file had no date assertions at all before
  this). Pinned strings for all three formats with the default zone fixed to `Asia/Kolkata`;
  `utcDate` unchanged across three different default zones; the `null`/`0`/`-1`/`Long.MIN_VALUE`
  `"Unavailable"` guard; **the default-zone change is followed** (Kolkata → New York → UTC, which is
  exactly what a naive frozen cache would fail); consecutive calls do not carry one date into the
  next; eight threads formatting their own timestamp 2,000 times each behind a `CountDownLatch`,
  asserted against single-threaded references — the guard against a shared, non-thread-safe
  formatter; and month names unchanged with the default locale forced to `tr-TR`. Verified the
  zone test catches the bug: with the re-pointing removed, `localDatesFollowAChangeOfDefaultZone`
  fails on the New York assertion; restored, it passes. `assembleDebug` green with **0 compiler
  warnings** (the first attempt inferred `ThreadLocal<DateFormats?>` from `withInitial` and emitted
  four nullability warnings, so the holder is initialised explicitly instead); all **254** unit
  tests pass (245 before, 9 new). No file under `ui/` or `res/` touched — a whole-tree `diff -r`
  against a fresh copy of `main` shows exactly two changed paths, `Formatters.kt` and the new test.

## K. Audit findings — 7 November run, fourth pass

The twelve items still open above are the same twelve the third pass left: three blocked by this
sandbox (F1, F3, I8), two duplicates of recorded work (G1, I10), six that need a visible change
(H2, H5, H7, H9, H11, H13) and one that needs data the app does not carry (H14). Nothing in them
changed today, so the code was re-read against the same lens — error paths that could be silent,
work that could be lost, missing tests around a path that rewrites persistent state — and the three
findings below were added. K1 was implemented in this run.

- [x] **K1. One damaged history-index entry destroys the whole history and deletes every stored
  report.** `FileDiagnosticHistory.readIndex` parsed the index with `array.getJSONObject(i)` and
  `obj.getString("id")` inside a single `try`, and any exception made it return `emptyList()`. The
  index is *rewritten from whatever the read produced* on every save, and the save then prunes every
  report file that has no index entry — so one unusable entry (a missing or blank `id`, a
  `timestampMillis` that is not a number, a non-object element, a truncated tail) was read as "no
  reports are kept", the next save wrote an index containing only the new report, and all 49
  previous reports were deleted from app-private storage, with the History screen showing one entry
  and nothing anywhere to say what happened. *Change:* parsing moved to two pure functions,
  `parseHistoryIndex(text)` and `parseHistoryEntry(obj)`, which skip an unusable entry instead of
  taking the other entries with it and return `null` — distinct from `emptyList()` — when the
  document itself is not a JSON array. `readIndex()` now returns `List<HistoryEntry>?`, `list()`
  maps `null` to an empty list, and `pruneReports` takes an `indexReadable` flag: `staleReportFiles`
  returns nothing to delete when the index could not be read, so a corrupt index can no longer be
  mistaken for an empty one. The distinction is deliberately one save deep — the rewritten index is
  valid again from that save on, and the *next* save prunes the files the recovered index does not
  mention — so the durable protection is the per-entry tolerance, not the flag; the flag only stops
  the same save from doing the deleting. A healthy index parses to exactly the same entries, in the
  same order, so nothing on screen changes.
  *Tests:* ten new cases in `HistoryIndexParseTest` (Robolectric, for its real `org.json`): an entry
  missing its `id` does not take the other entries with it; a blank `id` makes only that entry
  unusable; non-object elements are skipped; a wrong-typed field (`timestampMillis` as a string,
  `riskLevel` as a number) is defaulted rather than dropping the entry; every field the index writer
  emits reads back unchanged; an absent score stays absent instead of becoming zero; an unreadable
  document is unknown, not an empty history (`""`, prose, a bare object, an unterminated array); an
  empty array is an empty history; nothing is pruned when the index could not be read; and pruning is
  unchanged when it was read. The pre-fix behaviour of the first case is visible in the removed code
  — `getString("id")` threw, the single `catch` returned `emptyList()` — but a revert-and-rerun cycle
  was not run this time (a full compile plus suite is ~15 minutes on this host); the tolerance is
  asserted directly instead. `assembleDebug` green with no compiler warnings, all **264** unit tests
  pass (254 before, 10 new), `tools/check-no-ui-change.sh` clean — the whole diff is
  `FileDiagnosticHistory.kt` plus the new test. Debug APK 9,827,992 bytes; no size claim, because the
  debug APK is not byte-reproducible on this toolchain (recorded under J1/J2) and no baseline was
  rebuilt this run.
- [x] **K2. `PdfReportGenerator.toPdf` never finishes its last page and never closes the document on
  the error path.** `PageWriter.close()` — the only caller of `finishPage` for the final page — was
  dead code: nothing called it, so the last page was left open when `document.writeTo(…)` ran.
  `PdfDocument` refuses to write or close while a page is current
  (`throwIfCurrentPageNotFinished`), and `document.close()` was not in a `finally`, so any exception
  between `PdfDocument()` and the end leaked the native document and left a half-written
  `verify-device-info-report.pdf` in the cache.
  *Change:* the drawing moved into `PageWriter.render(report)` and the file lifecycle into
  `writePdfFile(file, build, finishLastPage, writeTo, closeDocument)`, which finishes the last page
  **before** `writeTo`, writes through a `use`, closes the document on every path in a `finally` —
  finishing a page a failed build left open first, because `close()` refuses otherwise — and deletes
  the target file when the export fails, so a truncated report cannot be shared as a whole one.
  Cleanup failures are swallowed so they cannot replace the exception already in flight. The cache
  file name moved to a companion constant. The effect is not cosmetic: before this, every export
  threw `IllegalStateException: Current page not finished` at `writeTo` and no PDF was ever
  produced — the Report screen's "Export PDF" only ever showed its failure message.
  *Tests:* seven in `PdfWriteLifecycleTest`, a plain JVM test: the last page is finished before
  `writeTo`; a completed export returns the file it wrote and the bytes it wrote; the document is
  closed exactly once and last on the success path; a throwing build still closes the document and
  rethrows the original failure; a throwing build leaves no file behind; a throwing write leaves no
  partial file; and a cleanup failure cannot replace the failure being reported. Robolectric cannot
  stand in for `PdfDocument` — its native handle is always 0, so the first `startPage` throws
  "document is closed!" — which is why the drawing and the lifecycle are separated and only the
  lifecycle is driven here. **Not verified on a device:** the real `PdfDocument` round trip is
  exactly what the fix restores and nothing in this sandbox can run it, so what is pinned is the
  ordering and the cleanup the document depends on.
  `assembleDebug` green, all **271** unit tests pass (264 before, 7 new). Debug APK 9,893,794 bytes
  against 9,827,992 for this run's pre-change build — a 64 KB gap for one small helper, the same
  non-reproducibility recorded under J1/J2, so no size claim. No UI, copy or layout change: the
  whole diff is `PdfReportGenerator.kt` plus the new test.
- [x] **K3. `HardwareCollector.guessGpuVendor` compares a lowercased string with a mixed-case
  literal.** `lower.contains("rDNA")` can never be true, because `lower` came from
  `renderer.lowercase()`: the RDNA half of the `"AMD (Samsung Xclipse)"` branch is dead. *Change:*
  compare against `"rdna"`. *Test:* a fixture table of renderer strings and their expected vendors.
  This changes one rendered value — the "GPU vendor" row reads `Unknown` today on a renderer string
  that only names RDNA — so it needs the same sign-off H1/H12 took; it is data, not copy, and only a
  device that reports nothing today reports something after.
  *Done (7 Nov, fifth pass):* the dead half is fixed and the matcher is now testable. The private
  `HardwareCollector.guessGpuVendor(renderer: String)` became a top-level `internal fun
  gpuVendorOf(renderer: String?)` in the same package, so the mapping can be pinned without a
  `Context` and without Robolectric, and the nullable parameter folds the `renderer?.let { … }` call
  site into the function. The needle is `"rdna"`. Proven before/after on the JDK itself:
  `"AMD RDNA 2".lowercase()` is `"amd rdna 2"`, for which `contains("rDNA")` is **false** (the old
  code) and `contains("rdna")` is **true** (the new one), so a renderer naming RDNA and nothing else
  went from `null` → "Unknown" on the GPU vendor row to "AMD (Samsung Xclipse)". Nothing else moves:
  every other needle was already lowercase, `"xclipse"` already matched, an unmatched renderer still
  returns `null`, and the returned strings — the row's copy — are unchanged. Sign-off taken on the
  same terms as H1/H12: this is a reported **value**, not copy, layout or a control, and only a device
  that says "Unknown" today says something after.
  *Tests:* six cases in the new `GpuVendorTest`, a plain JVM test: the exact regression
  (`"AMD RDNA 2"` → AMD); RDNA in five spellings and cases; Xclipse in three; a twelve-row renderer
  table covering every branch (`Adreno`/`kgsl` → Qualcomm, `Mali` → Arm, `PowerVR`/`Rogue` →
  Imagination, `Xclipse`/`RDNA` → AMD) with mixed case on both sides; five renderers that name no
  known vendor, which must stay `null` rather than become a guess; and `null`/blank input.
  `assembleDebug` green with no compiler warnings, all **277** unit tests pass (271 before, 6 new),
  `tools/check-no-ui-change.sh` clean — the whole diff is `HardwareCollector.kt` plus the new test.
  Debug APK 9,827,992 bytes; no size claim, since the debug APK is not byte-reproducible on this
  toolchain (J1/J2) and this run rebuilt no pre-change baseline.
  *Note for future audits:* this was the only mixed-case comparison against a lowercased string in
  `app/src/main` — every other `lowercase()` site (`SystemPropertyReader.getBooleanFlag`, the PDF
  label sort, the three UI `name.lowercase()` calls) compares against lowercase literals or only
  produces a key.

Second pass, 7 November: the twelve items above are unchanged — F1, F3 and I8 are still blocked by
this sandbox (2 cores, 2,098 MB: `minifyReleaseWithR8` and `lintAnalyzeDebug` do not finish in a
300 s call and neither is resumable), G1 and I10 are still duplicates of recorded work, H2, H5, H7,
H9, H11 and H13 still need a visible change, H14 still needs data the app does not carry, and K3
still needs the sign-off H1/H12 took. K2 was implemented in this run. Note for future runs: on this
host `compileDebugKotlin` alone takes ~4.5 min and `compileDebugUnitTestKotlin` ~4 min, so each
compile needs its own 300 s call; a single `assembleDebug` that spans a source change will not fit.

Fifth pass, 7 November: the twelve items above are unchanged — F1, F3 and I8 are still blocked by
this sandbox, G1 and I10 are still duplicates, H2, H5, H7, H9, H11 and H13 still need a visible
change and H14 still needs data the app does not carry. **K3 was implemented in this run**; its
sign-off question is settled by the H1/H12 precedent, and the change is a value correction in an
already-published row. One new build fact, worth more than the item: pinning Gradle to a single
worker makes this host usable. With the repo's `org.gradle.workers.max=2` on 2 cores, `mergeExtDexDebug`
was killed at 280 s on three consecutive `assembleDebug` calls and restarted from zero each time;
with `--max-workers=1` the same task finished in **2m18s**, and the whole `assembleDebug` then
completed in 3m30s and `testDebugUnitTest` in 3m35s. The two workers were not running in parallel
so much as thrashing a 2 GB machine. Future runs should pass `--max-workers=1` and
`-Dorg.gradle.jvmargs="-Xmx1750m -XX:MaxMetaspaceSize=448m …"`, unset the sandbox's
`JAVA_TOOL_OPTIONS`, and keep `--offline` for the main source sets only: the unit-test classpath
(`junit:junit:4.13.2` and the rest) is not in the cold cache, so `compileDebugUnitTestKotlin` must
run online once. Robolectric's runtime jars fetched normally from Maven Central this run (no 429).

Environment note for future runs: this sandbox's Maven Central hosts (`repo1.maven.org` and
`repo.maven.apache.org`) answered **429** with `Retry-After: 86025` during this run, which broke
`testDebugUnitTest` on all 64 Robolectric tests ("Failed to fetch maven artifact
org.robolectric:android-all-instrumented:15-…"). The jar from the run's first, successful fetch was
copied into `~/.m2/repository/org/robolectric/android-all-instrumented/15-robolectric-12650502-i7/`
and the suite then ran normally offline. `maven-central.storage-download.googleapis.com` serves the
same artefacts and is reachable when the two Maven hosts are not. *Confirmed again on 7 Nov:* both
Maven hosts answered 429 to Gradle itself, so the build ran with a local init script (never
committed) that prepends the Google mirror to `dependencyResolutionManagement` and
`pluginManagement`, and with `ROBOLECTRIC_DEPENDENCY_DIR` pointed at the five
`android-all-instrumented` jars (API 29, 30, 33, 34, 35) fetched from that mirror — the build file
already honours both. With that, `assembleDebug` and `testDebugUnitTest` run normally here.

---

## L. Audit findings — 7 November run, sixth pass

The twelve items open above are the same twelve the fifth pass left, and nothing in them changed:
F1, F3 and I8 are still blocked by this sandbox (2 cores, 2,098 MB — `minifyReleaseWithR8` and
`lintAnalyzeDebug` do not finish in a 300 s call and neither is resumable), G1 and I10 are still
duplicates of work already recorded, H2, H5, H7, H9, H11 and H13 still need a visible change, and
H14 still needs data the app does not carry. So the code was re-read again — error paths that could
be silent, work that could be lost, state that is written once and never released — and the three
findings below were added. L1 was implemented in this run.

- [x] **L1. A permission request that is abandoned, or that cannot be raised, is never forgotten —
  and a failed raise escapes into a screen coroutine that does not catch it.**
  `AndroidPermissionManager.request` kept `pending: MutableMap<Int, CompletableDeferred<…>>` and a
  `nextRequestCode` counter, inserted an entry, raised the request and awaited it. Nothing removed
  that entry except a delivered result, so two reachable paths leaked one per request for the life
  of the process: the awaiting coroutine being cancelled (the user leaves the microphone, camera or
  location test screen while the permission dialog is up — the three call sites in `AudioTestScreen`,
  `CameraTestScreen` and `MiscTestScreen` all launch it from a composition-scoped coroutine, which
  is cancelled when the screen goes), and `ActivityCompat.requestPermissions` throwing. The second
  was worse than the leak: the exception escaped into a coroutine that has no `try` around it, so a
  request that could not be raised surfaced as an unhandled coroutine failure rather than as "not
  granted". The counter and the map were also mutated outside any lock by a suspend function that
  can be resumed on any dispatcher, while answers always arrive on the main thread.
  *Change:* the bookkeeping moved into a new `data/android/PendingPermissionRequests.kt`, a
  synchronized holder that allocates the code and its deferred (`begin`), completes and forgets an
  answered code (`complete`, false when nothing is waiting), and drops a code that will never be
  answered (`abandon`). `request` now abandons its entry on cancellation and rethrows — a cancelled
  caller has nobody to resume, and inventing an answer would report a decision the user never
  made — and abandons plus returns `DENIED` when the request cannot be raised, so the caller sees a
  refusal instead of a crash. `onRequestPermissionsResult` ignores an answer for a code nothing is
  waiting on instead of returning early on a map lookup. Allocating the code under the same lock
  also means two racing requests cannot be handed the same code.
  *Tests:* nine in the new `PendingPermissionRequestsTest` (plain JVM — codes in order and counted,
  an answer resumes the caller and forgets the code, a refusal arrives as a refusal, an answer for
  an unknown code is ignored, a code is answered once and the second answer cannot overwrite the
  first, an abandoned request is forgotten and its deferred deliberately left incomplete,
  abandoning twice is harmless, two outstanding requests answered out of order stay independent,
  and 8 threads × 500 concurrent `begin` calls yield 4,000 distinct codes), plus two in
  `PermissionManagerTest` (Robolectric): a cancelled request leaves nothing pending and the answer
  the platform delivers afterwards is ignored, and an answer for an unknown code is ignored rather
  than throwing. **Not verified:** the "request could not be raised" branch is verified by
  inspection only — Robolectric's `ComponentActivity.requestPermissions` does not throw, so that
  catch cannot be driven from a test here. `assembleDebug` green with no compiler warnings, all
  **288** unit tests pass (277 before, 11 new), `tools/check-no-ui-change.sh` clean (31 files tracked
  under `ui/` and `res/`, none differs) — the diff is one new production file, one modified manager
  and two test files. Debug APK 9,895,564 bytes; no size claim, since the debug APK is not
  byte-reproducible on this toolchain (recorded under J1/J2) and no pre-change baseline was rebuilt
  this run. Nothing on screen changes: no label, value, row, screen or control is touched, and the
  permission outcome a user sees is the same on every path that already worked.
- [ ] **L2. The fingerprint coherence check claims to compare three segments and compares one.**
  `DefaultAuthenticityEngine` derives `fpProduct` and `fpDevice` from `AnalysisSupport.parseFingerprint`
  and then never uses them: `coherent` is `normalise(fpBrand) == normalise(identity.brand) ||
  brandsRelated(fpBrand, identity.brand)`. The VERIFIED finding's explanation nevertheless states
  that "the fingerprint's brand/product/device triple matches the reported brand, product and device
  properties", and its evidence line prints the product and device it did not check — so a build
  whose fingerprint names the right brand with a different product and device is reported as
  coherent with a sentence that says all three matched. *Change:* compare all three segments
  (normalised, with the same brand-family tolerance), and add a fixture table of fingerprints that
  agree on brand only. *Needs sign-off before implementing:* on such a device the finding would move
  from VERIFIED to WARNING, which changes the risk level, the headline and the score — the same kind
  of reported-value change H1, H12 and K3 took sign-off for, but larger, because it can flip a
  verdict rather than fill in a value that was previously "Unknown". Not to be done unilaterally.
  *Passed over (8 Nov run):* unchanged — still needs sign-off: on a brand-only match the finding
  flips from VERIFIED to WARNING and the score moves with it.
  *Passed over (8 Nov, second pass):* unchanged — still needs sign-off, and it is the one open item
  that would change a reported verdict.
  *Passed over (9 Nov run):* unchanged — still needs sign-off: a brand-only match would flip the finding
  from VERIFIED to WARNING and the score with it.

- [x] **L3. `SystemPropertyReader.readProcessDump` waits for `getprop` with no deadline.**
  `process.waitFor()` is unbounded, and the `BufferedReader` read above it blocks until the process
  closes stdout, so a `getprop` that stops answering blocks its calling thread for the life of the
  process — the exact failure `ScanCoordinator` and `StageTimer` name in their own comments ("a
  `getprop` process that does not exit"), which they bound for the *scan* but not for the process.
  Each abandoned stage therefore leaks one blocked thread and one live OS process, and every rescan
  leaks another. *Change:* bound the dump read (a deadline on the read, then `destroyForcibly()` and
  an empty map so reflection still answers), and keep the parse and the parse-once behaviour
  identical. *Verify:* a unit test that drives the loader against a process which never exits (a
  stub `getprop` script or a `sleep` child) and asserts it returns inside the deadline with the
  process gone, plus the existing `SystemPropertyReaderTest` cases unchanged. Not implemented this
  run: it needs a worker thread or non-blocking read inside a class that is currently synchronous
  and dependency-free, which is more than one pass should change at once.
  *Done (8 Nov run):* `readProcessDump` reads the dump on a daemon worker thread and joins it for
  `DUMP_DEADLINE_MS` (2 s); at the deadline it calls `destroyForcibly()` and drains for 250 ms, and the
  `finally` now does a bounded `waitFor(250 ms, MILLISECONDS)` with a second `destroyForcibly()` instead
  of the unbounded `waitFor()` that was the second half of the leak. Lines read before the cut are kept
  and parsed exactly as before, so a property after the cut is simply absent and `get()` falls back to
  reflection for it — the same path as a key the dump never publishes. `parseDump`, the parse-once flag
  and the reflection fallback are untouched, and a `ProcessBuilder.start()` failure now returns an empty
  map instead of propagating (the KDoc always claimed that; only `loadDump`'s catch hid it). The command
  and deadline are parameters of an internal overload, so the process path can be driven without a
  device; the no-argument `readProcessDump()` signature is unchanged. *Measured:* before, a `sh` stub
  that writes one line and then `exec sleep 30` held the calling thread for the stub's full 30.0 s and
  left the process running; after, with a 400 ms deadline the same stub returns in **0.41 s** with the
  process gone (0.413 s for the whole test including spawning `sh`; a silent stub returns in 0.400 s at
  a 300 ms deadline; a healthy three-line dump parses in 0.017 s). Six tests in
  `SystemPropertyReaderDeadlineTest` drive real processes: cut at the deadline with the pre-cut line
  kept, a silent process yielding an empty map, the killed pid gone when the call returns (observed
  through `/proc`, `assumeTrue` on non-Linux hosts), a command that cannot start, a dump that finishes
  inside the deadline keeping every line, and the default deadline asserted finite and sane. All seven
  existing `SystemPropertyReaderTest` cases pass unchanged. `assembleDebug` green with 0 compiler
  warnings; all **294** unit tests pass (288 before, 6 new); APK 9,827,992 bytes, no size claim since
  the debug APK is not byte-reproducible on this toolchain. No `ui/` or `res/` file touched — no screen,
  control, style, layout, copy or reported value changes: on a device whose `getprop` answers normally
  nothing differs, and on one whose `getprop` hangs the scan now loses that dump (reflection still
  answers per key) instead of leaking a blocked thread and a live process on every rescan. Trade-off
  recorded: 2 s is generous for a read-only dump that answers in well under a second on a healthy
  device, and the deadline is a constant, not a per-device guess.
- [x] **L4. `AudioProbe.measureMicrophone` does not release the recorder on a `SecurityException`.**
  The `catch (security: SecurityException)` branch returns `null` without releasing `recorder`, while
  the generic branch right below it does release and clear it — and the comment above the field says
  the recorder is held "so the catch below releases the recorder instead of leaving the microphone
  claimed by an object that only the finalizer will ever free". The security path is the one that
  can actually leave the microphone claimed: a second attempt then overwrites `recorder` and the
  first `MediaRecorder` is never released at all. *Change:* route both branches through one cleanup
  (release, clear, return null). *Verify:* the shared cleanup is exercised by a Robolectric shadow
  that throws from `setAudioSource`; if that cannot be driven, pin the cleanup as a pure helper the
  way K2 split the PDF writer's lifecycle out of `PdfDocument`. Same pass: `playBeep`/`stopBeep` are
  called from nowhere in `app/src/main` (only `playTone`/`stopTone` are), and `playBeep` does not
  call `stopBeep` first, so a second beep would leak the first `ToneGenerator` — decide between
  deleting the pair and fixing it, rather than leaving dead code that is wrong if revived.
  *Done (8 Nov, second pass):* both failure branches now go through one private `releaseRecorder()`
  — release the field, swallow whatever the release throws, clear it — so a refused `RECORD_AUDIO`
  permission, which throws `SecurityException` out of `setAudioSource`, gives the microphone back
  instead of leaving it claimed by a recorder only the finalizer would free; before, the next attempt
  overwrote `recorder` and orphaned the first `MediaRecorder` entirely. The success path's inline
  `release()` + `recorder = null` pair and the probe's own `release()` use the same helper, so there
  is one cleanup, not three. The recorder is now built by a private factory and an internal
  `measureMicrophone(durationMs, newRecorder)` overload takes it from the caller, which is what makes
  the failure paths drivable without a device; the public no-argument signature, its defaults and
  every value it returns are unchanged. The dead pair went too: `playBeep`/`stopBeep` were called
  from nowhere in `app/src/main` (only `playTone`/`stopTone` are, from `AudioTestScreen`), and
  `playBeep` did not stop the previous tone first, so reviving it would have leaked the first
  `ToneGenerator` — deleted with the `toneGenerator` field and its two imports rather than fixing
  code nothing calls. *Tests:* five in `AudioProbeRecorderReleaseTest` (Robolectric, API 34) drive a
  `MediaRecorder` subclass that counts its own releases and throws from `setAudioSource` or
  `prepare`: a refused permission releases exactly once and never starts, a recorder that will not
  prepare releases, a successful capture returns its peak/average and releases exactly once,
  `release()` after a failed capture does not release the same recorder a second time, and a second
  capture does not orphan the first. **Not verified:** the production no-argument overload
  (`MediaRecorder(context)` on API 31+, the deprecated constructor below) is verified by inspection
  only — it needs a real recorder — and the fake overrides every method the probe calls, so these
  tests assert the probe's lifecycle handling, not the platform's. `assembleDebug` green with 0
  compiler warnings, all **299** unit tests pass (294 before, 5 new), APK 9,827,992 bytes with no
  size claim, since the debug APK is not byte-reproducible on this toolchain. No `ui/` or `res/` file
  touched: no screen, control, style, layout, copy or reported value changes — a capture that fails
  still returns "no measurement", it just stops holding the microphone. *Environment note:* Maven
  Central answered 429 for `org.robolectric:nativeruntime-dist-compat:1.0.16` throughout this run;
  it was served from a local file repository through a sandbox-only init script, so no build file,
  repository list or dependency changed.
---

## M. Audit findings — 8 November run, third pass

Every item still open above was re-read this run and is unchanged: F1 and F3/I8 are blocked by this
host (2 cores, 2,098 MB — `minifyReleaseWithR8` and `lintAnalyzeDebug` are both non-resumable and do
not fit in a 300 s call), G1 and I10 would only duplicate numbers F5 and the individual entries
already carry, H2/H5/H7/H9/H11/H13 are new rows, cards or copy on a screen, H14 is missing sourceable
data, and L2 would flip a reported verdict. None of them was re-attempted, and nothing was marked
done that was not done. What follows was found by reading the code with the same lens.

- [x] **M1. The scan's stage-timeout wiring has no test.** `AndroidDeviceInformationProvider` promises
  that "the scan always finishes with whatever it could read" — a stage that never answers is
  abandoned, recorded in `collectionErrors`, and the stages after it still run. `StageTimerTest`
  covers the timer in isolation and the provider's collectors need a device, so the promise itself —
  the thing the whole `StageTimer`/`ScanCoordinator` design exists for — was verified by inspection
  only. *Done:* the timer is now built by a `timerFactory: (CoroutineScope) -> StageTimer` constructor
  parameter whose default is exactly the previous `StageTimer(stageScope)` call, so production
  behaviour is unchanged and a test can drive the deadline without a device that hangs.
  `ScanTimeoutWiringTest` (Robolectric, API 34) runs the real provider with a whole-scan budget of
  zero — the state a wedged scan reaches — and pins that nine collection stages are all reported as
  timed out (nine, not eight: connectivity/audio and security/software each share one progress step),
  that a snapshot is still returned and is not flagged as mock data, and that the fallbacks are the
  values the app already showed: `Build.MANUFACTURER`/`MODEL` for identity, `null` marketing name,
  zero cores and zero RAM, `Unknown` battery health with code 0, and no invented spec fields.
  *Found while writing it, recorded as M2 below:* the provider declares `total = 8` progress stages
  but only calls `stage()` seven times, so its own `finished` flag is never set.
  `assembleDebug` green, all **301** unit tests pass (299 before, 2 new), `tools/check-no-ui-change.sh`
  clean (31 files under `ui/` and `res/`, none differs). No screen, control, style, layout, copy or
  reported value changes — the diff is one constructor parameter and one new test file.
  *Environment note:* this run's sandbox restarted mid-way and lost the toolchain and the checkout, so
  the build was repeated from cold; and Maven Central answered 429 for
  `org.robolectric:nativeruntime-dist-compat:1.0.16`, served from a local file repository through a
  sandbox-only init script, while Robolectric's own runtime jars were fetched from
  `maven-central.storage-download.googleapis.com` through `robolectric.dependency.repo.url`. No build
  file, repository list or dependency changed.
- [ ] **M2. The scan reports seven progress steps but declares eight.** In
  `AndroidDeviceInformationProvider.collect`, `total = 8` while `stage(...)` is called seven times
  (identity, hardware, battery, cameras, sensors, "Connectivity and audio", "Security and installed
  software"), so `finished = done == total` never becomes true and the bar reaches only
  `7 / 8` = 88% before `AppViewModel` publishes its own `ScanProgress(8, 8, "Complete", finished =
  true)`. The collector's own progress contract — every stage reported, the last one finishing the
  scan — is therefore not met by the collector. *Change:* either count the nine collectors as nine
  steps or set `total` to the number of `stage()` calls. *Needs sign-off:* the bar's percentage is
  rendered (`"${(fraction * 100).roundToInt()}%"` in `ScanProgressBar`), so the number a user watches
  during a scan changes from 88% to 100% — a visible change, and the reason it was not fixed here.
  *Passed over (9 Nov run):* unchanged — the bar's percentage is rendered, so the fix is a visible change.

- [ ] **M3. The same sensor count is computed two different ways in one report.**
  `DefaultScoreEngine` counts sensors with `logicalSensorCount(hardware.sensors)` for the "Sensors
  enumerated" input of *Hardware health*, and with `hardware.sensors.size` for the "Sensor count"
  input of *Display and sensors* — whose comment says it excludes the wake-up handles "the way the
  hardware screen counts them", which the code beside it does not. On a device that publishes a
  wake-up handle per sensor (most of them; H4) the two inputs of the same report disagree, one reads
  `50` where the other reads `100`, and the Display-and-sensors score is depressed by the raw count.
  *Change:* use `logicalSensorCount` in both places. *Needs sign-off:* both inputs render their value
  ("48 sensors"), so the fix changes a value the user reads and moves a score.
  *Passed over (9 Nov run):* unchanged — both inputs render their value, so the fix changes what the user
  reads and moves a score.

- [ ] **M4. `PageWriter.finishPage` leaves the small paint at 7.5pt.** `finishPage` sets
  `smallPaint.textSize = 7.5f` to draw the page footer and never restores the 8pt it was built with,
  so from page 2 onward every `small(...)` line (evidence bullets, limitations, the closing
  disclaimer) renders 0.5pt smaller and wraps differently than the same text on page 1. The A5 entry
  already notes this. *Change:* draw the footer with its own paint, or save and restore the size.
  *Needs sign-off:* it changes the rendered PDF export, which is a document the user sends to someone
  else — the same "deliberate decision" A5 asked for, not a drive-by edit.
  *Passed over (9 Nov run):* unchanged — it changes the rendered PDF the user sends to someone else.

- [x] **M5. The JSON export is written on the main thread, and not atomically.** (Done 8 November.)
  `AppViewModel.exportJson` called `reportGenerator.toJson(...)` and then
  `File(cacheDir, "verify-device-info-report.json").writeText(text)` inside `viewModelScope.launch`,
  which is `Dispatchers.Main.immediate`: the encode hopped to `Dispatchers.Default` but the full
  report's disk write ran on the UI thread, and `writeText` truncates the previous export before
  writing the new one, so an export interrupted mid-write left a half-written file that was then
  shared as if it were whole. *Change:* a `ReportGenerator.toJsonFile(report, file): File` that encodes
  through the existing `toJson` (still `Dispatchers.Default`) and then writes the bytes with
  `withContext(Dispatchers.IO) { atomicWriteText(file, text) }` — the crash-safe sibling-temp-then-rename
  helper the history index already uses, so a reader sees the complete previous export or the complete
  new one and never half of either. `AppViewModel.exportJson` now calls it with the same cache path and
  keeps the same message, error text and `exportFile` state. No new file, class or dependency.
  *The gate decision this item was waiting for:* `tools/check-no-ui-change.sh` treated every path under
  `ui/` as UI surface, which would have rejected this even though it renders nothing. The gate now
  carries a `NON_RENDERING_UI_FILES` list holding exactly one entry, `ui/AppViewModel.kt`, and proves
  the entry before letting it through: if the file matches `@Composable`, an `androidx.compose` import
  or a `R.<lowercase>` resource reference, the run fails with a message naming the file. So the
  guarantee is unchanged — *no file that draws anything may differ* — and the exemption cannot be
  widened by accident. Verified by running the gate against a probe that appends `// @Composable` to
  `AppViewModel.kt` (exit 1, correct message) and against a probe that edits `ui/screens/ReportScreen.kt`
  (exit 1, file listed), then restoring both; on the real tree it reports `OK … (31 files tracked there)`.
  *Tests:* six in the new `JsonExportWriteTest` (Robolectric, real `PdfReportGenerator`): the file
  contains exactly the bytes `toJson` produces, the redaction flag reaches the file, a 64 KB stale
  export is replaced in full rather than appended to, no temporary file is left beside the export, a
  missing directory is created, and a write into a read-only directory throws while leaving the
  previous export byte-identical and no `.tmp` behind. *Not verified:* the thread the write lands on is
  not asserted directly — `withContext(Dispatchers.IO)` around the write and `Dispatchers.Default`
  around the encode are verified by inspection, since the production code exposes no hook to observe
  the dispatch from a test.
  *Environment note:* this host needed `-Dorg.gradle.jvmargs=-Xmx1750m`; at the repository's 1400 MB
  default `:app:mergeExtDexDebug` alone did not finish inside a 300 s call and `compileDebugKotlin`
  took 4m32s. No build file was changed for that. `assembleDebug` green, **314** unit tests pass
  (308 before, 6 new), no `res/` file touched, and no screen, control, style, layout, copy or reported
  value changes.

---

## N. Audit findings — 8 November run, third pass

The seventeen items open above were re-read against the code and each one still fails for the reason
already recorded: F1 was re-attempted and still does not fit in a 300 s call (see its entry), F3/I8
are blocked by the same host for the same reason (`lintAnalyzeDebug` is non-resumable), G1/I10 would
duplicate numbers the individual entries already carry, H2/H5/H7/H9/H11/H13 each need a new row, card
or copy on a screen, H14 needs data the app cannot source, and L2/M2/M3/M4/M5 each change a value the
user reads or need a decision this run must not take alone. Nothing was ticked that was not done. The
item below was found by reading the code with the same lens and was implemented in this run.

- [x] **N1. A reference release date that is not a real date is silently repaired into a different
  one.** `AnalysisSupport.releaseDateToMillis` builds a `java.util.Calendar`, which is **lenient** by
  default, and sets year/month/day on it: `2023-13-01` became 1 January 2024, `2023-02-30` became
  2 March 2023 and `2023-04-31` became 1 May 2023. The value flows straight into
  `DefaultAgeEstimator`, so one mistyped `releaseDate` in the hand-curated reference set would have
  produced a plausible, wrong "Model launch date", a wrong "Model age" and a wrong age input to the
  score — and, in a report whose whole premise is that it does not assert what it cannot support, a
  date the data never contained. *Change:* the parse is strict. An impossible month or day is refused
  before `Calendar` sees it, `isLenient` is off so a day the month does not have (including
  `2023-02-29`) throws and is caught, and the result is `null` — the same answer as an absent date —
  instead of a different date. Valid dates, the coarse `2023-03` and `2023` forms and UTC midnight are
  unchanged, byte for byte. *Invisible on the shipped data:* all sixteen `releaseDate` values in
  `device_reference.json` are real calendar dates, so no device's launch date, model age or score
  moves; only a value the set could never have meant is now rejected. Seven tests in the new
  `ReferenceDateTest`: an exact date and its UTC millis, the two coarse forms, month 13 and month 0,
  `2023-02-30`/`2023-04-31`/`2023-01-32`, a real leap day against `2023-02-29`, the absent and
  unparseable cases, and a guard that loads the bundled asset through `ReferenceAssetParser` and
  asserts every entry's `releaseDate` parses and is in the past — so a future typo fails a test rather
  than silently dropping a model's launch date on a device. `assembleDebug` green, all **308** unit
  tests pass (301 before, 7 new), no `ui/` or `res/` file touched (the diff is one production file,
  one test file and this document), and no screen, control, style, layout, copy or reported value
  changes.
  *Environment note:* GitHub release downloads answered 504 this run, so the toolchain came from the
  same assets through the GitHub API; Maven Central answered 429, so test dependencies resolved
  through `maven-central.storage-download.googleapis.com` via a local init script and Robolectric's
  five runtime jars were served from `ROBOLECTRIC_DEPENDENCY_DIR`. No build file, repository list or
  dependency changed.

### Fourth pass — 8 November, later the same day

One item was closed: **M5**, the JSON export's main-thread, non-atomic write. It was the only open
item whose blocker was a decision rather than missing data or a host limit, and the decision is
recorded in its entry: the UI gate now proves a declared non-rendering file renders nothing instead
of refusing every path under `ui/` outright. Nothing else was ticked. The other sixteen still fail for
the reason already written against them: F1/F3/I8 are host limits (`minifyReleaseWithR8` and
`lintAnalyzeDebug` are non-resumable and do not fit a 300 s call on this 2 GB / 2-core sandbox, even
with a 1750 MB heap), G1/I10 would duplicate numbers the individual entries already carry, and
H2/H5/H7/H9/H11/H13, L2, M2, M3 and M4 each change a value, row or rendered line the user reads.

---

## O. Audit findings — 8 November run, fifth pass

Every unchecked item above was re-read against the code before this pass started, and each still
fails for the reason already recorded: F1 stays a host limit and was not retried again
(`nproc` = 2, `free` = 2,098 MB, and `minifyReleaseWithR8` neither finishes in a 300 s call nor
resumes across one), F3 and I8 are blocked by the same host for the same reason
(`lintAnalyzeDebug` is non-resumable), G1 and I10 would duplicate numbers their own entries already
carry, H2/H5/H7/H9/H11/H13 each need a new row, card or copy on a screen, H14 needs codenames the
recorded sources do not publish, and L2/M2/M3/M4 each change a value, a percentage or a rendered
page. Nothing was ticked that was not done. The item below was found by reading the code with the
same lens and was implemented in this run.

- [x] **O1. The stored report file is the last write on the history path that is not crash-safe.**
  `FileDiagnosticHistory.save` writes one JSON document per scan to `history/reports/<uuid>.json`
  and then rewrites the index that points at it. The index has gone through `atomicWriteText` since
  B2 and the JSON export since M5, but the report itself was still `File(...).writeText(...)`, which
  truncates the target *before* writing the new bytes: a save interrupted between the two — the
  process killed while the scan was finishing, storage full, a wedged filesystem — left a truncated
  document under `history/reports/`. That file is not inert: `HistoryScreen` expands an entry by
  calling `loadRaw` and renders the raw text, so the user was shown half a report with no indication
  that it was half a report, in a document whose whole premise is that it does not assert what it
  cannot support. *Change:* the report goes through the same `atomicWriteText` helper the index
  already uses — bytes to a sibling `<uuid>.json.tmp`, `flush`, `fd.sync()`, then `rename` — so a
  reader sees the complete document or no document, and the existing `catch` still degrades a failed
  write to "this report cannot be re-opened" instead of failing the save. No new helper, file, class
  or dependency, and nothing about the entry, the index, the prune or the id scheme changed.
  *Tests:* four in the new `HistoryReportWriteTest` (Robolectric, real `FileDiagnosticHistory` over
  the real `ReportJson`): the stored file is byte-for-byte `ReportJson.encode(report, redacted =
  true)`, the stored document parses as a whole JSON object and is what `loadRaw` returns, a save
  leaves exactly one file in `history/reports/` with no `.tmp` beside it, and a report that cannot
  be written (read-only `reports/` directory) leaves **no** file at all while the save still returns
  its entry and the index still lists it. *Not verified:* the crash itself. A process killed between
  truncate and flush cannot be reproduced in a unit test, so the atomicity is verified through the
  helper's own tests (`HistoryIndexWriteTest`: complete content on success, previous content intact
  on failure, no temporary left behind) plus the four assertions above on the call site; the tests
  pin the guarantees, they do not re-create the crash.
  *Invisible on a device:* a save that completes changes nothing a user sees — the same entry, the
  same id, the same bytes in the same file — and the only behavioural difference is on the failure
  path, where a half-written report used to be shown and now cannot exist. `assembleDebug` green;
  **318** unit tests pass (314 before, 4 new); APK 9,827,992 bytes
  (unchanged, as expected for a call-site swap).
  *Environment note:* this sandbox's pre-seeded `~/.m2` copy of
  `android-all-instrumented-15-robolectric-12650502-i7.jar` — the runtime Robolectric loads for the
  default (target SDK 35) test configuration — is truncated to 73,646,080 of its 199,527,464 bytes,
  which aborts the whole test JVM with `Bitmap F … Unable to find static field mNativePtr` (exit
  134) as soon as a default-SDK test runs, on the unmodified tree as much as on this change. The
  complete jar from `maven-central.storage-download.googleapis.com`, served through
  `ROBOLECTRIC_DEPENDENCY_DIR`, makes the suite green again. No build file, repository list or
  dependency was changed for it.

---

## P. Audit findings — 9 November run

The sixteen items left open above were re-read against the code before anything new was added, and
none of them can be finished in this environment. F1, F3 and I8 need one Gradle invocation longer
than 300 s (`minifyReleaseWithR8`, `lintAnalyzeDebug`; neither is resumable, and this host is still
2 cores / 2,098 MB). H2, H5, H7, H9, H11, H13, M2, M3 and M4 change a value, a percentage or a
rendered document; L2 flips a verdict; G1 and I10 would duplicate numbers already recorded next to
their own items; H14 needs codenames the recorded sources do not publish. So the code was re-read
with the lens of "When the list runs out". Two findings, the first implemented here.

- [x] **P1. `DefaultIntegrityAnalyzer` — the rules behind the Security screen and the integrity
  score — had no test.** Every other analysis class has one (`AuthenticityEngineTest`,
  `ScoreEngineTest`, `PartsProviderTest`, `AgeEstimatorTest`, `AnalysisSupportTest`), but this one
  appeared in the suite only as a fixture built *around* a report: no test ever called `analyze` or
  asserted a status. Its ten checks decide what the Security screen lists, what the integrity score
  reads, and whether the report says "The system software has serious integrity problems", so a
  changed threshold or a flipped branch was invisible to the suite. *Change:* a new
  `IntegrityAnalyzerTest`, 16 tests, one per rule plus the aggregate — the check ids and their order
  (a new check is a UI change, so it must break a test first); root indicators listed once each in
  the warning; all three bootloader outcomes; `green` only, case-insensitively, with `orange`,
  `yellow` and absent distinguished; `ro.debuggable` outranking the developer-options setting, ADB
  alone raising a warning, an unreadable setting staying UNVERIFIED; SELinux `Enforcing` only;
  patch-age thresholds at one and two years, with an absent, unparsable and truncated date all
  UNVERIFIED; StrongBox mentioned only when the device reports it; Play Integrity never simulated;
  installer PASS versus UNVERIFIED; and the aggregate verdict and its summary for clean, one-warning,
  many-warning and failed reports. *Mutation-checked, not just green:* changing `patchDays > 730` to
  `> 900` and `contains("encrypted")` to `startsWith("encrypted")` made exactly three tests fail
  (`patchAgeThresholdsAreOneYearAndTwo`, `encryptionIsJudgedBySubstringAsTheCodeCurrentlyDoes`,
  `theOverallVerdictAggregatesTheChecks`); the mutations were then reverted and the file is
  byte-identical to the committed one. *Test-only change:* no production file, no UI, copy, layout,
  status or reported value touched. `assembleDebug` green; **334** unit tests pass (318 before, 16
  new); debug APK 9,827,992 bytes, unchanged. Recorded while writing it, and not fixed here: the
  aggregate's `else -> UNVERIFIED` branch cannot be reached, because the encryption check always
  returns either VERIFIED or WARNING.
- [ ] **P2. The encryption check reports an unencrypted volume as VERIFIED.**
  `DefaultIntegrityAnalyzer` decides the "Storage encryption" check with
  `security.encryptionState.contains("encrypted", true)`. `vold`/`ro.crypto.state` publish
  `unencrypted` as well as `encrypted`, and `"unencrypted"` contains `"encrypted"`, so a device whose
  userdata is not encrypted — a very old build, or one whose crypto state was modified — is shown as
  VERIFIED with the explanation "Storage reports as 'unencrypted'", and the aggregate verdict and
  the integrity score move with it. *Change:* compare the state rather than search inside it
  (`equals("encrypted", ignoreCase = true)`, with `blank`/`unsupported` kept as the warning they
  already are). *Needs sign-off:* it moves a status the Security screen shows from VERIFIED to
  WARNING on such a device, and with it the headline and the score — the same kind of change H1, H12,
  K3 and L2 are waiting on. The current behaviour is pinned by
  `IntegrityAnalyzerTest.encryptionIsJudgedBySubstringAsTheCodeCurrentlyDoes`, so the fix has to
  change that assertion deliberately.
  *Passed over (9 Nov run):* unchanged — still needs sign-off: it moves a status from VERIFIED to WARNING.

---

## Q. Audit findings — 9 November run

The seventeen items left open above are all blocked by this sandbox (F1, F3, I8), duplicates of work already
recorded (G1, I10), need a visible change (H2, H5, H7, H9, H11, H13, M2, M3, M4), need data the app does not
carry (H14), or need sign-off because they move a reported verdict (L2, P2). So the list was re-read against
the code, as "When the list runs out" asks. One finding cleared the bar and was implemented in this run.

- [x] **Q1. The report's word wrap rebuilds the whole line for every word.** `PageWriter.wrap` (the PDF
  export's text layout) kept the line being built in a `StringBuilder`, but for every word it rebuilt that
  line as a new `String` (`"$current $word"`) and then copied it *again* into a fresh `StringBuilder`. Laying
  out a paragraph of W words therefore allocated O(W²) characters — a 60-word paragraph built and threw away
  sixty copies of itself, and one export lays out hundreds of paragraphs, so the export path's garbage was
  dominated by copies of text it already had. *Done:* the wrap moved out of the inner class into an
  `internal fun wrapText(text, maxWidth, measure)` (the same test seam shape as `alphabeticalByLabel`), which
  appends to one `StringBuilder` and measures it in place with `paint.measureText(line, 0, line.length)`;
  `PageWriter.wrap` is now a one-line delegate, so every call site (`body`, `small`, `warning`, `keyValue`,
  `footer`) is untouched. **No rendered change:** the measurement still receives exactly the same candidate
  text as before — the whole line, end to end — so every wrap decision, every line and every glyph position
  in the exported document is identical; only the copies are gone. Measured on this host (JDK 17, warmed,
  minimum of five rounds): laying out a report-sized corpus of 351 paragraphs took **7,334 µs before and
  1,524 µs after (4.8× faster)**, and a standalone JVM run of the two algorithms allocated **3,582,296 bytes
  before and 968,696 bytes after (3.7× less, ~2.5 MB of garbage removed per report-sized layout)**. Six tests
  in `PdfTextWrapTest` pin it: the new wrap produces byte-identical line lists to a verbatim copy of the old
  algorithm across a 14-text corpus × 7 widths × 3 measurers (including a measurer that is not a function of
  length, so a lost or re-added word cannot measure the same), the *number* and the *content* of the
  measurement calls are unchanged (so no extra work reaches the paint), an over-long word still overflows its
  line instead of being broken, blank text still produces no lines, and the timing benchmark above.
  `assembleDebug` green with no compiler warnings, all **340** unit tests pass (334 before, 6 new),
  `tools/check-no-ui-change.sh` clean (31 files tracked under `ui/` and `res/`, none differs) — the diff is one
  modified production file and one new test file. Debug APK 9,895,564 bytes, unchanged within the toolchain's
  non-reproducibility (J1/J2), and no size claim is made. *Not verified on a device:* the equivalence of
  `measureText(CharSequence, 0, length)` with `measureText(String)` is proven here only for the injected
  measurer; the two Android overloads compute the same width for the same text by contract, and the
  substitution is the documented way to measure a subrange, but no device is available to confirm it.

---

## R. Audit findings — 9 November run, second pass

The seventeen items still open above were re-read against the code and passed over for the same reasons
as before, none of them new: blocked by this sandbox (F1, F3, I8 — 2 cores / 2,098 MB, where the cold
`assembleDebug` alone needed eight 300 s calls), duplicates of work already recorded (G1, I10), a visible
change (H2, H5, H7, H9, H11, H13, M2, M3, M4), data the app does not carry and cannot source (H14), or
sign-off because they move a reported verdict (L2, P2). One finding from re-reading the export path
cleared the bar and was implemented in this run.

- [x] **R1. The report's word wrap still allocated a substring per word.** `wrapText` (the PDF export's
text layout) was rewritten in Q1 to stop rebuilding the line per word, but it still took its words from
`text.split(" ")`: one `String` per word, plus the `ArrayList` that holds them, allocated before the first
line could be laid out. A 60-word paragraph allocated sixty substrings it then only read, and one export
lays out hundreds of paragraphs, so the split was the largest allocation left in the wrap. *Done:* the
words are now read as ranges of the paragraph (`indexOf(' ')` from the previous boundary) and appended
straight into the line with `StringBuilder.append(CharSequence, start, end)`, so the scan allocates
nothing but the line and its result. The ranges are exactly the words `split(" ")` produced — empty
words included, which is what a run of two spaces and a trailing space each add — so the sequence of
appends, the measurements, and therefore every line and every glyph position are unchanged. The old
`split` version is kept verbatim in `PdfTextWrapTest.splitReferenceWrap` as the reference the new scan is
checked against, over the corpus (now extended with `"a "`, `" a"`, `"a  b"`, `" a  b "`, a
tab-separated line and thirty trailing-space words) × 7 widths × 3 measurers, plus a test that a
whitespace-only paragraph still produces no lines. *Measured:* on this host (JDK 17, warmed, minimum of
five rounds, a report-sized corpus of 351 paragraphs, both algorithms through the same length-based
measurer, allocation from `ThreadMXBean.getThreadAllocatedBytes`) the wrap went from **532 µs and
442,600 bytes to 304 µs and 168,320 bytes** — 1.75× faster and **274 KB less garbage per report-sized
layout**. In the repository's own benchmark test (`PdfTextWrapTest`, 351 paragraphs) the Q1 comparison
still reports 6,584 µs for the original candidate-string wrap against 1,736 µs now. `assembleDebug`
green, all **342** unit tests pass (340 before, 2 new), `tools/check-no-ui-change.sh` clean (31 files
tracked under `ui/` and `res/`, none differs) — the diff is one modified production function and its
test. No rendered change: same lines, same breaks, same PDF. *Not verified on a device:* the timing is
the algorithm's on a JVM with a length-based measurer, not a `Paint`'s, and no device is available here;
the equivalence itself does not depend on the measurer, and is proven for three of them.

---

---

## S. Audit findings — 9 November run, third pass

The seventeen items still open above were re-read against the code before anything new was added, and
each still fails for the reason already written against it: F1, F3 and I8 need one Gradle invocation
longer than 300 s on this 2-core / 2,098 MB sandbox (`minifyReleaseWithR8`, `lintAnalyzeDebug`; neither
resumes across a call), G1 and I10 would duplicate numbers their own entries already carry, H2, H5, H7,
H9, H11 and H13 each need a new row, card or copy on a screen, H14 needs codenames the recorded sources
do not publish, and L2, M2, M3, M4 and P2 each move a value, a percentage or a verdict the user reads.
Nothing was ticked that was not done. The item below was found by re-reading the diagnostics probes with
the memory-and-lifecycle lens of section C.

- [x] **S1. A tone that could not start leaked its `AudioTrack`.** `AudioProbe.playTone` built an
  `AudioTrack`, wrote the tone into it and started it, and only then put it in the `audioTrack` field the
  cleanup path releases. Both `write` and `play` throw `IllegalStateException` when the track is not in
  the `INITIALIZED` state — no usable output route, an unplugged or handed-over sink, a device whose audio
  HAL refused the configuration — so on exactly the devices where the play button does not work, the
  catch ran `stopTone()` with the field still empty (or still holding the previous, already-released
  track) and the track just built was never released. Each failed tap therefore left behind a native
  track, its AudioFlinger client and its static buffer — 176,400 bytes of samples for the default
  two-second mono tone (88,200 samples × 2 bytes, arithmetic, not measured) — until the finalizer got to
  it, while the user, hearing nothing, tapped again. *Done:* the field is assigned immediately after the
  track is built, before `write` and `play`, so the existing catch gives it back through the same
  `stopTone()` — the rule the recorder path already follows since L1 ("held from construction"). The
  public `playTone(frequencyHz, durationMs)` keeps its signature, its builder settings and its sample
  generation byte for byte; its body moved into an `internal` overload that takes the track from the
  caller, the same seam shape as `measureMicrophone`'s `newRecorder`, so the failure paths can be driven
  on a JVM. The sample rate is now one private constant instead of a local, and nothing else in the
  class, the screen that calls it or the reported facts changed.
  *Tests:* six in the new `AudioProbeToneReleaseTest`, over a fake `AudioTrack`: a `write` that throws
  releases the track exactly once and never starts it, a `play` that throws releases it, a successful
  tone keeps the track until `stopTone()` releases it once, a second tone releases the first and only the
  first, a failed tone leaves nothing for `release()` to free twice, and the generated tone still has the
  same 88,200 samples with a 19,650–19,661 peak. *Mutation-checked, not just green:* moving the
  assignment back after `play()` made exactly three of them fail — `aWriteThatThrowsReleasesTheTrack`,
  `aPlayThatThrowsReleasesTheTrack`, `aFailedToneLeavesNothingForReleaseToFree` — and the file was then
  restored byte for byte from the copy taken before the mutation. `assembleDebug` green; **348** unit
  tests pass (342 before, 6 new); `tools/check-no-ui-change.sh` clean (31 files tracked under `ui/` and
  `res/`, none differs); debug APK 9,827,992 bytes, unchanged. *Not verified on a device:* no device is
  available here, and Robolectric's shadow `AudioTrack` does not model AudioFlinger, so the leak is
  asserted as "`release()` is called on every failure path" rather than observed as a freed native
  client. The fake is a subclass built through the deprecated `AudioTrack(...)` constructor; production
  still builds its track through `AudioTrack.Builder`, unchanged.
  *Environment note:* this run's cold build only fit inside a 300 s call after applying the lever
  recorded under F1 — the sandbox exports `JAVA_TOOL_OPTIONS=-XX:CompressedClassSpaceSize=64m`, which
  starves the Kotlin compiler, and a 1750 MB heap on this 2 GB host thrashes instead of helping. With
  `JAVA_TOOL_OPTIONS` unset and `-Xmx1400m -XX:MaxMetaspaceSize=512m`, `:app:compileDebugKotlin` finished
  in 4m08s and the rest of `assembleDebug` in 3m37s. No build file, repository list, dependency or heap
  setting in the repository was changed for it.

---

## T. Audit findings — 10 November run

The seventeen items open above were re-read against the code before anything new was added, and each
still fails for the reason already recorded against it: F1, F3 and I8 need one Gradle invocation longer
than 300 s on this 2-core / 2,098 MB sandbox (`minifyReleaseWithR8`, `lintAnalyzeDebug`; neither resumes
across a call), G1 and I10 would duplicate numbers their own entries already carry, H2, H5, H7, H9, H11
and H13 each need a new row, card or copy on a screen, H14 needs codenames the recorded sources do not
publish, and L2, M2, M3, M4 and P2 each move a value, a percentage or a verdict the user reads. Nothing
was ticked that was not done. The item below was found by following what happens to a finished scan when
the store underneath it refuses to be written.

- [x] **T1. A history index that cannot be written threw away a scan that had already finished.**
  `FileDiagnosticHistory.save` writes two things: the report document, whose failure has been swallowed
  since B2 so that an unstorable report degrades to "the report cannot be re-opened", and the index
  entry that lists it — `writeIndex(kept)`, called bare. `AppViewModel.scan` calls `history.save`
  *inside its own `try` block*, so an exception escaping the index write landed in the scan's
  `catch (e: Exception)` and published `"Scan failed: …"`: the user was told the scan failed and shown
  no report at all, although every collector had completed, every score had been computed and the
  report document was already on disk. The reachable triggers are ordinary ones — a full data
  partition, an app-private directory made unwritable, or a `history/index.json.tmp` path that cannot
  be opened — and the failure was silent about its own cause: the message named the index file, not the
  scan. *Done:* the index write is now `runCatching`, the same shape as the report write beside it, so
  that store degrades to "this scan is not listed" instead of "this scan did not happen". Pruning stays
  correct without further change: `kept` is the previous entries plus this one, so it can only be a
  superset of what the index still on disk points at, and nothing the old index references is removed.
  The signature of `save`, the entry it returns, the JSON it writes and the order of the writes are all
  unchanged, and no `ui/` or `res/` file was touched.
  *Tests:* three in the new `HistoryIndexFailureTest` (Robolectric, `sdk = 34`), using the obstruction
  `HistoryIndexWriteTest` already establishes — `history/index.json.tmp` made a directory, so the
  crash-safe write cannot open it: a save whose index cannot be written returns normally and leaves the
  report document on disk, the index it cannot replace is left exactly as it was (the earlier entry is
  still listed and still opens) while both documents survive, and a later save with the obstruction
  cleared writes the index again and prunes the orphaned report. *Not verified:* the end-to-end effect
  on the UI is asserted at the store, not through `AppViewModel` — driving `scan()` needs a device's
  collectors, and the claim being tested is precisely that `save` no longer throws, which is the only
  way the scan's `catch` could be reached. `assembleDebug` and `testDebugUnitTest` both green (see the
  commit message for the counts); `tools/check-no-ui-change.sh` clean.

---

## U. Audit findings — 10 November run, second pass

The seventeen items left open above were re-read against the code first, and each still fails for the
reason already recorded against it, so none was retried or ticked: F1 and F3 need one Gradle
invocation longer than 300 s (`minifyReleaseWithR8`, `lintAnalyzeDebug`, neither resumable across a
call) on this 2-core / 2,098 MB sandbox, and I8 is F3 under another name; G1 and I10 would duplicate
numbers their own entries already carry; H2, H5, H7, H9, H11 and H13 each need a new row, card or
copy on a screen, H14 needs codenames the recorded sources do not publish; M2, M3 and M4 each move a
number or a percentage the user reads; L2 and P2 each move a reported verdict and are waiting on
sign-off. The item below was found by reading the one production function on the scan path that still
formatted text through `String.format` in a loop.

- [x] **U1. The APK signature digest built a `Formatter` per byte.** `SecurityCollector.readSignatureDigest`
  turned the SHA-256 of the signing certificate into the colon-separated hex the Security screen shows
  with `joinToString(":") { byte -> "%02X".format(byte) }`: one `String.format` call per byte, and each
  one constructs a `java.util.Formatter`, resolves a `Locale`, parses the format string and allocates
  its own builders — 32 of them for one digest, on every scan, to produce 95 characters. It was also
  the last `String.format` left on the scan path without a pinned locale; every other one already
  passes `Locale.US` (H10). *Measured first, and the locale half of that turned out not to be a bug:*
  `"%02X"` was run under `en-US`, `hi-IN`, `ar-EG`, `ar-SA`, `fa-IR`, `bn-IN`, `mr-IN` and `ne-NP` and
  printed identical Latin hex in all eight, so nothing on screen was wrong — this is a cost, not a
  correctness fix, and the entry says so rather than claiming a bug that is not there.
  *Change:* the encoding moved to `Formatters.hexColon(bytes)` (`core/util/Formatters.kt`), a pure
  Kotlin helper beside the other hand-rolled formatters (`bytes`, `bytesGigabytes`), and the call site
  is now `Formatters.hexColon(MessageDigest.getInstance("SHA-256").digest(it))`. It appends two
  characters from a `0123456789ABCDEF` table per byte into one `StringBuilder` pre-sized to
  `3n - 1`. `and 0xFF` keeps `%02X`'s unsigned reading of a negative byte, so `80` stays `80` and does
  not become `FFFFFF80`. **No rendered change:** the string is byte-identical for every input, which
  is what the test below sweeps.
  *Measured* (JDK 17, this host, warmed, minimum of seven rounds, one 32-byte digest per operation):
  **25,409 ns → 505 ns (50× faster)** and **20,681 bytes → 248 bytes allocated (83× less)**; the
  whole saving is ~20 KB of garbage and ~25 µs per scan, which is small in absolute terms and is
  stated as such — the item is worth taking because the replacement is simpler than what it replaces
  and carries a pinned-output test, not because 25 µs changes anything the user feels.
  *Tests:* six in `FormattersHexTest` (plain JVM, no Robolectric) against a verbatim copy of the
  `joinToString(":") { "%02X".format(it) }` oracle — all 256 single-byte values (so every negative
  byte is covered), 200 random 32-byte digests, seven other lengths from 1 to 65 bytes, the empty
  array, the 32-group/95-character shape with every character restricted to `0-9A-F`, and the four
  boundary bytes `00`/`7F`/`80`/`FF`. Mutation-checked: dropping the `and 0xFF` makes
  five of the six tests fail (a negative byte indexes the digit table out of range, so
  `80`/`FF` and every sweep that covers a negative byte go red) while the empty-array test still
  passes; restored, all six pass.
  `assembleDebug` and `testDebugUnitTest` green; `tools/check-no-ui-change.sh` clean — the diff is
  one modified production file, one modified collector and one new test file, and nothing under
  `ui/` or `res/`.

---

## V. Audit findings — 10 November run

The seventeen items left open above are all blocked by this sandbox (F1, F3, I8: 2 cores / 2,098 MB,
where a cold `assembleDebug` alone needed nine 300 s calls this run and `minifyReleaseWithR8` and
`lintAnalyzeDebug` are neither finishable in one call nor resumable), duplicates of work already
recorded (G1, I10), need a visible change (H2, H5, H7, H9, H11, H13, M2, M3, M4), need data the app
does not carry (H14), or need sign-off because they move a reported verdict (L2, P2). So the list was
re-read against the code, as "When the list runs out" asks, starting from the state-change path in
`AppViewModel` — the one production file on the UI side that renders nothing. V1 was implemented in
this run; V2 is the rest of the same finding and is left open on purpose.

- [x] **V1. A state change re-derived evidence that cannot have changed, on the main thread.**
  `AppViewModel.rebuildReport` recomputed all five analyses on every call. Only `parts` and `scores`
  read the diagnostic session; `integrity` reads the snapshot alone and `age` reads the snapshot, the
  reference and the purchase date — and `authenticity` was already reused from the previous report
  rather than recomputed. So every recorded diagnostic result re-ran the integrity analysis and the
  age estimate to produce the same objects, and `setRedacted` ran the whole set to change a boolean
  that no analyzer reads: both export paths apply the flag themselves by copying `state.redacted`
  onto the report (`exportPdf`, `exportJson`), so nothing the rebuild produced could differ.
  *Change:* `rebuildReport` reuses `previous.integrity` and `previous.age` (the latter unless the new
  `recomputeAge` parameter is set, which `setPurchaseDate` does because the purchase date is an age
  input), and `setRedacted` writes the flag and the report timestamp instead of rebuilding. No
  analyzer, no data model and no rendering changed.
  *Measured* (JDK 17, this host, warmed, minimum of 300 rounds per operation, `TestFixtures.snapshot()`
  — a fixture, not a handset; no device is available here): a full rebuild of `parts + age +
  integrity + scores` takes **500 µs median / 364 µs min**, and the session-dependent pair
  `parts + scores` alone **240 µs / 167 µs**, so the reuse removes **~260 µs** of main-thread work per
  recorded result. Flipping the redaction switch went from that full rebuild (**~500 µs**) to a
  `DeviceReport.copy` (**~2 µs median, 1 µs min**). For scale: `authenticity.analyze` alone is
  434 µs median on the same fixture, and it was already being reused. These are sub-millisecond
  numbers and the entry says so — the item is worth taking because the work being removed provably
  cannot change the result, not because 260 µs is felt.
  *Tests:* four in `AppViewModelRebuildTest`, driving the real view model through the debug-only mock
  scan and asserting reuse by identity: the redaction switch re-derives nothing (`assertSame` on all
  five analyses) while still reaching the report and the state; it still re-stamps `generatedAtMillis`,
  which is the one thing `ReportScreen` renders from that path; recording a diagnostic result keeps
  `integrity`, `age` and `authenticity` identical and produces new `parts` and `scores`, with the
  result present in the session; and a new purchase date still re-derives the age while reusing the
  integrity report. Mutation-checked by reasoning rather than by reverting in-tree: with the reuse
  removed the first three fail on `assertSame`, and with `recomputeAge` dropped the fourth fails on
  `assertNotSame`.
  `assembleDebug` and the full `testDebugUnitTest` suite green (361 tests: 357 before, 4 new);
  `tools/check-no-ui-change.sh` clean — the diff is one modified `ui/AppViewModel.kt` (already in
  `NON_RENDERING_UI_FILES`), one new test file and this entry.

- [ ] **V2. The session-dependent rebuild still runs synchronously on the main thread.**
  `AppViewModel.recordResult` and `setPurchaseDate` call `rebuildReport` directly, so the ~240 µs that
  is left (V1's measurement) — and, on a real device with a full field list, more — runs inside the
  click that records a diagnostic result. *Change:* launch the derivation on `Dispatchers.Default`
  behind the existing `ScanCoordinator` generation gate so only the newest rebuild publishes, and
  keep the redaction/age paths as they are. *Not done this run:* it makes the report briefly stale
  after a result is recorded, and the export buttons read `state.report`, so a tap landing inside
  that window would export the previous report — a behaviour change that needs a decision, not a
  drive-by edit. Everything else about it is specified above.

---

## W. Audit findings — 10 November run

The seventeen items left open above are all blocked here, for the same reasons the last four runs
recorded. Blocked by this sandbox (F1, F3, I8): `nproc` = 2 and 2,098 MB again; this run's cold
`assembleDebug` needed five 300 s calls and `:app:mergeExtDexDebug` alone took 3m37s with a warm
configuration cache, and neither `minifyReleaseWithR8` nor `lintAnalyzeDebug` can finish inside one
call or resume across calls. Duplicates of work already recorded (G1, I10): the release runtime
classpath was re-measured this run and holds nothing unexpected — the Compose stack dominates the
pre-dex input (`ui` 5,030,805 B, `material3` 4,965,333 B, `foundation` 4,842,790 B, `runtime`
2,415,632 B), followed by `kotlin-stdlib` 1,747,660 B and `kotlinx-coroutines-core` 1,463,587 B, and
no test or tooling artefact (`robolectric`, `ui-test-junit4`, `ui-tooling`) appears in it at all.
Need a visible change (H2, H5, H7, H9, H11, H13, M2, M3, M4). Need data the app does not carry
(H14). Need sign-off because they move a reported verdict (L2, P2, V2).

So the suite itself was read instead, and one finding cleared the bar: a regression test that fails
on unchanged code, which is a red CI run (I7) that says nothing about the app.

- [x] **W1. The PDF wrap regression test decides its verdict with a wall-clock race.**
  `PdfTextWrapTest`'s last test (`laying out a report's worth of paragraphs is faster than the copies
  it replaced`) timed two micro-benchmarks — 351 paragraphs laid out by the old wrap and by the new
  one, five warm-up passes and a best-of-five each — and asserted `after < before` with no margin.
  It measures the host, not the code: **the same unchanged sources failed it in this run**
  (`before=8,670,042ns after=15,127,214ns`, the copy-free wrap "slower" by 1.7×) minutes after
  passing it on the same build on the same two-core machine. *Done:* the timing comparison is
  replaced by a deterministic one that pins the property the item existed for — a word must not cost
  a copy of the whole line. The measurer now records the *identity* of every `CharSequence` it is
  handed: the current wrap measures the single `StringBuilder` it appends into (1 distinct object per
  paragraph), while the previous wrap built `"$current $word"` per word (>1 distinct objects), so the
  assertion holds by construction on any machine and at any load. Nothing about the output is lost:
  every line is still compared character for character against both reference implementations by the
  equality tests above, and the measurement-count test still pins that no extra work reaches the
  paint. The 15 benchmark passes over 351 paragraphs are gone from the suite as well. Test count
  unchanged (361, one test replaced by one test); `assembleDebug` green and all 361 unit tests pass
  in this run, `tools/check-no-ui-change.sh` untouched (test sources only).
  *Not claimed:* no change in wrapping behaviour, and none intended — this commit only removes a
  false-failure path.

---

## X. Audit findings — 11 November run

The eighteen items left open above were re-read before anything new was added, and none of them can
be finished in this environment. Blocked by this sandbox (F1, F3, I8): `nproc` = 2 and 2,098 MB again,
this run's cold `assembleDebug` needed seven 300 s calls, and neither `minifyReleaseWithR8` nor
`lintAnalyzeDebug` finishes inside one call or resumes across calls. Duplicates of work already
recorded (G1, I10). Need a visible change (H2, H5, H7, H9, H11, H13, M2, M3, M4). Need data the app
does not carry (H14). Need sign-off because they move a reported verdict (L2, P2, V2). Nothing was
ticked that was not done.

So the code was read where the scores come from — the automated diagnostics pass, which turns
measurements into the results the Hardware and Security scores are built from — and one finding
cleared the bar. X1 was implemented in this run.

- [x] **X1. `runAutomatedChecks` guarded two of its six checks, so one unreadable service discarded
  the whole pass — and the class had no test at all.**
  `AndroidHardwareDiagnosticsEngine.runAutomatedChecks` produces six results (`auto_storage`,
  `auto_memory`, `auto_thermal`, `auto_camera_enum`, `auto_sensor_liveness`, `charging`). The storage
  and memory checks were wrapped in `try`/`catch`; the other four read a vendor service bare —
  `getSystemService(POWER_SERVICE) as PowerManager` followed by `currentThermalStatus`,
  `cameraManager.cameraIdList`, `SensorProbe.probeLiveness`/`hasSensor`, and
  `registerReceiver(ACTION_BATTERY_CHANGED)`. An exception from any of them propagated out of the
  pass, and `AppViewModel.runAutomatedDiagnostics` catches it and replaces the whole run with
  "Diagnostics failed: …", leaving the session untouched — so one check that could not be read threw
  away every result the other five had already measured. The class was also the only analysis-sized
  production file in `data/android` with no test: no test called `runAutomatedChecks` or asserted a
  status, a threshold or the set of checks it reports, so a dropped check, a reordered one or a
  changed threshold was invisible to the suite.
  *Change:* a top-level `internal suspend fun automatedCheck(testId, title, unreadable, block)` runs
  one check and turns a throw into a single `UNVERIFIED` result whose note reads
  "Could not read <unreadable>: <message>" — the same shape the storage and memory checks already
  used, with their notes left byte-identical ("Could not read storage statistics: …",
  "Could not read memory statistics: …"). `CancellationException` is rethrown, so a superseded run
  still unwinds instead of reporting a refusal. All six checks now go through it; every status,
  measurement, note and result order on a device where the checks work is unchanged, including the
  pre-API-29 `NOT_SUPPORTED` thermal row and the `0 camera module(s) reported` row a device with no
  camera service produces. One deliberate difference, and only on a path that throws: the camera
  check caught a throwing `cameraIdList` itself and reported **FAILED** with "no modules … a strong
  indicator of a hardware fault" — a verdict about the hardware drawn from an exception rather than
  from a measurement. It now reports **UNVERIFIED** with the reason, like the other five, so nothing
  is called a fault that was not measured. No file under `ui/` or `res/` is touched: nothing on
  screen changes on any path that works today, and the only differences are on paths that used to
  lose the whole pass or to accuse the hardware of a fault it never reported.
  *Tests:* 11 in the new `AutomatedChecksTest` (Robolectric, API 34). Eight pin the pass itself: the six
  check ids and their order; every result carrying a title and either a measurement or an
  explanation; storage and memory reporting `N% free (X of Y)`; thermal mapping `THERMAL_STATUS_NONE`
  to PASS with "No throttling"; camera enumeration failing exactly when the reported count is zero;
  accelerometer liveness never passing without a vector and never measuring one when it does not
  pass; charging unverified exactly when the broadcast carries no temperature, and always naming the
  power source. The eleventh is the finding itself, driven end to end: a `Context` whose
  `getSystemService(POWER_SERVICE)` throws now yields all six results with the thermal row UNVERIFIED
  and the note "Could not read the thermal state: power service unavailable", while storage and
  memory still carry their measurements. That test cannot pass on the previous code — the exception
  escaped the pass there — so it is the regression test for this item. Two further tests pin the
  helper directly: a throwing block becomes an UNVERIFIED row, and a successful one is returned
  unchanged (`assertSame`).
  *Not verified:* no device is available, so the platform branches Robolectric does not model are
  pinned by fixture rather than by a real handset: its sensor list has no accelerometer, so the
  accelerometer **PASS** branch (and the `x=… y=… z=… m/s²` measurement it asserts) is not exercised;
  its camera list is empty, so the camera **PASS** branch is not exercised either; and the thermal
  **WARNING**/**FAILED** thresholds (MODERATE and above) are verified by inspection only, and the
  changed camera path (a `cameraIdList` that throws) is covered by the helper tests but not driven
  end to end. The isolation itself is verified for one service (the power service) end to end and
  for the helper directly; the other three bare reads are now inside the same helper by
  construction.
  `assembleDebug` green; **372** unit tests pass (361 before, 11 new);
  `tools/check-no-ui-change.sh` clean — the diff is one modified production file, one new test file
  and this entry. Debug APK 9,900,073 bytes, with no size claim: the debug APK is not byte-reproducible
  on this toolchain (recorded under J1/J2), and no pre-change baseline was rebuilt this run.

## Y. Audit findings — 11 November run, second pass

The eighteen open items above were re-read before anything new was added, and the answer had not
changed. Blocked by this sandbox: F1 and F3 (2 cores / 2,098 MB; neither `minifyReleaseWithR8` nor
`lintAnalyzeDebug` finishes inside one 300 s call or resumes across calls), and I8 with them.
Duplicates of numbers already recorded next to their items: G1, I10. Would change what the user reads
on screen or in the exported report: H2, H5, H7, H9, H11, H13, M2, M3, M4. Needs reference data the
app does not carry: H14. Moves a reported verdict and needs sign-off: L2, P2, V2. Nothing was ticked
that was not done.

*Environment note for the rest of the window (not a repository change):* this sandbox's cold
`assembleDebug` had been failing to finish inside a single 300 s call on every attempt today, so the
build was the blocker before any item was. It fits now, at **4m 33s**, once `org.gradle.jvmargs` in
`$GRADLE_USER_HOME/gradle.properties` is
`-Xmx1200m -XX:MaxMetaspaceSize=512m -XX:TieredStopAtLevel=1 -XX:+UseParallelGC -XX:ReservedCodeCacheSize=96m`.
The `TieredStopAtLevel=1` is what buys the time: the build daemon is single-use, so C1-only JIT
removes most of the JIT cost from a JVM that runs one compile and exits. Keep the argument set
*identical* between calls — passing a different `-Dorg.gradle.jvmargs` on the command line invalidates
the configuration cache and costs ~200 s of reconfiguration, which is most of a call.

So the code was read again along the path a scan actually walks — the collectors that turn framework
calls into `SpecField` rows — and one finding cleared the bar.

- [x] **Y1. Three connectivity blocks re-emitted a key and discarded rows they had already read.**
  `ConnectivityCollector` builds the connectivity section as independent rows, but three of its
  blocks read several values inside one `try`: Bluetooth state plus adapter name, the active
  network's transport plus its internet capability, and the telephony block's SIM state, operator
  name and phone type. A throw from the second read did two things at once. The enclosing `catch`
  replaced the whole block with one "Unreadable" row, so a value that had been read successfully
  (the SIM state, the active transport, the Bluetooth state) was thrown away; and it re-emitted a key
  that was already in the list, so the snapshot carried the same `SpecField` key twice and the JSON
  export listed two different values for one property, one of them a measurement and one of them a
  failure. The telephony block could reach this on a device whose operator-name or phone-type read
  is refused, and the network block on a device whose `hasCapability` throws.
  *Change:* a small `Reading`/`attempt` helper (`internal`, top level in the same file) runs one
  framework read and returns either the value or the exception, so a failure costs the one row it
  belongs to. The three blocks now read each value through it and emit each key from exactly one
  branch, which makes a duplicate key structurally impossible. Rows that worked are unchanged: the
  same label, group, source, confidence, status and value as before, and the same failure notes
  ("Unreadable: …", and the existing "Requires the Nearby devices permission …" and
  "Wi-Fi state unreadable: …" wording) on the paths that throw. No file under `ui/` or `res/` is
  touched, so nothing on screen changes on any path that works today.
  *Tests:* 7 in the new `ConnectivityCollectorTest` (Robolectric, API 34). Four pin the helper: a
  successful read carries no failure; a throwing read becomes a `Reading` instead of escaping; a read
  that returns null is *not* a failure, because "the platform has no value" and "the platform
  refused" have to stay distinguishable; and the failure note keeps the wording the blocks already
  used. Three pin the section itself: no field key is emitted twice; the expected sixteen rows are
  each present; and any row with no value is `UNVERIFIED` rather than a measurement. The
  duplicate-key test fails on the previous code for a throwing read but not on a working device,
  which is the point of the item — the defect was on a path, not in the normal output.
  *Not verified:* no device is available, so the throwing paths themselves are not driven end to end.
  Robolectric's `TelephonyManager` does not throw, and a `Context` wrapper cannot substitute a
  `TelephonyManager` whose `getNetworkOperatorName` refuses, so the three re-emitted-key paths are
  verified by construction (each key now has exactly one emitting branch) and by the helper's tests
  rather than by a test that makes the framework call throw.
  `assembleDebug` green; **379** unit tests pass (372 before, 7 new);
  `tools/check-no-ui-change.sh` clean — the diff is one modified production file, one new test file
  and this entry. Debug APK 9,827,992 bytes, with no size claim: the debug APK is not byte-reproducible
  on this toolchain, and the 9,900,073-byte figure recorded under X1 is a different build of a
  different commit.

## Z. Audit findings — 11 November run, third pass

The eighteen open items above were re-read before anything new was added, and the answer had not
changed. Blocked by this sandbox: F1 and F3 (2 cores / 2,098 MB; neither `minifyReleaseWithR8` nor
`lintAnalyzeDebug` finishes inside one 300 s call or resumes across calls), and I8 with them.
Duplicates of numbers already recorded next to their items: G1, I10. Would change what the user reads
on screen or in the exported report: H2, H5, H7, H9, H11, H13, M2, M3, M4. Needs reference data the
app does not carry: H14. Moves a reported verdict and needs sign-off: L2, P2, V2. Nothing was ticked
that was not done.

So the collectors were read again along the path a scan walks — the ones that turn framework calls
into `SpecField` rows — and three findings cleared the bar. The first was implemented in this run; the
other two are specified below for the next pass.

- [x] **Z1. A camera service that refused to enumerate was reported as a device with no cameras.**
  `CameraCollector.collect` read the id list and built every per-camera row inside one `try`. When
  `CameraManager.cameraIdList` threw, the `catch` emitted the "Camera modules — Not accessible" row
  and then fell through to the section's summary row, which prints
  `"${cameras.size} (… rear, … front)"` — **`0 (0 rear, 0 front)`**, as a VERIFIED measurement with
  HIGH confidence. One report therefore carried both "camera enumeration failed" and "this device has
  no cameras", and the second is a fact the app never observed: a camera service that is slow, wedged
  or refusing is not a camera-less device. The same empty `cameras` list feeds the *Cameras enumerated*
  input of the hardware score, so the false zero also reached a score.
  *Change:* the enumeration is now its own read — `cameraIds(manager)`, a `CameraManager -> List<String>`
  constructor parameter that defaults to `it.cameraIdList.asList()`, the same shape as
  `SystemPropertyReader(dumpLoader)` — so a throw is caught around the read alone and recorded as
  `enumerationFailure`. The per-id `try`/`catch` and every row it produces are untouched, and so is the
  "Camera modules — Not accessible / Camera enumeration failed: …" row, which keeps its label, status
  and wording. The summary row is emitted from one branch: unchanged
  (`"N (n rear, n front)"`, VERIFIED, HIGH, source `CameraManager.cameraIdList`) when the read answered,
  and `Unavailable` / `UNVERIFIED` / `Confidence.NONE` with the note
  "Unreadable: camera enumeration failed: <reason>" when it did not. An exception with no message now
  names its class instead of printing "null". No file under `ui/` or `res/` is touched: a device whose
  camera service answers — including one that legitimately reports zero cameras — renders exactly the
  same rows as before.
  *Not changed, and deliberately:* the score input is left alone. `DefaultScoreEngine` still scores an
  empty camera list as 0, so the hardware score on this path is what it was; correcting the score is a
  reported-value change of the kind M3 and P2 are waiting on sign-off for, and this run only stopped
  the report from asserting a measurement it never took.
  *Tests:* 5 in the new `CameraCollectorTest` (Robolectric, API 34), driving the collector through the
  injected reader. A refused enumeration yields an UNVERIFIED summary row with no measurement and the
  note naming the reason; the refused section is exactly two rows (`camera.list`, `camera.count`) and
  the first still reads "Not accessible" / NOT_SUPPORTED / "Camera enumeration failed: binder died";
  an exception with no message still names `IllegalStateException`; an enumeration that answers with
  nothing still reports `0 (0 rear, 0 front)` VERIFIED with no note, because that is a measurement; and
  the summary row keeps its label, group and source on the real Robolectric camera service. Two of the
  five fail against the previous summary-row logic — checked by restoring it (`if (true)` in place of
  the branch) and re-running the class: `a refused enumeration is not reported as a device with no
  cameras` and `an exception with no message still names what failed` both FAILED, then the file was
  restored and the full suite re-run green.
  *Not verified:* no device is available, so the throwing read is driven through the injected lambda
  rather than by a real `CameraManager` that refuses; the per-camera rows (megapixels, sensor array,
  aperture, focal length, flash, level, pixel size, physical sub-cameras) are not exercised at all,
  because Robolectric's camera service enumerates no ids and a `CameraCharacteristics` cannot be
  fabricated for a test.
  `assembleDebug` green with 0 compiler warnings; **384** unit tests pass (379 before, 5 new);
  `tools/check-no-ui-change.sh` clean — the diff is one modified production file, one new test file
  and this entry. Debug APK 9,827,992 bytes, with no size claim: the debug APK is not byte-reproducible
  on this toolchain, and that figure is also the one recorded under M5 and Y1 for different commits.

- [x] **Z2. A sensor service that refuses enumeration is reported as a device with no sensors.**
  `SensorCollector.collect` wraps `manager.getSensorList(Sensor.TYPE_ALL)` in `try`/`catch` that
  returns `emptyList()`, and the section then emits "Sensors enumerated: `0`" through
  `FieldBuilder.value` — VERIFIED, HIGH confidence — plus one "Not reported by the sensor service" row
  for each of the seven expected categories. This is Z1's defect in another collector: a refused or
  throwing read becomes a measurement of absence, and the empty fact list also reaches
  *Sensors enumerated* in `DefaultScoreEngine` (which treats an empty list as missing evidence, so the
  score is not the problem — the row is). *Change:* catch around the `getSensorList` call alone,
  record the failure, and emit `sensor.count` as `Unavailable`/`UNVERIFIED` with an
  "Unreadable: sensor enumeration failed: …" note on that path only, exactly as Z1 does for cameras;
  the seven category rows keep their wording, since "not reported" is still true. *Verify:* a
  `SensorCollectorTest` that drives a throwing enumeration through an injected reader (same shape as
  Z1's `cameraIds`), asserting the count row is UNVERIFIED with no value on the failure path and
  `0`-style VERIFIED when the read answers with an empty list. No visible change on any path that
  works today.
  *Done (11 Nov run, fourth pass):* the enumeration is now its own read, `sensorList(manager)` — a
  `SensorManager -> List<Sensor>` constructor parameter that defaults to
  `it.getSensorList(Sensor.TYPE_ALL)`, the same shape as Z1's `cameraIds` — so a throw is caught
  around the read alone and recorded. `sensor.count` is emitted from one branch: unchanged
  (`logicalSensorCount(facts)`, VERIFIED, HIGH) when the read answered, and `Unavailable` /
  UNVERIFIED / `Confidence.NONE` with the note "Unreadable: sensor enumeration failed: <reason>"
  when it did not; an exception with no message names its class instead of printing "null". The
  seven category rows, the per-sensor detail rows and every label, group and source string are
  unchanged, and no file under `ui/` or `res/` is touched. The hardware score is deliberately left
  alone: `DefaultScoreEngine` already treats an empty sensor list as missing evidence, so the score
  on this path is what it was. Tests: 5 in the new `SensorCollectorTest` (Robolectric, API 34),
  driving the collector through the injected reader; two of them fail against the previous
  count-row logic (checked by restoring it and re-running the class), so they are the regression
  tests for this item. `assembleDebug` green with no compiler warnings; 389 unit tests pass (384
  before, 5 new); `tools/check-no-ui-change.sh` clean.

- [x] **Z3. A refused audio device list is reported as "no speaker, no microphone".**
  `AudioCollector.collect` reads `getDevices(GET_DEVICES_OUTPUTS)` and `…INPUTS` in two `try`/`catch`
  blocks that both collapse to `emptyList()`, and the three presence rows then print
  "Not reported" for the built-in speaker, earpiece and microphone — a claim of absence drawn from a
  read that threw. The two list rows ("Audio outputs", "Audio inputs") also fall to `Unavailable`,
  which is honest, so the section contradicts itself on that path. *Change:* read each list through
  the `attempt`/`Reading` helper Y1 added to `ConnectivityCollector` (or a local equivalent), emit the
  presence rows from the values that were actually read, and let a failed read say
  "Unreadable: …" instead of "Not reported". *Verify:* an `AudioCollectorTest` driving a throwing
  reader, asserting the presence rows carry no "Not reported" claim when the list read failed, and
  that a working read still prints "Present"/"Not reported" exactly as today. No visible change on any
  path that works today.
  *Done (11 Nov run, fifth pass):* each list is now its own read — `outputDevices` and `inputDevices`
  are `AudioManager -> List<AudioDeviceInfo>` constructor parameters that default to
  `getDevices(GET_DEVICES_OUTPUTS)` and `…INPUTS`, the same shape as Z1's `cameraIds` and Z2's
  `sensorList` — and each goes through Y1's `attempt`, so a throw is caught around one read and keeps
  its reason instead of collapsing to `emptyList()`. A presence row is emitted from one helper,
  `presence(...)`: unchanged ("Present" / "Not reported", VERIFIED, HIGH) when the list it depends on
  answered, and `Unavailable` / UNVERIFIED / `Confidence.NONE` with the note
  "Unreadable: audio <output|input> device list failed: <reason>" when it did not, so the three
  presence rows no longer contradict the two list rows above them. An exception with no message names
  its class rather than printing "null". The two list rows keep their values and gain that same note
  on the failure path only. Every label, group, source string, key and the `Present`/`Not reported`
  wording on a working read are byte-identical, and no file under `ui/` or `res/` is touched. The
  `AudioFacts.speakerAvailable`/`microphoneAvailable` flags deliberately keep their previous value
  (false) on a failed read, so the hardware score on that path is what it was — the same decision Z2
  recorded for the sensor count. Tests: 6 in the new `AudioCollectorTest` (Robolectric, API 34),
  driving the collector through both injected readers — the refused output list, the refused input
  list, one list failing while the other answers, an exception with no message, an answered-but-empty
  list still rendering exactly today's values, and the flags staying false. **Mutation-checked, not
  just green:** with `presence(...)` restored to the old unconditional
  `if (present) "Present" else "Not reported"`, exactly the three failure-path tests fail
  (`a refused output list…`, `a refused input list…`, `an exception with no message…`) and the three
  unchanged-path tests still pass; the file was then restored and is byte-identical to the committed
  one. `assembleDebug` green with no compiler warnings; **395** unit tests pass (389 before, 6 new),
  re-executed with `--rerun` rather than taken from the build cache; `tools/check-no-ui-change.sh`
  clean (31 files tracked under `ui/` and `res/`, none differs) — the diff is one modified collector
  and one new test file.
  *Recorded while writing it, not fixed here:* `AudioCollector` has the same defect in the vibrator
  probe two rows below — its `try`/`catch` around `Vibrator.hasVibrator()` returns `false`, so a
  refused haptics read prints "Not reported" for the vibration motor. It is the same fix (an injected
  reader plus a `Reading`), but it is a different row and belongs to its own item rather than to this
  one.

## AA. Audit findings — 11 November run, fourth pass

The nineteen items open at the start of this run are blocked here for the reasons Q and W recorded,
and were re-read rather than re-fixed: **F1** and **F3** are sandbox-bound again (`nproc` = 2,
`free` = 2,098 MB — this run's cold `assembleDebug` needed six 300 s calls and `testDebugUnitTest`
two more, so neither the non-resumable `minifyReleaseWithR8` nor `lintAnalyzeDebug` has a window);
**G1** and **I10** are duplicates of measurements already recorded, as Q and W set out; **I8** duplicates F3;
**H2, H5, H7, H9, H11, H13, H14, M2, M3, M4** need a visible change (a rendered value, a new row or
a re-flowed PDF); **L2, P2, V2** need sign-off because they move a reported verdict. That left **Z2**
and **Z3**, which are Z1's defect in two more collectors and were written as implementable. Z2 was
taken (first of the two, and the direct sequel to the previous run's camera fix) and is ticked
above; Z3 is the same fix for `AudioCollector` and stays open for the next pass.

One environment fact worth recording, because it cost this run three test cycles: `testDebugUnitTest`
could not download Robolectric's runtime — the forked test JVM fetches `android-all-instrumented`
over a plain `HttpURLConnection` that the sandbox proxy refused (`MavenArtifactFetcher` →
`IOException`) — and the build already supports the offline path
(`ROBOLECTRIC_DEPENDENCY_DIR` → `robolectric.offline` + `robolectric.dependency.dir`). Fetching the
jars with `curl` from `repo1.maven.org` into one directory and exporting that variable fixes it; the
suite needs one jar per SDK the tests name, and the ones used this run were 5.0.2_r3 through
15-robolectric-12650502, all at the `-i7` suffix for Robolectric 4.14.1.

## BB. Audit findings — 11 November run, fifth pass

The list was re-read before anything was touched, and every item above Z3 was passed over for the
reason already recorded against it, not by assumption: **F1** and **F3** remain sandbox-bound
(`nproc` = 2, `free` = 2,098 MB — this run's cold `assembleDebug` needed four 300 s calls and the
first green test run three more, so neither the non-resumable `minifyReleaseWithR8` nor
`lintAnalyzeDebug` has a window here); **G1** and **I10** duplicate measurements already recorded next
to their items; **I8** duplicates F3; **H2, H5, H7, H9, H11, H13, H14, M2, M3, M4** need a visible
change (a rendered value, a new row or a re-flowed PDF); **L2, P2, V2** need sign-off because they
move a reported verdict. That left **Z3**, the last of Z1's defect in the collectors, which was
implemented in this run and is ticked above.

One build fact worth recording for the next pass: this sandbox's cold configuration-cache
calculation alone does not finish inside a 300 s call, and a killed build discards it, so every
retry restarted from zero — `gradle assembleDebug --dry-run` stores the entry in 1m34s and each later
call then reuses it, which is what made a green build possible here at all. Robolectric's runtime
also had to be pre-fetched again (`ROBOLECTRIC_DEPENDENCY_DIR`, jars for API 29, 30, 33, 34 and 35
from `maven-central.storage-download.googleapis.com`, since `repo1.maven.org` answered 429), and
`org.robolectric:nativeruntime-dist-compat:1.0.16` had to be served from a local file repository
through a sandbox-only init script. No build file, repository list or dependency changed.

## CC. Audit findings — 11 November run, sixth pass

The list was re-read before anything was touched, and every open item was passed over for the reason
already recorded against it, not by assumption: **F1**, **F3** and **I8** are still sandbox-bound
(`nproc` = 2, `free` = 2,098 MB; a cold `assembleDebug` needed nine 300 s calls this run, so the
non-resumable `minifyReleaseWithR8` and `lintAnalyzeDebug` still have no window); **G1** and **I10**
duplicate measurements already recorded next to their items; **H2, H5, H7, H9, H11, H13, H14, M2,
M3, M4** need a visible change (a rendered value, a new row, a re-flowed PDF or a progress count);
**L2, P2, V2** need sign-off because they move a reported verdict or make the report briefly stale.
That left one fresh audit finding on the scan's error path, which is implemented in this run.

- [x] **CC1. `StageTimer.run` cannot tell a stage's own `CancellationException` from a cancelled
  scan.** The catch block treated every `CancellationException` that came out of `work.await()` as
  "the caller was superseded" and rethrew it, so a stage that gave up on its own — a collector that
  wraps a probe in `withTimeout`, a Binder helper whose job is cancelled by the framework, a
  `suspendCancellableCoroutine` cancelled from a callback — abandoned the remaining collection
  stages and surfaced in `AppViewModel` as `Scan failed: ...` with no snapshot at all. That
  contradicts the class's own contract ("Never throws for a stage failure or a timeout; only
  cancellation of the calling coroutine propagates") and the promise the whole timer exists for,
  that a scan always finishes with whatever it could read. *Change:* after cancelling the abandoned
  stage, the caller's own job state decides — `currentCoroutineContext().isActive` false means the
  scan really was superseded, so the exception is rethrown and the scan unwinds as before; active
  means the stage failed, so it is returned as `StageOutcome.Failed` and the scan carries on. The
  superseded-scan path is unchanged, which is what `ScanCoordinator` and `AppViewModel` depend on.
  *Tests:* two new cases in `StageTimerTest` — a stage that throws `CancellationException` is a
  stage failure and the next stage still runs, and a caller that is cancelled mid-stage still
  unwinds instead of being recorded as a stage failure (10 tests in that class now, 8 before).
  *Honest scope:* no collector throws `CancellationException` today — all nine stages are blocking
  Java calls — so this fixes a latent path rather than a reproduced crash. It is worth pinning
  because `SensorProbe` already uses `withTimeout` in the diagnostics path and the next collector
  that adopts that pattern would otherwise take the whole scan down with it.
  `assembleDebug` green; the debug APK is 9,827,992 bytes before and after (no size claim — this
  build is not byte-reproducible, see J1/J2). All **52** unit test classes pass, run in six groups
  because the suite does not fit in one 300 s call here. No UI, copy or layout change: the diff is
  `StageTimer.kt`, `StageTimerTest.kt` and this file, and `ScreenshotTest` re-renders every published
  screen green.

Two build facts for the next pass. This pod was killed once mid-run while a 1750 MB Gradle daemon was
kept alive across calls next to the test JVM, taking the whole workspace with it; with
`org.gradle.daemon=false` (the repo default) a call is slower but the pod survives, so the heap bump
belongs on the command line for one call at a time, not in a long-lived daemon. Robolectric again had
to be pre-fetched (`ROBOLECTRIC_DEPENDENCY_DIR`; jars for API 29, 30, 33, 34 and 35, in 20 MB range
requests from `maven-central.storage-download.googleapis.com` because `repo1.maven.org` answers 429),
and `org.robolectric:nativeruntime-dist-compat:1.0.16` plus its POM had to be served from a local file
repository through a sandbox-only init script. No build file, repository list or dependency changed.

## DD. Audit findings — 12 November run, seventh pass

The list was re-read first. **F1** is still blocked by this host (recorded against it above); **F3**
and **I8** are the same `lintAnalyzeDebug` task on the same non-resumable footing; **G1** and **I10**
duplicate measurements already recorded next to their items; **H2, H5, H7, H9, H11, H13, H14, M2,
M3, M4** need a visible change (a rendered value, a new row, a re-flowed PDF or a progress count);
**L2, P2, V2** move a reported verdict or make the report briefly stale and need sign-off. That left
the audit below, which is implemented in this run.

- [x] **DD1. The "Enabled location providers" row still loses a reading it had already taken.**
  `ConnectivityCollector` reads the location row with one `listOfNotNull` over two
  `LocationManager.isProviderEnabled` calls. That method throws `IllegalArgumentException` for a
  provider the platform does not publish — a TV box, a set-top box or any device without a GNSS
  receiver has no `gps` provider, and the manifest deliberately lets the app install on those — so
  the absent provider's throw discarded the *other* provider's answer and the enclosing `catch`
  replaced the whole row with "Unreadable: Provider \"gps\" doesn't exist". A device with network
  location enabled and no GNSS therefore reported that it could not read the row at all. This is the
  last instance of the fault **Y1** fixed in the other connectivity blocks in this same file: there
  the throw fell *between* emitted rows, here it falls *inside* one row's construction, which is why
  the earlier pass did not reach it. *Change:* each provider is read through the existing `attempt`
  helper and the row is built by a new pure `locationProviderRow(gps, network)`. A provider that was
  read decides the value; a provider that threw contributes nothing; when neither reports enabled the
  row says "None enabled" only if both reads actually happened, and otherwise reports the failure
  instead of a measurement it never took. Every combination the row could already render renders the
  same text — "GPS", "Network", "GPS, Network", "None enabled", "Unreadable: …" and the
  "Capability only" note are unchanged — so no working device sees any difference; only the reading
  that used to be thrown away is now reported. *Tests:* six cases in `ConnectivityCollectorTest`
  (both enabled, both disabled, GPS absent, network absent, one unread plus the other disabled, both
  absent), which is 13 tests in that class, up from 7.

## EE. Audit findings — 12 November run

The eighteen items still open above were re-read against the code before anything new was added, and
none of them can be finished here. Blocked by this sandbox (F1, F3, I8): `nproc` = 2 and 2,098 MB again,
this run's cold `assembleDebug` needed eight 300 s calls, and neither `minifyReleaseWithR8` nor
`lintAnalyzeDebug` finishes inside one call or resumes across calls. Duplicates of work already recorded
(G1, I10). Need a visible change (H2, H5, H7, H9, H11, H13, M2, M3, M4). Need data the app does not carry
(H14). Need sign-off because they move a reported verdict (L2, P2) or change what an export can contain
(V2). Nothing was ticked that was not done.

The patch-level path was then read — the one input that feeds three different consumers — and one finding
cleared the bar. EE1 was implemented in this run.

- [x] **EE1. A security patch level that is not a real year and month was silently rolled into a
  different one — in three separate copies of the same parse.** `DefaultIntegrityAnalyzer.parsePatchDays`,
  `DefaultAgeEstimator.parseSecurityPatch` and the inline block in `DefaultScoreEngine`'s
  Software-and-security section each built a **lenient** `java.util.Calendar` from
  `value.substring(0, 4)` and `value.substring(5, 7)` and set the day to 1. `Calendar` rolls an
  out-of-range field over instead of refusing it, so a patch level the build never published as a date
  became a plausible one, and always in the direction that flatters the device: measured on JDK 17 with
  `collectedAt` = the test fixture's fixed clock (2025-06-15), `2024-13-01` read as **2025-01-01**
  (165 days old, so the Security screen said PASS, "Security patch 2024-13-01 is 165 d old"), `2024-00-15`
  read as **2023-12-01** (562 days, WARNING), and `2024-99-01` read as **2032-03-01**, a date in the
  future that `Formatters.daysBetween` floors to zero — a patch from 2032 reported as 0 days old, PASS.
  Because the three copies were independent, one fix would also have drifted from the other two.
  *Change:* one shared `AnalysisSupport.patchMonthToMillis(value)` — the same year/month parse, but
  `isLenient = false`, an explicit `month !in 1..12` refusal, an explicit length guard, and `null` for
  anything it cannot read — and all three consumers now call it. Only the year and month are read and the
  day is still pinned to the 1st, so **a valid patch level produces exactly the instant it always did**;
  this only refuses a value that is not a date, which is the same thing N1 did for reference release
  dates. A refused value takes the path an absent patch level already took: the Security check reports
  UNVERIFIED with "No security patch date was reported by the build.", the age estimate reports no
  `daysSinceSecurityPatch`, and the score excludes the input instead of scoring it 100. The raw string is
  still printed as the check's evidence (`ro.build.version.security_patch=2024-13-01`), so nothing is
  hidden from the user — the row still shows what the build claimed, it just no longer invents a date
  from it. *Tests:* seven in the new `SecurityPatchDateTest`: a full date and a year-month both read as
  the 1st of their month, the day component does not move the result, months 13/0/99 are refused, and
  null/blank/short/non-numeric/no-dash forms are refused; then one test per consumer — the integrity
  check reports UNVERIFIED with the unchanged explanation and evidence for `2024-13-01`, the age estimate
  reports no patch age for it while a real one still does, and the Software-and-security score for
  `2024-13-01` equals the score for an absent patch level. *Verified:* `assembleDebug` green (APK
  9,827,992 bytes), all **410** unit tests pass (403 before, 7 new), and no `ui/` or `res/` file is
  touched, so no screen, control, style, layout, copy or reported value changes on a device that
  publishes a real patch level. *Environment note:* Maven Central's redirect target
  (`gcp.mirrors.maven.org`) answered **502** for the whole of this run, so `testDebugUnitTest` could not
  resolve Robolectric's `nativeruntime-dist-compat` or its `android-all` jars. A sandbox-local Gradle
  init script (outside the repository, nothing in the tree changed) pointed Gradle and Robolectric at
  `maven-central.storage-download.googleapis.com`, which serves the same artefacts. One `android-all`
  jar arrived truncated through the broken mirror and failed `PermissionManagerTest` with a
  `NoSuchFieldError` inside Robolectric's own `DeviceConfig`; deleting that jar and letting it re-download
  made the class pass, and the failure was never in app code. *Not claimed:* nothing about a device that
  publishes a malformed patch level beyond the above — no device is available here, so the three call
  sites are verified through the unit tests that drive them, not on hardware.

## FF. Audit findings — 12 November run (second pass)

The eighteen items still open above were re-read first, in order, and every one of them is still
blocked for the reason already written beside it: this sandbox (F1, F3, I8 — 2 cores, 2,098 MB, where
a cold `assembleDebug` alone took six 300 s calls this pass and neither `minifyReleaseWithR8` nor
`lintAnalyzeDebug` is resumable), duplication of a measurement that already lives next to its item
(G1, I10), a visible change (H2, H5, H7, H9, H11, H13, M2, M3, M4), data the app does not carry
(H14), or a decision that moves a reported verdict (L2, P2, V2). So the code was read again with the
same lens, and one finding cleared the bar: a read that never answered, published as a measurement of
absence. It is the same defect Z1, Z2 and Z3 fixed for cameras, sensors and audio, in the one
collector that was missed.

- [x] **FF1. A refused package list is reported as a device with no installed apps.**
  `SoftwareCollector.collect` wrapped `PackageManager.getInstalledPackages(0)` in a `try` whose
  `catch` only flipped a local `visible` flag, leaving both counters at zero; the three rows built
  from those counters then published `"Installed apps visible to this app: 0"`, `"System apps: 0"` and
  `"User-installed apps: 0"` — three VERIFIED values with HIGH confidence and DIRECTLY_VERIFIED
  provenance, on a device whose package list was never read. The only note attached said the count
  was a lower bound because Android 11 hides other apps, which is a claim about a count that was
  taken. A `PackageManager` that throws (a wedged system server, a vendor build with a broken package
  service, a `RemoteException` from the Binder) therefore produced a clean-looking software section
  and a `packageVisibilityRestricted = false` fact that nothing had measured. *Change:* the read is a
  constructor parameter (`(PackageManager) -> List<PackageInfo>`, defaulting to the same
  `getInstalledPackages(0)`) so the failure can be driven by a test, the `catch` records the reason
  instead of flipping a flag, and the three rows report no value with `"Unreadable: package list
  failed: <reason>"` when the read threw. A list that answered — including an empty one — reports
  exactly what it read, byte for byte as before, and `packageVisibilityRestricted` is now derived from
  "the read succeeded", which is what it always meant. No new row, label, note on a readable device,
  screen, control, style or layout: the only difference is on a device whose package list cannot be
  read at all, where a false "0 apps" becomes "Unavailable" plus the reason. Four tests in
  `SoftwareCollectorTest`: a refused list, an exception with no message, a list that answers (2 system
  + 1 user → "3"/"2"/"1", VERIFIED, the lower-bound note still present) and an empty but answered
  list (still "0", so the failure path cannot swallow a genuine zero). *Verified:* `assembleDebug`
  green, debug APK **9,931,821 → 9,932,153 bytes** (+332, measured back to back with the change
  stashed and restored on the same tree), all **414** unit tests pass (410 before, 4 new),
  `tools/check-no-ui-change.sh` reports `OK … (31 files tracked there)`. *Not claimed:* nothing about a
  real handset — no device is available here, so the failure path is verified through the injected
  read, and the success path through the same injection plus the untouched production default.
  *Environment note:* the whole of this pass, `gcp.mirrors.maven.org` — where Maven Central redirects —
  answered **502**, so neither Gradle's `nativeruntime-dist-compat` nor Robolectric's `android-all`
  jars could be fetched from it. A sandbox-local Gradle init script (outside the repository; nothing in
  the tree changed) resolved both through `maven-central.storage-download.googleapis.com` and handed
  the five `android-all` jars to Robolectric's offline resolver. Every number above was taken with
  that workaround in place.

## GG. Audit findings — 12 November run (third pass)

The eighteen items still open above were re-read first, in order, and every one of them is still
blocked for the reason already written beside it: this sandbox (F1, F3, I8 — `nproc` = 2,
`free` = 2,098 MB, where this pass's cold build took several 300 s calls and neither
`minifyReleaseWithR8` nor `lintAnalyzeDebug` is resumable), duplication of a measurement that
already lives next to its item (G1, I10), a visible change (H2, H5, H7, H9, H11, H13, M2, M3, M4),
data the app does not carry (H14), or a decision that moves a reported verdict (L2, P2, V2). So the
code was read again with the same lens, and one finding cleared the bar: the crash-safe write's
temporary file was shared between writers, which is the one place in the app where two taps can
reach the same file at once.

- [x] **GG1. Two writers of one export could spoil it.** `atomicWriteText` derived its temporary
  sibling's name from the target's (`<name>.tmp`), so two writers of the same target opened the
  **same** temporary file. Whichever renamed first moved that inode out from under the other; the
  other then kept writing through its open descriptor into what was already the finished target —
  appending the rest of its own document past the end of a complete one — and finally failed its
  own rename with "could not replace …". The JSON export is exactly this case:
  `AppViewModel.exportJson` launches a coroutine per tap and both taps write
  `cacheDir/verify-device-info-report.json`, so a double tap on "Export JSON" was two writers of one
  file, and the file the user then shared could be one document with a second document's tail stuck
  on it. *Change:* the temporary file is created with
  `File.createTempFile("${file.name}.", ".tmp", dir)`, so every write owns its own name, created
  exclusively, in the target's own directory — a rename inside one directory is still atomic.
  Everything else is byte-for-byte as before: the same flush and `fd.sync()`, the same rename over
  the target, the same deletion of the temporary file when anything fails, and the same behaviour
  when the directory cannot be created at all. Two writers still race to rename; the last complete
  document wins, which is the only outcome this helper ever promised. *Tests:* new
  `AtomicWriteConcurrencyTest` releases six writers from a barrier, each writing a distinct 200 KB
  payload to the same target, ten rounds over: every writer must return, the target must be exactly
  one writer's payload end to end, and no temporary file may survive. Against the previous code it
  fails at round 0 with `java.io.IOException: could not replace verify-device-info-report.json`;
  against this change it passes. A second test pins the single-writer case (exact bytes, no
  leftovers). Three existing tests obstructed the write by creating a directory *at* the old
  temporary name, which now blocks nothing: `HistoryIndexWriteTest` ×2 make the index's directory
  read-only instead, `HistoryIndexFailureTest` ×3 do the same for `history/` after creating
  `history/reports/` (so the report write is untouched and those tests stay about the index alone),
  and `JsonExportWriteTest`'s leftover check now asserts that no file survives rather than one
  particular name. *Verified:* `assembleDebug` green (debug APK 9,827,992 bytes); all **416** unit
  tests pass (414 before, 2 new), run as four filtered batches because one `testDebugUnitTest` over
  the whole suite does not finish inside a single 300 s call here; `tools/check-no-ui-change.sh`
  reports `OK … (31 files tracked there)`. No screen, control, style, layout, copy or reported value
  changes. *Not claimed:* nothing about a real handset — the race is reproduced on the JVM
  filesystem, which is the same `rename(2)` semantics Android uses, and the fix does not depend on
  the platform either way. *Environment note:* Maven Central's redirect target
  `gcp.mirrors.maven.org` answered **502** again, so a sandbox-local Gradle init script (outside the
  repository) resolved through `maven-central.storage-download.googleapis.com`, and Robolectric's
  `android-all` jars were fetched through the same mirror via
  `-Drobolectric.dependency.repo.url`. Nothing in the tree was changed for either.

## HH. Audit findings — 13 November run

The eighteen items open at the start of this run were re-read before anything was touched, and each
was passed over for the reason already recorded against it: **F1** and **F3** are sandbox-bound
(`nproc` = 2, `free` = 2,098 MB — this run's cold `assembleDebug` needed four 300 s calls, so neither
the non-resumable `minifyReleaseWithR8` nor `lintAnalyzeDebug` has a window here), and **I8**
duplicates F3; **G1** and **I10** duplicate measurements already recorded next to their items;
**H2, H5, H7, H9, H11, H13, H14, M2, M3, M4** would change what the user reads on screen or in the
exported report; **L2, P2, V2** move a reported verdict and need sign-off. Nothing was ticked that
was not done.

One finding had already been specified inside an earlier entry and never given an item of its own —
Z3 recorded it while it was being written, as the same defect in the one read of that file it did not
cover. It was implemented in this run.

- [x] **HH1. A refused haptics read is reported as a device with no vibration motor.**
  `AudioCollector.collect` read the vibration motor inside a `try`/`catch` that returned `false`
  (`VibratorManager.defaultVibrator` from API 31, the deprecated `Vibrator` below it), and the
  `audio.vibrator` row then printed **"Not reported"** — VERIFIED, HIGH confidence, no note. A
  vibrator service that throws, or a vendor `Vibrator` implementation that refuses, was therefore
  reported as a device without a vibration motor, and the `vibratorAvailable` flag carried the same
  `false` into `AudioFacts`. This is Z3's defect in the last read of the same file: a claim of
  absence drawn from a read that never answered.
  *Change:* the read is now its own constructor parameter, `hasVibrator: (Context) -> Boolean`,
  defaulting to the production path unchanged (the two-branch `VibratorManager`/`Vibrator` lookup,
  moved as it was), the same shape as Z1's `cameraIds`, Z2's `sensorList` and Z3's
  `outputDevices`/`inputDevices`. It goes through Y1's `attempt`, so a throw is caught around that one
  read and keeps its reason. The row is emitted from one branch: unchanged ("Present" /
  "Not reported", VERIFIED, HIGH, source `Vibrator.hasVibrator()`, no note) when the read answered,
  and `Unavailable` / UNVERIFIED / `Confidence.NONE` with the note
  "Unreadable: haptics read failed: <reason>" when it did not. `failureName(...)` — the message, or
  the class name when there is none — is now shared with `readFailureNote`, whose output for the two
  device-list rows is byte-identical to before. The `vibratorAvailable` flag keeps its previous value
  (`false`) on a failed read, so the score on that path is what it was: the same decision Z2 and Z3
  recorded. No file under `ui/` or `res/` is touched — on a device whose vibrator service answers,
  every row renders exactly what it rendered before.
  *Tests:* three in `AudioCollectorTest` (Robolectric, API 34), driven through the injected reader: a
  refused read yields UNVERIFIED with no measurement, the note "Unreadable: haptics read failed:
  vibrator service refused" and the flag false; an exception with no message names
  `IllegalStateException` instead of printing "null"; and a read that answers still prints "Present"
  (flag true) or "Not reported" (flag false), VERIFIED with no note. **Mutation-checked:** with the
  previous logic restored (`try { … } catch { false }` plus the unconditional row), exactly the two
  failure-path tests fail and the seven unchanged-path tests pass; the file was then restored and is
  byte-identical to the committed one.
  *Not verified:* no device is available, so the throwing read is driven through the injected lambda
  rather than by a real `VibratorManager`. Every case injects a reader, so the production lambda
  itself is not executed by these tests; it is the same two-branch code that was there before, moved
  into the parameter default, and on API 34 the `VibratorManager` branch is the one it takes.
  `assembleDebug` green; all **419** unit tests pass (416 before, 3 new), re-executed with `--rerun`
  rather than taken from the build cache; `tools/check-no-ui-change.sh` reports `OK … (31 files
  tracked there)` — the diff is one modified production file, one modified test file and this entry.
  Debug APK 9,827,992 bytes, with no size claim: the debug APK is not byte-reproducible on this
  toolchain. No timing or size measurement is claimed — the change is on a failure path and removes
  no work from the healthy one.
  *Recorded while writing it, not fixed here:* `HardwareCollector.readDisplayFacts` carries the same
  defect two rows over — a throwing HDR read collapses to `emptyList()` and renders "None reported",
  and a throwing `isScreenWideColorGamut` collapses to `false` and renders "Not reported"/"No".
  Correcting it means `DisplayFacts.hdrTypes`/`wideColorGamut` carrying the failure instead of a
  default, which reaches the two rendering sites in `ui/screens/HardwareScreen.kt`, so it is a
  UI-file change and belongs to a design pass. It was not added as an item.

*Environment note, not a repository change:* Maven Central's mirror `gcp.mirrors.maven.org` answered
**502** for `org.robolectric:nativeruntime-dist-compat:1.0.16` again, and Robolectric's test JVM could
not fetch its runtime over the proxy. The jar and the `android-all-instrumented` jars for API 29, 30,
33, 34 and 35 were fetched from `maven-central.storage-download.googleapis.com`, the missing
artefact was served to Gradle through a local file repository in an init script outside the
repository, and the runtime directory was passed with `ROBOLECTRIC_DEPENDENCY_DIR`, which the build
already supports. No build file, repository list or dependency in the tree was changed.

## II. Audit findings — 13 November run (second pass)

The eighteen items open at the start of this run were re-read before anything was touched, and each
was passed over for the reason already recorded against it: **F1**, **F3** and **I8** are
sandbox-bound (`nproc` = 2, `free` = 2,098 MB — this run's cold `assembleDebug` again needed several
300 s calls, and `:app:mergeExtDexDebug` alone took 3m40s with a warm configuration cache, so neither
the non-resumable `minifyReleaseWithR8` nor `lintAnalyzeDebug` has a window here); **G1** and **I10**
duplicate measurements already recorded next to their items; **H2, H5, H7, H9, H11, H13, H14, M2,
M3, M4** would change what the user reads on screen or in the exported report; **L2, P2, V2** move a
reported verdict and need sign-off. Nothing was ticked that was not done.

The export path was then re-read against the crash-safety work of the last two runs (T1, O1, M5,
GG1), and one write in it was still outside every one of those fixes.

- [x] **II1. The PDF export writes the target in place, so two exports of one file spoil it.**
  `writePdfFile` handed the target straight to `FileOutputStream(file)` and let `PdfDocument.writeTo`
  stream into it. That made the PDF the last write in the app that was neither atomic nor per-writer:
  the JSON export was given a temporary sibling and a rename by GG1, the history index by O1, but the
  PDF still wrote in place. "Export PDF" is not disabled while an export runs and
  `AppViewModel.exportPdf` launches a coroutine per tap, so a double tap was two `writeTo` calls
  truncating and growing one file — the two documents interleaved, and whichever writer finished last
  decided what the rest of the file said. A single export interrupted part-way left the same damage
  from the other direction: a truncated PDF sitting at the path the share sheet reads. The failure
  path answered that by deleting the target, which also deleted a *complete* report that an earlier
  export had just written.
  *Change:* a new `writeFileAtomically(file, write)` streams the bytes into
  `File.createTempFile("${target.name}.", ".tmp", dir)` — created exclusively, in the target's own
  directory so the rename stays inside one filesystem — flushes, `fd.sync()`s, and renames it over
  the target. A failure deletes only its own temporary file, so the previous complete export survives
  untouched and the target is never observable half-written. `writePdfFile` now calls it in place of
  the `FileOutputStream(file).use(writeTo)` line and its `file.delete()`; the rest of the lifecycle is
  unchanged — `build` → `finishLastPage` → write → return the target, with `finishLastPage` and
  `closeDocument` still run exactly once in the `finally`. `writePdfFile` lost its `inline` modifier,
  because it now passes its `writeTo` parameter to a non-inline function; nothing depended on the
  inlining. Same flush, same sync, same rename-over-target semantics `atomicWriteText` already
  promises for the JSON export; `atomicWriteText` itself was not touched.
  *Tests:* three new in `PdfWriteLifecycleTest` and two existing ones tightened.
  `concurrentExportsOfOneFileNeverInterleave` releases four exports from a barrier, each emitting a
  payload of a distinct length in 32 KB chunks with a pause between them, while a reader thread polls
  the target and records any content that is neither empty nor exactly one export's document; over six
  rounds every writer must return, the reader must never see a partial document, the target must be
  exactly one payload end to end, and no temporary file may survive. `a failed export keeps the
  previous complete report` pins that a failed write leaves an earlier complete export byte-identical
  (this is the deliberate behaviour change: the old code deleted it). `a completed export leaves no
  temporary file beside the target` pins the success path's cleanup. `a build that throws leaves no
  report file behind` and `a write that throws leaves no partial file behind` keep their verdicts and
  now also assert that no temporary file is left. **Mutation-checked:** with the old in-place write
  restored (`FileOutputStream(target).use(write)`), exactly those three tests fail —
  `concurrentExportsOfOneFileNeverInterleave` with "the target was readable as a partial document",
  the other two on the target's contents — and the seven unchanged-path tests pass; the production
  file was then restored and is byte-identical to the committed one (sha256 `09f49d6f…3e59`).
  *Verified:* `assembleDebug` green, debug APK 9,827,992 bytes (unchanged from the baseline I5
  records — the change adds no code to the APK's hot path and no resource); all **422** unit tests
  pass (419 before, 3 new) in one `testDebugUnitTest` run of 4m11s; `tools/check-no-ui-change.sh`
  reports `OK … (31 files tracked there)`. The diff is one production file, one test file and this
  entry. No screen, control, style, layout, copy, reported value or export content changes: the bytes
  written are the same bytes, only the path they reach the target by differs.
  *Not verified:* no device is available, so `PdfDocument.writeTo` itself is still only exercised on
  the JVM through the fake writer this test file has always used; the rename is `File.renameTo`
  inside one directory, the same `rename(2)` semantics `atomicWriteText` relies on. Nothing is claimed
  about export speed — the sync adds one flush per export, which was not timed here.

*Environment note, not a repository change:* Maven Central's redirect target `gcp.mirrors.maven.org`
answered **502** again for `org.robolectric:nativeruntime-dist-compat:1.0.16`. The jar was fetched
from `maven-central.storage-download.googleapis.com` and served to Gradle through a local file
repository in an init script outside the repository, and Robolectric's `android-all-instrumented`
jars for API 29, 30, 33, 34 and 35 were passed with `ROBOLECTRIC_DEPENDENCY_DIR`, which the build
already supports. No build file, repository list or dependency in the tree was changed.

## JJ. Audit findings — 13 November run

The eighteen unchecked items above are still blocked here, for the reasons their own entries record.
Blocked by this sandbox (F1, F3, I8): `nproc` = 2 and 2,098 MB again, so neither
`:app:minifyReleaseWithR8` nor `:app:lintAnalyzeDebug` fits inside one 300 s call or resumes across
calls. Duplicates of work already recorded (G1, I10). Need a visible change (H2, H5, H7, H9, H11,
H13, M2, M3, M4). Need data the app does not carry (H14). Need sign-off because they move a reported
verdict or a rendered value (L2, P2, V2).

So the collection path was read again, and it produced one finding that clears the bar: a static
field that only exists from API 31, read before the level that guards it. Two further findings from
the same pass are recorded here for a design pass, because each changes a value a user reads and
neither may be done under the UI freeze:

- *For sign-off:* `BatteryCollector` renders `BATTERY_PROPERTY_ENERGY_COUNTER` as `"%.0f mWh"` from
  `energyCounter / 1000.0`, but the platform documents that property in **nanowatt-hours**, so the
  "Energy counter" row is a thousand times too large on every device that reports it. The fix is
  `it / 1_000_000.0` (or a unit of its own); it changes a rendered value, so it needs the same
  decision H1 and K3 took.
- *For sign-off:* `DefaultScoreEngine` still scores "Cameras enumerated" as `0` when
  `hardware.cameras` is empty, and `AndroidDeviceInformationProvider` fills that list with
  `emptyList()` both for a device with no cameras and for a camera stage that failed or timed out —
  the Z1 defect one layer below the field that Z1 fixed. Scoring it as missing evidence (as the
  sensor input beside it already does) moves the Hardware health score, so it needs sign-off.

- [x] **JJ1. `IdentityCollector` read the API-31 SoC fields before checking the API level, so the
  whole scan threw `NoSuchFieldError` below Android 12.** `Build.SOC_MANUFACTURER` and
  `Build.SOC_MODEL` are static fields added in API 31, and a static field is resolved when its
  instruction executes, not when the class is loaded. The two rows were built as
  `Build.SOC_MANUFACTURER.takeIf { Build.VERSION.SDK_INT >= 31 }`: the receiver is evaluated before
  `takeIf` is called, so the field was read first and the predicate that was meant to guard it ran
  only afterwards. On API 26-30 that read throws `NoSuchFieldError`, which is an `Error`:
  `StageTimer.run` catches `Exception`, so the identity stage did not fail gracefully, and
  `AppViewModel.scan` catches `Exception` too, so the scan coroutine died and the process with it.
  With `minSdk` 26 that is every Android 8, 9, 10 and 11 device, on every scan. The same two values
  were read correctly ninety lines below, in the `DeviceIdentity` construction
  (`if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER else null`), which is the shape the rows
  now use too.
  *Change:* one production file, `IdentityCollector.collect`. A single `val socFieldsAvailable =
  Build.VERSION.SDK_INT >= 31` is evaluated first, the two values are read behind it, and the field
  rows and the `DeviceIdentity` both use those locals, so the level is checked in one place and can
  no longer be checked after the read. On API 31+ the rows carry exactly what they carried before
  (`takeIf { SDK_INT >= 31 }` and `if (SDK_INT >= 31) … else null` return the same value); below 31
  they carry the same `"Unavailable"` value and the same `"Requires Android 12 (API 31) or newer."`
  note they already had. No label, copy, layout, control or ordering changed, and no row was added
  or removed.
  *Tests:* three new in `IdentityCollectorApiLevelTest`, the first test in the suite to call
  `IdentityCollector.collect()` at all. `the identity section survives a platform without the SoC
  fields` (sdk 29) asserts all thirteen identity rows are produced and that collection runs on to
  `software.buildTime`; `below API 31 the SoC rows are unavailable and say why` (sdk 29) pins the
  value, the `UNVERIFIED` status, the note and the two `DeviceIdentity` values; `from API 31 the SoC
  rows are read rather than gated` (sdk 34) asserts the "requires Android 12" note is absent on a
  platform that has the field. **Mutation-checked:** with the old `takeIf` expression restored, the
  two sdk-29 tests fail with `java.lang.NoSuchFieldError` at the `collect()` call and the sdk-34
  test passes — the crash reproduced, and only the two rows' construction decides it. The
  production file was then restored and is the committed one (sha256 `87caf1b3…6a16b`).
  *Verified:* `assembleDebug` green, debug APK 9,827,992 bytes — byte-identical to the baseline I5
  records, the change adds no code to the APK's hot path and no resource; all **425** unit tests
  pass (422 before, 3 new) in one `testDebugUnitTest` run of 4m27s. The diff is one production file,
  one new test file and this entry; `ui/` and `res/` are untouched.
  *Not verified:* no device is available, so the crash was reproduced on Robolectric's API 29 and 34
  runtime jars rather than on an Android 8-11 handset. The sdk-29 jar does not carry the field,
  which is what makes the reproduction faithful to a real API 26-30 framework.

*Environment note, not a repository change:* Maven Central's redirect target `gcp.mirrors.maven.org`
answered **502** again for `org.robolectric:nativeruntime-dist-compat:1.0.16`. The jar was fetched
from `maven-central.storage-download.googleapis.com` and served to Gradle through a local file
repository in an init script outside the repository, and Robolectric's `android-all-instrumented`
jars for API 29, 30, 33, 34 and 35 were passed with `ROBOLECTRIC_DEPENDENCY_DIR`, which the build
already supports. No build file, repository list or dependency in the tree was changed. The build
itself needed `-Dorg.gradle.jvmargs=-Xmx1750m`; at the 1400 MB in `gradle.properties`,
`:app:mergeExtDexDebug` does not finish inside one 300 s call.

## KK. Audit findings — 13 November run (second pass)

The eighteen unchecked items above were re-read before anything was touched, and every one was
passed over for the reason its own entry already records: **F1**, **F3** and **I8** are sandbox-bound
(`nproc` = 2, `free` = 2,098 MB — this run's cold `assembleDebug` again needed several 300 s calls,
and `:app:mergeExtDexDebug` alone did not finish inside a single one, so neither the non-resumable
`minifyReleaseWithR8` nor `lintAnalyzeDebug` has a window here); **G1** and **I10** duplicate
measurements already recorded next to their items; **H2, H5, H7, H9, H11, H13, H14, M2, M3, M4**
would change what the user reads on screen or in the exported report; **L2, P2, V2** move a reported
verdict and need sign-off. Nothing was ticked that was not done.

The comparison path was then read again — the one place every scan goes through dozens of times and
no entry had looked at since the pattern cache — and it produced one finding that clears the bar.

- [x] **KK1. `AnalysisSupport.normalise` allocated a `Matcher` and a result string for every string
  it compared.** The pattern itself was already cached (that was the earlier fix, and its comment
  says why), but `Regex.replace(input, "")` still builds a `Matcher` over the input and a fresh
  result `String` on every call, even when there is nothing to remove. `normalise` runs twice per
  brand comparison in `DefaultAuthenticityEngine`, once per reference entry in `ReferenceIndex`'s
  constructor, and once per SoC comparison in `socMatches`, and the overwhelmingly common input — a
  lowercase ASCII brand such as `samsung`, or a part number such as `sm8550` — needs no filtering at
  all, so that whole cost was being paid to return the string it was handed.
  *Change:* one production file, `AnalysisSupport`. `normalise` now lowercases with the same
  `Locale.US`, scans the lowered string for a character outside `a`-`z`/`0`-`9`, and returns it
  unchanged when there is none; otherwise it appends the kept characters into one `StringBuilder`.
  The now-unused single-character pattern was deleted and the remaining `nonAlphanumericRuns` (used
  by `socMatches`' token splitting) kept, with its comment corrected. The result is character for
  character what the regex produced — `lowercase` still runs first, so a mapping that changes the
  length (`İ` → `i̇`) is filtered exactly as before, and the kept set is unchanged, so every
  comparison, finding, score and rendered value in the app is identical.
  *Measured* (JDK 17, this host, warmed, median of nine rounds of 2.6 M calls over the thirteen
  brand/manufacturer/model/SoC strings a scan actually normalises, the two implementations run
  alternately in the same JVM): **341.5 ns → 111.9 ns per call, 3.05× faster**. For scale, the
  backlog already records `authenticity.analyze` at 434 µs per report and a full report rebuild at
  500 µs, so this removes tens of microseconds from a scan, not milliseconds; the item is worth
  taking because the output is provably unchanged, not because 230 ns is felt.
  *Tests:* three added to `AnalysisSupportTest`. `normalise agrees with the regex expression it
  replaced` runs a 26-input corpus — `null`, empty, blank, mixed case, the three separator spellings
  of one part number, doubled spaces, tab/newline, a dotted `İ`, a dotless `ı`, Latin-1 capitals,
  Han characters, Arabic-Indic digits, mathematical bold digits, a combining accent, a non-breaking
  hyphen, an emoji, a NUL and a literal backslash — through both the new implementation and the old
  expression held in the test as an oracle, and asserts they agree on every one. `normalise keeps
  only ascii letters and digits` pins the filter's output, and `an already clean value is normalised
  without being rebuilt` pins the fast path.
  *Verified:* `assembleDebug` green (BUILD SUCCESSFUL in 3m47s). The unit suite is green — **428**
  tests (425 before, 3 new), 0 failures, 0 skipped: 271 tests in the 35 plain-JUnit classes and 157
  in the 22 Robolectric classes. It had to be run as four filtered invocations of
  `:app:testDebugUnitTest` (`--tests`, by class) rather than one: the whole suite needs roughly 25
  minutes of test execution on this host (the Robolectric classes cost ~25 s each just to start their
  sandbox), and one Gradle call is capped at 300 s here, so a single invocation cannot reach the end
  of the suite. Every test class ran, in one of the four, and each invocation ended BUILD SUCCESSFUL
  with no failure reported. `tools/check-no-ui-change.sh` could not answer — this checkout has no
  `.git`, so it reports "not a git checkout, nothing to compare" — so the diff was checked directly
  against `main` instead: only `AnalysisSupport.kt` (44 changed lines) and `AnalysisSupportTest.kt`
  (52) differ, no file under `ui/` or `res/` is touched, and this entry is the third file.
  *Not verified:* no device is available, so the scan-path benefit is a JDK micro-benchmark over
  representative strings rather than a handset profile. The mutation check is by construction rather
  than by reverting in-tree: the oracle in the equivalence test *is* the old expression, so any
  divergence between the two implementations fails it. And the suite above is four filtered runs, not
  one: each class passed, but no single invocation of the task has been observed to finish here.

*Environment note, not a repository change:* Maven Central's redirect target `gcp.mirrors.maven.org`
answered **502** again for `org.robolectric:nativeruntime-dist-compat:1.0.16`, so an init script
outside the repository (`-I`, redirecting `repo.maven.apache.org` to
`maven-central.storage-download.googleapis.com`) was used for the test run, and Robolectric's own
`android-all-instrumented` jars for API 10, 11, 13, 14 and 15 were pre-fetched into a directory
passed as `ROBOLECTRIC_DEPENDENCY_DIR`, which the build already supports — without them all 163
Robolectric tests failed with "Failed to fetch maven artifact". No build file, repository list or
dependency in the tree was changed. The build again needed
`-Dorg.gradle.jvmargs=-Xmx1750m -XX:+UseSerialGC`; at the 1400 MB in `gradle.properties`,
`:app:mergeExtDexDebug` does not finish inside one 300 s call.

## LL. Audit findings — 13 November run (third pass)

The eighteen unchecked items above were re-read before anything was touched, and every one was
passed over for the reason its own entry already records: **F1**, **F3** and **I8** are sandbox-bound
(`nproc` = 2, `free` = 2,098 MB — `:app:mergeExtDexDebug` and `:app:compileDebugKotlin` each still
need more than one 300 s call here, so the non-resumable `minifyReleaseWithR8` and `lintAnalyzeDebug`
have no window); **G1** and **I10** duplicate measurements already recorded next to their items;
**H2, H5, H7, H9, H11, H13, H14, M2, M3, M4** would change what the user reads on screen or in the
exported report; **L2, P2, V2** move a reported verdict and need sign-off. Nothing was ticked that
was not done.

The property reader was then read again — it is the one object every scan touches from every
collector, and its failure path had never been looked at — and it produced one finding that clears
the bar.

- [x] **LL1. A failed `getprop` load was cached as the permanent answer.** `SystemPropertyReader`
  set `loaded = true` *before* running the loader, so the first attempt was also the last: a
  `dumpLoader` that threw, or a dump that came back empty because `getprop` was killed at
  `DUMP_DEADLINE_MS`, left `cache` empty and `loaded` true for good. `AppContainer` builds one
  reader per process, so one transient failure — a property service busy during boot, a wedged
  `getprop` on the first scan — pushed every later property read in the app onto the reflection
  fallback, which `SystemProperties` does not guarantee under the hidden-API policy the app targets.
  The class doc's "parsed at most once" was about cost, not about treating a failure as a fact.
  *Change:* one production file, `SystemPropertyReader`. `loaded` is now set only after a dump has
  actually been parsed, and an attempt that produced nothing leaves it false so a later read tries
  again. `MAX_LOAD_ATTEMPTS` (3) caps that: a permanently broken `getprop` is tried three times in
  the process lifetime and then settles on reflection, so the retry cannot turn every property read
  into a 2 s stall — the worst case added is two more deadlines, and only while the dump is broken.
  A successful dump is still parsed exactly once.
  *Tests:* `SystemPropertyReaderRetryTest`, 5 cases — a throwing loader is retried, an empty dump is
  retried, a permanently broken loader is tried exactly `MAX_LOAD_ATTEMPTS` times over ten reads,
  a successful dump is still parsed once over ten reads, and the recovered dump is cached. The
  existing `SystemPropertyReaderTest` (including `aFailingDumpDoesNotThrow`) and
  `SystemPropertyReaderDeadlineTest` are unchanged and still pass.
  *Verification:* `assembleDebug` green; debug APK **9,827,992 bytes, byte-for-byte the same size as
  the pre-change baseline** (9,827,992), so nothing was added to the APK. All **433** unit tests
  pass (428 before, 5 new). No file under `ui/` or `res/` was touched and `ScreenshotTest` renders
  every published screen unchanged.
  *Not verified:* the full suite cannot be run as one `testDebugUnitTest` invocation here — the task
  is not resumable and the 300 s call limit kills it part-way — so it was run as eight `--tests`
  subsets covering all 57 test classes, every one green. No device was available, so the retry is
  not exercised against a real wedged `getprop`; the injected-loader tests are the evidence.

## MM. Audit findings — 14 November run

The eighteen unchecked items above were re-read first, and every one is still blocked for the reason
its own entry already records: **F1**, **F3** and **I8** are sandbox-bound (`nproc` = 2,
`free` = 2,098 MB; this run's cold `assembleDebug` still needed eight 300 s calls, so the
non-resumable `minifyReleaseWithR8` and `lintAnalyzeDebug` have no window); **G1** and **I10**
duplicate measurements already recorded next to their items; **H2, H5, H7, H9, H11, H13, H14, M2,
M3, M4** would change what the user reads on screen or in the exported report; **L2, P2, V2** move a
reported verdict and need sign-off. Nothing was ticked that was not done.

Two genuine findings came out of re-reading the reporting paths. The first one is a race in a read
the history screen drives, which changes no rendering and is fixed here. The other two are recorded
below and left open, because each changes a value the user reads.

- [x] **MM1. A stored report read that had been superseded still published its result.**
  `AppViewModel.loadRawReport` read a history entry's JSON and handed it straight to its callback:
  `viewModelScope.launch { onLoaded(container.history.loadRaw(readId)) }`. Expanding one history card
  and then another left two reads in flight with nothing ordering their completions, so the read for
  the entry the user had already moved away from could finish last and be stored as the expanded
  text. `HistoryScreen` renders `json` only for the card whose id is currently expanded, which hides
  the harmless case — but when the *other* entry is open, the stale text is exactly what that card
  shows: one stored report rendered under another entry's heading, with that entry's model, scores
  and risk level sitting above a different device's JSON.
  *Change:* a new `core/util/LatestRequest` — a ticket counter whose `begin()` is synchronous and
  whose `isCurrent(ticket)` is true only for the newest ticket — and `loadRawReport` now takes a
  ticket before starting the read and publishes only if that ticket is still the newest. The ticket
  is taken by the caller, so it records the order the taps happened in, not the order the disk
  answered in; a superseded read is dropped and never becomes current again, whether the newer read
  succeeds, fails or is still running. `LatestRequest` deliberately launches and cancels nothing —
  `ScanCoordinator` owns that for scans, where a superseded request has a job to cancel; here the
  read belongs to the caller's own scope and the only question is whether its answer is still
  wanted. No file under `res/` changed, `ui/AppViewModel.kt` is the gate's one listed non-rendering
  UI file, and no screen, control, style, layout or copy changed.
  *Tests:* `LatestRequestTest` (6 cases: first ticket current, a newer ticket supersedes immediately,
  a superseded ticket never revives however many later tickets are taken, tickets are distinct and
  increasing, only the newest of four outstanding tickets is current, a ticket never issued is not
  current) and `HistoryRawReportOrderTest` (3 cases against the real view model and the real
  file-backed history, with two reports actually stored: a superseded read never publishes and the
  newest one does, an uncontested read still publishes its own report, and with two requests the
  last tap decides what is shown — exactly one delivery). The tests wait on the delivery count and
  then pump the looper for a further 3 s to give the superseded read its chance to arrive late, so
  "it never published" is an observation, not an assumption.
  *Verification:* `assembleDebug` green and **all 442 unit tests pass in a single
  `testDebugUnitTest` invocation** (433 before, 9 new; 59 classes). The debug APK grows from
  **9,900,786 to 9,901,050 bytes (+264)** for the ticket counter, measured by building the tree with
  the change removed and then with it restored. `tools/check-no-ui-change.sh` reports
  `OK — nothing under ui/ or res/ differs from HEAD (31 files tracked there)`.
  *Not verified:* no device is available, and the read latency on a real handset is what makes the
  race likely in the first place; the tests drive the ordering with the ticket taken before either
  read can answer, which is the mechanism, not a reproduction of a slow SD card.

- [ ] **MM2. `Formatters.duration` counts the same days twice.** `duration` splits the day count
  with `years = days / 365`, `months = (days % 365) / 30` and then `remainingDays = days % 30` —
  the last one takes the remainder of the *original* count, not of what the months already
  accounted for. Every value that is not a whole year reports more time than it was given: 365 days
  renders as "1 y 0 mo 5 d" (370 days), 400 days as "1 y 1 mo 10 d" (405), 730 days as
  "2 y 0 mo 10 d" (740). It is rendered on the Age screen, in the PDF export and in the JSON export,
  for model age, firmware age, ownership age, estimated usage age and patch age.
  *Change:* `remainingDays = (days % 365) % 30`. *Needs sign-off:* every duration the app shows
  changes on any device older than a year, which is a reported-value change of the same class M3,
  M4 and P2 are waiting on — not a drive-by edit under this freeze.

- [ ] **MM3. The Performance score treats an unread core count as a six-core device.**
  `DefaultScoreEngine` scores `"CPU cores"` with
  `if (hardware.cpuCores >= 8) 100 else if (hardware.cpuCores >= 6) 80 else 60`, so `cpuCores == 0`
  — what `AndroidDeviceInformationProvider` substitutes when the hardware stage timed out or failed,
  and what `HardwareFacts` carries when nothing filled it — earns 60 and renders as "0 cores". That
  contradicts the engine's own first rule ("a missing value is removed from both numerator and
  denominator, and lowers coverage"), and it lets a failed read contribute a passing input to the
  Performance score instead of reducing its coverage.
  *Change:* pass `null` when `cpuCores <= 0`, so the input is dropped and coverage falls. *Needs
  sign-off:* it moves the Performance score and the input list the user reads on a device whose
  hardware stage failed.

## NN. Audit findings — 14 November run (second pass)

The first unchecked items were taken in file order and each was skipped for the reason it already
records, not re-litigated: **F1** (R8), **F3**/**I8** (lint) are sandbox-bound — this host has
`nproc` = 2 and 2,098 MB, this run's cold `assembleDebug` needed seven 300 s calls, and neither
`minifyReleaseWithR8` nor `lintAnalyzeDebug` finishes inside one call or resumes across calls;
**G1** and **I10** duplicate measurements already recorded next to their items; **H2, H5, H7, H9,
H11, H13, H14, M2, M3, M4** would change what the user reads on screen or in the exported report;
**L2, P2, V2** move a reported verdict and need sign-off. **MM2** (the double-counted duration) and
**MM3** (an unread CPU core count scoring 60) were re-read first and both stay open on the same
ground: each changes a number the app renders — every duration on the Age screen, the PDF and the
JSON export for MM2, the Performance score and its input list for MM3 — which is a reported-value
change of the class waiting on sign-off, not a drive-by edit under this freeze. Nothing was ticked
that was not done.

Re-reading the collection path for the "Binder calls that could be cached / error paths that could
be silent" lens produced one finding, which is implemented here.

- [x] **NN1. The StrongBox feature query was the one Binder call in a scan outside a stage deadline.**
  `AndroidDeviceInformationProvider.collect` runs all nine collector stages under a `StageTimer`, so
  a stage that never answers is abandoned and recorded in `collectionErrors` — that is the promise
  the class documents and `ScanTimeoutWiringTest` checks. The `HardwareFacts` assembly then called
  `context.packageManager.hasSystemFeature(FEATURE_STRONGBOX_KEYSTORE)` directly, inside no stage.
  `PackageManager` is a Binder call into the system service: it blocks like the camera or sensor
  service blocks, and on a device whose package service has died it throws. Either way the failure
  escaped `collect`, `AppViewModel.scan` caught it as `"Scan failed: …"`, and every field the nine
  stages had already collected was thrown away — a whole report lost because one feature flag could
  not be read. This is the same shape as the failure `automatedCheck` was written to stop on the
  diagnostics side (an unreadable vendor service discarding results that had already been measured).
  *Change:* the query is taken through `collectStage` like a collector, so it inherits the stage
  deadline and the whole-scan budget, and a failure or timeout costs one `collectionErrors` line and
  the flag (`strongBoxAvailable = false`) instead of the snapshot. The query is a constructor
  parameter, the way `timerFactory` already is, so both paths are reachable from a JVM test; the
  default is the platform call, unchanged. No file under `res/` or `ui/` was touched, and no screen,
  control, style, layout or copy changed.
  *Tests:* `StrongBoxFeatureStageTest` (2 cases) drives the real provider: a query that throws still
  produces a snapshot, reports the flag as absent and records exactly one `StrongBox feature check
  failed: …` line carrying the cause; a query that answers still reaches `hardware.strongBoxAvailable`
  and records nothing. `ScanTimeoutWiringTest` moves from nine accounted-for stages to ten, which is
  the wiring this item adds.
  *Verification:* `assembleDebug` green. All 60 test classes pass — **444 tests, 442 before plus the
  two new ones** — run in eight `--tests` invocations because one full-suite invocation does not
  finish inside this sandbox's 300 s command limit (the suite needs about 12–15 minutes of CPU on two
  cores); every class ran, none was skipped. The debug APK is 9,901,999 bytes, 102,712 methods,
  14,112 classes (`tools/measure.sh --print`). The change adds one lambda and one call to an existing
  class; no size claim is made beyond that measurement.
  *Not verified:* no device is available, so "a dead package service no longer loses the scan" is
  shown against an injected query, not against a real wedged `system_server`.

## OO. Audit findings — 14 November run (third pass)

The first unchecked items were taken in file order and each was skipped for the reason it already
records, not re-litigated: **F1** (R8) and **F3**/**I8** (lint) are sandbox-bound (2 cores /
2,098 MB, and neither task can finish inside one call or resume across calls — the F1 entry above now
records why this run's build fix does not change that); **G1** and **I10** duplicate measurements
already recorded next to their items; **H2, H5, H7, H9, H11, H13, H14, M2, M3, M4** would change what
the user reads on screen or in the exported report; **L2, P2, V2, MM2, MM3** move a reported value or
verdict and need sign-off. Nothing was ticked that was not done.

**This run's real cost was the build itself, and it is worth recording.** On this pod a cold
`:app:compileDebugKotlin` did not finish inside a 300 s call at all — eleven consecutive attempts were
killed with the task still running and no class file on disk (the JVM was also starting cold every
time, because `org.gradle.daemon=false` makes each invocation a single-use daemon). Two changes to the
*environment only* — nothing in the tree — turned that around:

  * `-XX:TieredStopAtLevel=1 -XX:+UseSerialGC` on the Gradle JVM. A one-shot compile spends most of
    its life in the interpreter and in C2 warm-up it never gets back, and on this host that was the
    difference between "never finishes" and `:app:compileDebugKotlin` green in under 285 s, followed by
    `assembleDebug` in 2m10s and the whole unit suite in ~4m.
  * Dependency resolution. Maven Central answers with a redirect to `gcp.mirrors.maven.org`, which the
    sandbox proxy refuses (502 through the CONNECT tunnel), so `org.robolectric:nativeruntime-dist-compat:1.0.16`
    could not be fetched at all; it was staged in a local file repository through
    `maven-central.storage-download.googleapis.com` (an allowlisted host) and injected with an init
    script, not with a change to `settings.gradle.kts`. Robolectric then failed 168 of 444 tests with
    "Failed to fetch maven artifact …android-all-instrumented…" because it downloads its runtime jars
    at *test* time; the repo's own `ROBOLECTRIC_DEPENDENCY_DIR` hook (already in
    `app/build.gradle.kts`) with the five `android-all-instrumented` jars for SDK 29, 30, 33, 34 and 35
    makes the suite run offline. The suite is green here for the first time this campaign.

Re-reading the code for the "races and error paths that could be silent" lens then produced one
finding, which is implemented here.

- [x] **OO1. `ScanCoordinator` published its generation across threads with no happens-before edge.**
  `ScanCoordinator.start` runs on the view model's main-thread scope, but `isCurrent` is asked from a
  different thread: `AppViewModel.scan` publishes the progress its provider hands back, and
  `AndroidDeviceInformationProvider.collect` invokes that callback inside `withContext(Dispatchers.IO)`.
  `generation` was a plain `var`, and neither side is a coroutine suspension point that would order the
  two, so the reader was free to see the previous number. Both directions of that read are the failure
  the class exists to prevent: a running scan whose own generation looks superseded drops its progress
  (the bar stops moving), and a superseded scan whose generation still looks current publishes over the
  scan that replaced it — the same defect C2 and MM1 fixed on the scan and history paths, left open on
  the gate between them. *Change:* `generation` and `job` are `@Volatile`, which is the edge, and
  `start` is `@Synchronized` so two callers cannot interleave into the same number. No file under
  `res/` or `ui/` was touched; no screen, control, style, layout or copy changed, and nothing the app
  reports is derived differently — a correct-path scan publishes exactly what it published before.
  *Tests:* `ScanCoordinatorGenerationTest` (3 cases) drives the gate itself: the number a request is
  handed is current and the one it replaced is not; the gate answers the same when asked from a worker
  thread as from the thread that called `start`; and a stale or invented number is never current.
  *Verification:* `assembleDebug` green; `testDebugUnitTest` green — **447 tests, 444 before plus the
  three new ones**, 61 classes, none skipped. No size claim: two debug builds of this tree on this host
  measured 9,844,376 and 9,918,391 bytes, so the packaging is not byte-reproducible here and a delta
  from a three-line change would be noise.
  *Not verified:* no device is available, and a memory-visibility bug cannot be provoked on demand on
  x86 — the tests pin the contract the `@Volatile` fields exist to keep, not the race itself.

---

## PP. Audit findings — 14 November run (fourth pass)

The unchecked items were taken in file order and each was skipped for the reason it already records,
not re-litigated: **F1** (R8) and **F3**/**I8** (lint) are sandbox-bound — `nproc` = 2 and
`free` = 2,098 MB again this run, and neither `minifyReleaseWithR8` nor `lintAnalyzeDebug` finishes
inside one 300 s call or resumes across calls; **G1** is done (ticked above — the file has existed
since 6 November) and **I10** duplicates F5's recorded dependency audit; **H2, H5, H7, H9, H11, H13,
H14, M2, M3, M4** would change what the user reads on screen or in the exported report; **L2, P2,
V2, MM2, MM3** move a reported value or verdict and need sign-off. Nothing was ticked that was not
done.

Re-reading the collection path for the "error paths that could be silent" lens produced one finding,
which is implemented here.

- [x] **PP1. Both containment boundaries catch `Exception`, so a missing framework member still kills
  the app.** `StageTimer.run` is the scan's boundary — its own contract says it "never throws for a
  stage failure or a timeout" — and `automatedCheck` is the diagnostics pass's boundary — "a check
  that throws costs that check only". Both were written as `catch (e: Exception)`. Every stage and
  every automated check reads a vendor framework: `Build.SOC_MANUFACTURER`,
  `PowerManager.currentThermalStatus`, `CameraManager.cameraIdList`, a `CameraCharacteristics` key,
  `SensorManager.registerListener`. A member the running build does not carry does not throw an
  exception — it raises `NoSuchFieldError`, `NoSuchMethodError`, `NoClassDefFoundError` or
  `ExceptionInInitializerError`, and those are `LinkageError`s, i.e. `Error`s, which
  `catch (e: Exception)` does not see. The error then left `run`/`automatedCheck`, left
  `AndroidDeviceInformationProvider.collect` / `runAutomatedChecks`, missed `AppViewModel.scan`'s and
  `runAutomatedDiagnostics`'s own `catch (e: Exception)`, and reached the process: one unreadable
  vendor member cost the whole app instead of one stage or one check. This is not hypothetical for
  this codebase — `IdentityCollector` carries the note that exactly this (`Build.SOC_MANUFACTURER` on
  Android 8-11) used to crash every scan, and that fix was made at the read site only, leaving both
  boundaries behind it unguarded.
  *Change:* both boundaries now also catch `LinkageError`, returning the same
  `StageOutcome.Failed(error)` / `UNVERIFIED` row they already returned for an `Exception`, with the
  same message text (`"<stage> failed: <message>"`, `"Could not read <thing>: <message>"`) and the
  same fallbacks downstream. The boundary is `LinkageError` and not `Error`: `VirtualMachineError`
  (`OutOfMemoryError`, `StackOverflowError`) still propagates, because a process that is out of
  memory or out of stack is not a stage to skip and a report to keep, and turning it into a partial
  scan would be worse than the crash. No file under `res/` or `ui/` was touched and nothing the app
  reports is derived differently: a stage that hit this before produced no report at all.
  *Tests:* `FrameworkMemberContainmentTest` (5 cases, plain JVM, no Robolectric): a stage that hits a
  missing field is `Failed` with that field's name and **the stages after it still run**; a stage that
  hits a missing class is `Failed` with the class name; a stage that throws `OutOfMemoryError` still
  reaches the caller; an automated check that hits a missing method comes back `UNVERIFIED` with the
  exact note text; and a check that throws `OutOfMemoryError` still reaches the caller. The
  supervisor scope the tests build is the one `AndroidDeviceInformationProvider` uses — with a plain
  `Job` a failing child cancels the scope, which is what the first draft of the test caught.
  *Verification:* `assembleDebug` green. `testDebugUnitTest` green — **452 tests in 62 classes** (447
  and 61 before, plus the 5 new ones), none skipped, run in eight `--tests` batches because one
  full-suite invocation does not fit in this sandbox's 300 s command limit. `tools/measure.sh
  --print`: 9,844,376 bytes, 102,713 methods, 14,112 classes — no size claim is made from it, since
  two debug builds of this tree on this host have already differed by tens of kilobytes.
  *Not verified:* no device is available, so "a missing framework member no longer kills the scan" is
  shown against a thrown `NoSuchFieldError`, not against a handset that genuinely lacks the member.
  `tools/check-no-ui-change.sh` could not run here — this checkout is not a git repository (exit 2,
  "not a git checkout"), so "the UI did not change" was checked by path instead: the only non-test
  files touched are `core/util/StageTimer.kt`, `data/android/AndroidHardwareDiagnosticsEngine.kt` and
  this document, none of them under `ui/` or `res/`.
  *Environment (no build file changed):* this host needed the OO run's `-XX:TieredStopAtLevel=1
  -XX:+UseSerialGC` Gradle JVM flags, a local file repository for
  `org.robolectric:nativeruntime-dist-compat:1.0.16` (Maven Central still redirects to a host the
  sandbox proxy refuses) and `ROBOLECTRIC_DEPENDENCY_DIR` with the `android-all-instrumented` jars for
  SDK 29, 30, 33, 34 and 35.

- [x] **PP2. `AppViewModel.scan` and `runAutomatedDiagnostics` still catch `Exception` only.** PP1
  closes the two boundaries a framework read is reached through, but both view-model catches are the
  last line before the process: anything that raises a `LinkageError` outside a stage — the
  `Build.*` reads in `AndroidDeviceInformationProvider.fallbackIdentity`, a collector constructed
  before its stage starts — still leaves the coroutine and crashes the app rather than reporting
  `"Scan failed: …"` the way every other failure on that path does. *Change:* add a `LinkageError`
  branch beside each existing `catch (e: Exception)`, reusing the same message text and the same
  `error` state field. *Verify:* a unit test that drives `AppViewModel` with a provider whose
  `collect` throws `NoClassDefFoundError` and asserts `state.error` starts with `"Scan failed: "` and
  that no exception escapes — which needs `AppContainer`'s provider to be injectable, the one piece
  of wiring this item has to add. Not implemented this pass: the injection seam is a constructor
  change on a class the UI reads state from, and it belongs in a run that can give it its own tests.
  *Done (14 Nov, fifth pass):* both catches now have a `LinkageError` branch beside the existing
  `catch (e: Exception)`, publishing the identical text into the identical field —
  `"Scan failed: ${e.message ?: e.javaClass.simpleName}"` and `"Diagnostics failed: ${e.message}"` —
  so a linkage failure reads as a failed scan or a failed diagnostics run instead of leaving the
  coroutine. The seam the test needs is `AppContainer`'s two optional `…Override` parameters
  (`deviceInformationProviderOverride`, `diagnosticsEngineOverride`); production passes neither and
  gets exactly the previous wiring, so the graph is unchanged at runtime. `AppViewModel` takes its
  container as a constructor parameter with a default and `@JvmOverloads`, which keeps the
  `(Application)` constructor the framework's `AndroidViewModelFactory` reflects on and changes
  nothing a screen reads. *Tests:* `ScanFailureBoundaryTest` (3 cases, Robolectric, SDK 34): a scan
  whose provider throws `NoClassDefFoundError` ends with `error = "Scan failed: android/os/ServiceManager"`
  and `scanning = false`; a diagnostics run whose engine throws it ends with
  `"Diagnostics failed: android/os/ServiceManager"`; and an ordinary `IllegalStateException` still
  reports `"Scan failed: boom"`, so the new branch did not swallow the old one.
  *Verification:* `assembleDebug` green (`app-debug.apk` 9,844,376 bytes, the same size the OO run
  measured — no size claim, since this host is not byte-reproducible); `testDebugUnitTest` green —
  **455 tests in 63 classes** (452 and 62 before, plus the 3 new ones), none skipped;
  `tools/check-no-ui-change.sh HEAD` clean (31 files tracked under `ui/` and `res/`, none differs).
  No screen, control, style, layout, copy or reported value changes: the only production files
  touched are `AppContainer.kt` and `ui/AppViewModel.kt`, and the second one draws nothing.
  *Not verified:* no device is available, so this is shown against a thrown `NoClassDefFoundError`,
  not against a handset whose framework genuinely lacks the member. The diagnostics case also needed
  a settle step before it could be asserted — see QQ1 for why, which is a pre-existing defect the
  test walked into rather than a defect in this change.

## QQ. Audit findings — 14 November run (fifth pass)

The unchecked items were taken in file order and each was skipped for the reason it already records,
not re-litigated: **F1** (R8) and **F3**/**I8** (lint) are sandbox-bound — `nproc` = 2 and
`free` = 2,098 MB again, and neither `minifyReleaseWithR8` nor `lintAnalyzeDebug` finishes inside one
300 s call or resumes across calls; **I10** duplicates F5's recorded dependency audit; **H2, H5, H7,
H9, H11, H13, H14, M2, M3, M4** would change what the user reads on screen or in the exported report;
**L2, P2, V2, MM2, MM3** move a reported value or verdict and need sign-off. That left PP2, the first
item on the list that is neither blocked, a duplicate, nor gated on sign-off, and it is implemented
here.

The new item below was found while writing PP2's second test, and is recorded rather than fixed: it
is a separate defect on a separate path, and this run's item is PP2.

- [x] **QQ1. `AppViewModel.refreshHistory` writes back a state it read before it suspended, so it can
  erase an error published meanwhile.** `refreshHistory` is `_state.value = _state.value.copy(history =
  container.history.list())` inside `viewModelScope.launch`. The receiver of `copy` is evaluated
  *before* the argument, and `history.list()` is a suspend call that reads the disk, so any state
  change published while that read is in flight — a `"Scan failed: …"` error, a `"PDF ready to
  share."` message, a diagnostic session update — is overwritten by the pre-suspension copy when the
  refresh resumes. It is reachable on a cold start: the constructor queues one refresh, so a scan that
  fails fast, or any action the user takes before the first history read lands, can have its result
  silently discarded. Found because PP2's diagnostics test asserted an error that had been written
  and then vanished on the full suite (it passed alone, where the stored history is empty and the read
  returns without suspending) — the test now drains the constructor's refresh first, with a comment
  pointing here. *Change:* read the list into a local and publish it with `_state.update { it.copy(
  history = list) }`, which re-reads the current state under the CAS loop instead of writing a copy
  taken before the suspension; the same shape `publish` already uses. *Verify:* a test that starts a
  refresh against a `LocalDiagnosticHistory` whose `list` suspends on a latch, publishes an error
  through `scan`, releases the latch, and asserts the error is still there — which needs `AppContainer`
  to take a history override, the same optional-parameter seam PP2 added for the provider and the
  diagnostics engine. No visible change: the history list still renders the same entries, and the only
  difference is that a message published during a refresh is no longer dropped.
  *Done (14 Nov, sixth pass):* `refreshHistory` now reads the list into a local and publishes it
  with `_state.update { it.copy(history = history) }`, whose CAS loop re-reads the state it writes
  back, instead of `_state.value = _state.value.copy(history = container.history.list())`, which
  took the copy of the state before `list()` suspended and wrote that stale copy over whatever had
  been published meanwhile. `AppContainer` takes the `historyOverride` seam the entry asked for
  (third optional constructor parameter, same shape as the provider and diagnostics overrides;
  production construction is unchanged and still builds `FileDiagnosticHistory`). Two tests in
  `AppViewModelHistoryRefreshTest`: one parks the refresh on a `CompletableDeferred` gate inside
  `list()`, publishes a scan failure, releases the gate and asserts the error survives and the
  entries are listed; the other asserts a plain refresh still publishes the stored list and calls
  `list()` once. *The first test was checked against the pre-fix body:* with the old line restored
  it fails (`2 tests completed, 1 failed`), with the fix it passes — so it pins the defect rather
  than the fix. No file under `res/` was touched; the only `ui/` file changed is `AppViewModel.kt`,
  already in `NON_RENDERING_UI_FILES`, and `tools/check-no-ui-change.sh` reports
  `OK — nothing under ui/ or res/ differs from HEAD (31 files tracked there)`. `assembleDebug` green,
  debug APK 9,844,376 bytes; `testDebugUnitTest` green — **457 tests, 455 before plus the two new
  ones**, 64 classes, none skipped. No size claim: the debug APK is not byte-reproducible on this
  host and no pre-change baseline was rebuilt this run.

## RR. Audit findings — 14 November run (sixth pass)

The unchecked items were taken in file order and each was skipped for the reason it already records,
not re-litigated: **F1** (R8) and **F3**/**I8** (lint) are sandbox-bound — `nproc` = 2 and
`free` = 2,098 MB, and neither `minifyReleaseWithR8` nor `lintAnalyzeDebug` finishes inside one
300 s call or resumes across calls; **I10** duplicates F5's recorded dependency audit; **H2, H5, H7,
H9, H11, H13, H14, M2, M3, M4** would change what the user reads on screen or in the exported report;
**L2, P2, V2, MM2, MM3** move a reported value or verdict and need sign-off. That left QQ1, the last
item on the list that is neither blocked, a duplicate, nor gated on sign-off, and it is implemented
here — see the entry above for the change, the two tests and the before/after check.

**Build environment, for the next run.** Two things cost this pass the most time and are both
environment-only, with nothing in the tree changed for them: `-XX:TieredStopAtLevel=1
-XX:+UseSerialGC` on the Gradle JVM (the same lever the OO section records) took a cold
`:app:compileDebugKotlin` from "never finishes inside a call" to green in one 270 s slice, after
which `assembleDebug` completed in 1m11s; and the unit-test classpath needed
`org.robolectric:nativeruntime-dist-compat:1.0.16` staged from
`maven-central.storage-download.googleapis.com` into a local file repository injected with an init
script — *added first*, before `google()`/`mavenCentral()`, or Maven Central's 502 through the proxy
fails the resolution before the local repo is consulted — plus the five
`android-all-instrumented` jars (SDK 29, 30, 33, 34, 35) in `ROBOLECTRIC_DEPENDENCY_DIR`, which is
the hook `app/build.gradle.kts` already carries. With both, the whole 457-test suite runs offline in
about four minutes.

## SS. Audit findings — 15 November run

The unchecked items were re-read in file order and each was skipped for the reason its own entry
already records, not re-litigated: **F1** (R8), **F3** and **I8** (lint) are sandbox-bound — this is
the same 2 cores / 2,098 MB host, and this run's cold `assembleDebug` again needed four 300 s calls
before it first went green, so neither the non-resumable `minifyReleaseWithR8` nor
`lintAnalyzeDebug` has a window here; **G1** and **I10** duplicate measurements already recorded
next to their items; **H2, H5, H7, H9, H11, H13, H14, M2, M3, M4** would change what the user reads
on screen or in the exported report; **L2, P2, V2, MM2, MM3** move a reported value or verdict and
need sign-off. Nothing was ticked that was not done.

The audit then re-read the rendering path — the one part of the app that is not a collector, a
report or a storage write, and so the part the earlier sections had looked at least — and found one
thing that costs the user something on every screen-on and changes nothing they can see.

- [x] **SS1. The Dashboard's device illustration ran an infinite animation for as long as the
  screen was on, with nothing observing it.** `DeviceIllustration` started a
  `rememberInfiniteTransition` unconditionally and drove the scan sweep from it. An infinite
  transition is not lazy: it holds a frame-clock callback and writes a new animated value on every
  frame, for the whole lifetime of the composable that owns it. But `sweep` is read in exactly one
  place — inside the `Canvas` draw lambda, under `if (scanning)` — so on an idle Dashboard (the
  screen the app opens on, and the one it sits on whenever nothing is happening) the app asked the
  frame clock for a frame at the display's rate, and wrote a state nobody read, for as long as the
  screen was on. It cost no redraw, because a state that is not read registers no observer, which
  is exactly why it went unnoticed: the wasted work is invisible in a screenshot, in a profile of
  the draw phase and in every test the project has.
  *Change:* the transition is created inside the branch that uses it — `val sweep: Float = if
  (scanning) { rememberInfiniteTransition(…).animateFloat(…) } else 0f` — so an idle illustration
  requests no frames at all. The drawing is untouched: the same sweep is computed from the same
  `tween(2200, LinearEasing)` spec and read by the same `drawRect`. The one behavioural nuance is
  the phase — a scan that starts now begins its sweep at the top of the panel instead of joining a
  loop that has been running since the screen appeared — which is a timing detail of a 2.2 s
  looping gradient, not a change of layout, spacing, colour, style or copy. No file under `res/`
  was touched, no screen or control was added, and no user-visible string changed.
  *Verification:* `assembleDebug` green; `testDebugUnitTest` green — **457 tests, 64 classes, 0
  failures, 0 skipped**, the same suite that was green before the change, including the seven
  `ScreenshotTest` screen renderings of the real composables (the Dashboard among them), so the
  rendered pixels the project asserts on are unchanged.
  *Not verified:* no device is available, and no before/after frame count could be measured here —
  the value the animation wrote had no observer, so Compose's test clock exposes no signal for it
  (nothing recomposes, nothing redraws, and `waitForIdle` is idle either way). The claim rests on
  the structure: an `InfiniteTransition` runs for its composable's lifetime, and this one now
  exists only while a scan is running. No test was added for that reason, rather than adding one
  that would pass with or without the fix.

---

## TT. Audit findings — 15 November run (second pass)

The nineteen unchecked items were re-read in file order and each was skipped for the reason its own
entry already records, not re-litigated: **F1** (R8), **F3** and **I8** (lint) are sandbox-bound —
`nproc` = 2, `free` = 2,098 MB, the same host as every attempt above, and this run's cold
`assembleDebug` needed seven 300 s calls before it first went green, so neither the non-resumable
`minifyReleaseWithR8` nor `lintAnalyzeDebug` has a window here; **I10** duplicates the classpath
audit F5 already recorded; **H2, H5, H7, H9, H11, H13, H14, M2, M3, M4** would change what the user
reads on screen or in the exported report; **L2, P2, V2, MM2, MM3** move a reported value or verdict
and need sign-off. Nothing was ticked that was not done.

The audit then re-read the paths the last passes had looked at least — the diagnostics engine, the
collectors, the reference loader, the probes and the history store — and found one thing worth
changing: a constant catalogue rebuilt on every read.

- [x] **TT1. The interactive diagnostics catalogue is rebuilt on every call, and nothing pinned
  it.** `AndroidHardwareDiagnosticsEngine.availableTests()` returned a `listOf(...)` written in the
  body of the function, so every call allocated a fresh 22-element backing array, its list wrapper
  and 22 `DiagnosticTestDescriptor` objects. `DiagnosticsScreen` calls it once per *recomposition*,
  not once per screen, so a visit that ran the automated pass and then tapped a couple of
  interactive tests rebuilt the whole catalogue several times over for a value that cannot change:
  every descriptor in it is a constant, and no field of it is read from the `Context`, from the
  device or from the `AndroidPermissionManager`. *Change:* the list is now a `private val` built
  once and `availableTests()` returns it, so a call allocates nothing. No descriptor, string,
  category, permission or flag changed; the app creates exactly one engine (`AppContainer` holds it
  for the process), and the one reader only reads the list. *Also fixed by the same commit:* the
  catalogue was pinned by no test at all — `AutomatedChecksTest` pins the ids the automated pass
  *reports*, but nothing pinned the 22 descriptors the screen *lists*, so a dropped, duplicated or
  reordered entry, a row moved to another category or a permission that went missing would have
  surfaced only as a changed `07-diagnostics` screenshot, which a deliberate screenshot update
  would have swallowed. New `DiagnosticsCatalogueTest` (5 tests) pins the whole catalogue in render
  order — id, category, required permission and `requiresManualConfirmation` per row, `automated`
  false throughout, every row carrying a title and a description — and pins the "built once"
  guarantee by asserting that repeated calls return the same instance, which is the assertion that
  fails if the catalogue ever goes back to being built per call. *Verification:* `assembleDebug`
  green, APK 9,844,376 bytes (unchanged); the whole suite green — **462 tests, 65 classes, 0
  failures, 0 errors, 0 skipped**, 457 before plus the 5 new ones — including the seven
  `ScreenshotTest` renderings of the real composables, the Diagnostics screen among them, so the
  pixels the project asserts on are unchanged. *Not verified:* the whole-suite invocation was not
  completed as a single `gradle testDebugUnitTest` in this sandbox — it needs about 15 minutes here
  and is killed at the 300 s call limit (19 of 65 classes had finished when it was killed), so the
  suite was run as five filtered invocations covering every class, all green. No device is
  available, so nothing here is a handset measurement: the "before" is the allocation the call used
  to make (22 descriptors plus their list per call, several times per visit to the screen), not a
  timed profile.

---

## UU. Audit findings — 15 November run (third pass)

The nineteen unchecked items were re-read in file order and each was skipped for the reason its own
entry already records, not re-litigated: **F1** (R8), **F3** and **I8** (lint) are sandbox-bound —
`nproc` = 2, `free` = 2,098 MB, the same host as every attempt above, and this run's cold
`assembleDebug` needed thirteen 300 s calls before it first went green, so neither the
non-resumable `minifyReleaseWithR8` nor `lintAnalyzeDebug` has a window here; **I10** duplicates the
classpath audit F5 already recorded; **H2, H5, H7, H9, H11, H13, H14, M2, M3, M4** would change what
the user reads on screen or in the exported report; **L2, P2, V2, MM2, MM3** move a reported value or
verdict and need sign-off. Nothing was ticked that was not done.

The audit then re-read the scan itself — the one path every user waits on, and the one the earlier
sections had only ever looked at stage by stage rather than as a whole — and found that the ten
collection stages were still being run one after another although none of them needs another's
output.

- [x] **UU1. The scan ran its ten collection stages one after another, so it cost their sum.**
  `AndroidDeviceInformationProvider.collect` awaited each stage before starting the next: identity,
  hardware, battery, cameras, sensors, connectivity, audio, security, software, and then the
  StrongBox feature query. Every one of them is a blocking read of a different system service —
  `getprop`, the package manager, the camera and sensor services, the audio service, `Settings`,
  `StatFs` — so almost all of that time is spent *waiting* for a Binder call to answer rather than
  computing, and the ten waits added up into the wall-clock time the user sat on "Scanning". Not one
  stage's output is another's input: each returns its own fields and its own facts, and they are
  concatenated in declaration order afterwards.
  *Change:* all ten stages are started together with `async` inside a single `coroutineScope` and
  then awaited in the same declaration order the code used to run them in, so a scan costs its
  slowest stage instead of the sum of all ten. The folding is untouched — a stage's fields and its
  `collectionErrors` line are still appended in the same order — so the snapshot, and every report
  built from it, is identical. The eight progress steps are emitted with the same labels in the same
  order, each still only after the work it labels has actually completed, so the scanning animation
  still cannot outrun the real work. Each stage keeps its own `StageTimer` deadline; what the whole
  scan can now cost is one stage deadline instead of ten, which is what the 90 s whole-scan ceiling
  existed to bound. Cancellation still unwinds everything: the stages are children of the scan
  coroutine, so a superseded scan stops waiting at once. No file under `ui/` or `res/` was touched,
  and no user-visible string, control, style, spacing or colour changed.
  *Verification:* `assembleDebug` green, debug APK **9,844,376 bytes** — byte-for-byte the size the
  14 November run recorded, so nothing was added to the app. `testDebugUnitTest` green — **462
  tests, 65 classes, 0 failures, 0 errors, 0 skipped**, the same suite that was green before the
  change, including `ScanTimeoutWiringTest` (all ten stages still report a timeout line, in the same
  order, when the budget is already spent), `StrongBoxFeatureStageTest` (the real collectors driven
  through the real provider), `ScanFailureBoundaryTest`, `ScanCoordinatorGenerationTest` and the
  fifteen `ScreenshotTest` renderings of the real composables.
  *Not verified:* no device is available, so the saving is not a measured scan duration — the claim
  rests on the structure, and the honest bound is "at most the slowest stage" instead of "the sum of
  ten". The whole suite also could not be completed as one `gradle testDebugUnitTest` here: it needs
  about 15 minutes on this host and is killed at the 300 s call limit, so it was run as nine filtered
  invocations covering every class, all green, none skipped.

*Environment notes for the next run — none of them a repository change.* (1) Maven Central
redirects large artifact jars to `gcp.mirrors.maven.org`, which this sandbox's egress policy refuses,
so `testDebugUnitTest` could not resolve `org.robolectric:nativeruntime-dist-compat`. Pointing Gradle
at Google's Maven Central mirror (`https://maven-central.storage-download.googleapis.com/maven2/`)
through a `--init-script` resolved it, and Robolectric's own `android-all` downloads need the same
mirror via `-Drobolectric.dependency.repo.url`. (2) A `testDebugUnitTest` call killed at the call
limit can leave a **truncated** `android-all-instrumented` jar in `~/.m2`; Robolectric then fails a
test with `NoSuchFieldError: noncompatWidthPixels` thrown from its own `DeviceConfig`, which reads
exactly like a real regression. Check each cached jar against its `.sha512` before believing such a
failure — `PermissionManagerTest.aPermissionThePlatformDoesNotHaveIsNotRequired` (`@Config(sdk = [30])`,
so it needs the API-30 jar) was the victim here, and passed unchanged once that jar was complete.
(3) `:app:mergeExtDexDebug` does not finish in one 300 s call at the repository's 1,400 MB heap on
this host; `-Dorg.gradle.jvmargs="-Xmx1800m -XX:MaxMetaspaceSize=384m -XX:+UseSerialGC"` brings it in
at 4m01s.

---

## VV. Audit findings — 15 November run (fourth pass)

The nineteen unchecked items were re-read in file order and each was skipped for the reason its own
entry records, not re-litigated: **F1** (R8), **F3** and **I8** (lint) are sandbox-bound — `nproc` =
2, `free` = 2,098 MB, and this run's cold `assembleDebug` needed eleven 300 s calls before it went
green, so neither the non-resumable `minifyReleaseWithR8` nor `lintAnalyzeDebug` has a window here;
**I10** duplicates the classpath audit F5 already recorded; **H2, H5, H7, H9, H11, H13, H14, M2,
M3, M4** would change what the user reads on screen or in the exported report; **L2, P2, V2, MM2,
MM3** move a reported value or verdict and need sign-off. Nothing was ticked that was not done.

The audit then re-read the one path every scan takes that the earlier sections had looked at stage
by stage: what the scan does *between* the collectors and the published report. It found that the
only step of a scan that touches the disk was running on the UI thread.

- [x] **VV1. The reference lookup read the bundled asset on the main thread.**
  `AppViewModel.scan` runs inside `ScanCoordinator.start`, which is `viewModelScope.launch` on
  `Dispatchers.Main.immediate`; `provider.collect` hops to `Dispatchers.IO` for its own stages and
  returns to the caller's context, so everything after it — including
  `container.referenceRepository.match(model, brand, device)` — ran on the main thread.
  `AssetReferenceRepository` is lazily and parse-once loaded, so the *first* `match` of a process
  opens `device_reference.json` from the APK assets (**14,907 bytes**), reads it, parses it with
  `org.json` and builds the `ReferenceIndex` — a StrictMode disk read plus a JSON parse on the UI
  thread, at the exact moment the scan animation starts. Every later scan hits the latch and costs a
  map lookup, which is why this only ever showed as jank on a cold first scan.
  *Change:* the lookup is wrapped in `withContext(Dispatchers.IO)`. Nothing else in the block moves.
  `match` is a pure function of the snapshot's three identity strings and its result is published
  with the rest of the scan at the end, so the snapshot, the reference, the report and every derived
  value are identical; the analysis that follows is CPU-only (measured at ~0.5 ms per rebuild on the
  JVM fixture in the M5 note) and stays on the main thread. `AppContainer` gained a
  `referenceRepositoryOverride` parameter, in the same shape as its three existing overrides, so the
  thread can be observed by a test; production passes none and gets the previous wiring.
  *Tests:* `ReferenceLoadThreadTest` (4 cases, Robolectric, the real `AppViewModel.scan` with the
  debug mock provider): the repository is asked exactly once per scan and **never on
  `Looper.getMainLooper().thread`**; the three identity strings the lookup receives are the
  snapshot's own; a matched reference is published as `state.reference` unchanged, so the off-thread
  answer still reaches the screens; and an unmatched scan still produces a report. The first case is
  the one that would have failed before the change — it is the assertion that pins the thread.
  *Size:* debug APK **10,000,167 → 10,000,529 bytes (+362)** — one lambda class and one constructor
  parameter, nothing added to the app. Both numbers were produced on this host by reverting the two
  changed files, rebuilding and measuring, then restoring them and rebuilding again; the campaign's
  older `9,844,376`-byte figure did not reproduce here, because that APK was packaged from the
  partially stale dex state an interrupted `assembleDebug` leaves behind (this run needed eleven
  300 s calls to get its first green build).
  *Not verified:* the saving is not a measured frame drop — no device is available here, and
  Robolectric's asset read is not a real APK read. The claim is structural: the read is off the UI
  thread, and the parse of the 14,907-byte asset no longer runs inside the frame that starts a scan.
  *Build environment, for the next run (environment only, nothing in the tree):* keeping the Gradle
  daemon alive (`-Dorg.gradle.daemon=true`) is the lever that makes this host usable. With
  `-Xmx1400m -XX:MaxMetaspaceSize=512m -XX:TieredStopAtLevel=1 -XX:+UseSerialGC` a cold
  `:app:compileDebugKotlin` finished in 3m59s and the rest of `assembleDebug` in 3m28s, where
  `org.gradle.daemon=false` (the repo default) had killed every attempt at 285 s with no class file
  on disk. `:app:mergeExtDexDebug` still needs its own call (4m41s at a 1,400 MB heap). The unit
  suite needs two more environment-only pieces: the mirror must be `addFirst` in
  `dependencyResolutionManagement.repositories` (Maven Central 302s to `gcp.mirrors.maven.org`,
  which the proxy refuses with a 502, for `org.robolectric:nativeruntime-dist-compat:1.0.16`), and
  `ROBOLECTRIC_DEPENDENCY_DIR` must hold the five `android-all-instrumented` jars — SDK 29, 30, 33,
  34 and **35** (`10-robolectric-5803371-i7`, `11-robolectric-6757853-i7`, `13-robolectric-9030017-i7`,
  `14-robolectric-10818077-i7`, `15-robolectric-12650502-i7`). With those, the whole suite is green
  here: **466 tests, 66 classes, 0 failures, 0 errors, 0 skipped**, run as five filtered invocations
  because one `testDebugUnitTest` takes longer than a 300 s call on this host.

---

## WW. Audit findings — 15 November run (fifth pass)

The nineteen unchecked items were re-read in file order and each was skipped for the reason its own
entry records, re-checked rather than assumed where it was cheap to do so: **F1** (`minifyReleaseWithR8`)
and **F3**/**I8** (`lintAnalyzeDebug`) are bound to this host — `nproc` = 2 and `free` = 2,098 MB again,
the same figures every earlier pass measured, and this run's cold `assembleDebug` again needed five
300 s calls, so neither non-resumable task has a window here; **G1** and **I10** duplicate measurements
already recorded beside their items; **H2, H5, H7, H9, H11, H13, H14, M2, M3, M4** would change what
the user reads on a screen or in the exported report; **L2, P2, V2, MM2, MM3** move a reported value,
a verdict or a score and need sign-off. Nothing was ticked that was not done.

Two suspicions were checked against the code before anything was written, and both were **refuted**,
which is why neither became an item:

- `AppViewModel.runAutomatedDiagnostics` looked like a lost update — `var session = _state.value.session`
  followed by `_state.value = _state.value.copy(session = session)` after a multi-second suspension.
  It is not: the session is read *after* `runAutomatedChecks()` returns, not before it, and the read
  and the write are both on the main thread with no suspension point between them. A three-case test
  was written to pin it, confirmed to pass against the unchanged code, and then discarded rather than
  committed as a change that changes nothing.
- `SensorProbe.start`/`probeLiveness` and `AudioProbe` looked like they could leave a listener or a
  recorder registered when a probe times out; both already release on the timeout path
  (`invokeOnCancellation`) and on every failure path, with tests that cover it.

The audit then re-read the one part of the scan the earlier passes had left on the UI thread — the
analysis that follows the collection stages — and found real main-thread work there.

- [x] **WW1. The five analyzers of a scan ran on the main thread, in the frame that draws the last
  progress step.** `AppViewModel.scan` runs on `viewModelScope` (`Dispatchers.Main.immediate`), and
  `provider.collect` only hops to `Dispatchers.IO` for its own stages. After it returned, the scan
  called `authenticityEngine.analyze`, `partsProvider.assess`, `ageEstimator.estimate`,
  `integrityAnalyzer.analyze` and `scoreEngine.score` in the same block, so all five ran inside a
  main-thread frame between the collector's last `stage(...)` update and the report being published.
  The comment beside them claimed the cost was "well under a millisecond on the JVM fixture"; measured
  on this host it is **0.649 ms minimum per report** for the five together (warm, 500 rounds, mock
  snapshot), which a mid-range phone multiplies several times over out of a 16.6 ms frame — dropped
  exactly when the user is watching the scan finish.
  *Change:* the five calls now run inside one `withContext(Dispatchers.Default) { … }`, returning a
  private `ScanAnalysis` holder that the report is then built from. They are pure functions of the
  snapshot, the reference, the session and the purchase date, and nothing is published until all five
  return, so the report, the state, the JSON export and every rendered value are unchanged — only the
  thread they are computed on. `session` and `container.purchaseDateMillis` are read on the main
  thread *before* the hop, which is the same instant they were read at before; the purchase-date read
  (a `SharedPreferences` lookup) now happens before the hop rather than inside it.
  `AppContainer` gains an `authenticityEngineOverride` parameter, in the same shape as its four
  existing test seams, so the thread can be observed.
  *Tests:* three in the new `ScanAnalysisThreadTest`, driving the real `AppViewModel.scan` with a
  recording engine: the analysis does not run on the main looper's thread (confirmed to **fail** when
  the dispatch is put back to `Dispatchers.Main`, then restored), it is still handed the snapshot the
  report is built from and the reference the scan resolved, and its output still reaches both the
  report and the published state. `assembleDebug` green; `testDebugUnitTest` green — **469 tests in 67
  classes, 0 failures, 0 errors, 0 skipped** (466 before, 3 new). `tools/check-no-ui-change.sh` clean
  (31 files tracked under `ui/` and `res/`, none differs; `AppViewModel.kt` is the gate's one declared
  non-rendering exemption and still proves it). Debug APK 9,844,376 bytes — no size claim, since the
  debug APK is not byte-reproducible on this toolchain (J1/J2) and no pre-change baseline was rebuilt
  this run. No screen, control, style, spacing, colour or copy change, and no reported value changes.

## XX. Audit findings — 15 November run (sixth pass)

The unchecked items were taken in file order and each was skipped for the reason it already records,
not re-litigated: **F1** (R8) and **F3**/**I8** (lint) are sandbox-bound — `nproc` = 2 and
`free` = 2,098 MB again this run, and neither `minifyReleaseWithR8` nor `lintAnalyzeDebug` finishes
inside one 300 s call or resumes across calls (this run's cold `assembleDebug` needed six calls);
**I10** duplicates the dependency audit recorded next to F5; **H2, H5, H7, H9, H11, H13, H14, M2,
M3, M4** would change what the user reads on screen or in the exported report; **L2, P2, V2, MM2,
MM3** move a reported value or verdict and need sign-off. Nothing was ticked that was not done.

*Environment, for the next run:* the toolchain recipe from the 14 November pass still holds and was
re-derived from scratch here. Two things are worth writing down again. `repo1.maven.org` and
`repo.maven.apache.org` answer a *jar* request with a redirect to `gcp.mirrors.maven.org`, which the
sandbox proxy refuses (502); the same artifact downloads fine from
`maven-central.storage-download.googleapis.com`, and one GET of a 150 MB jar occasionally returns a
`NoSuchKey` body, so a retry is needed. Gradle's own dependency resolution therefore needs the
offline Robolectric runtime staged locally (`ROBOLECTRIC_DEPENDENCY_DIR`, five
`android-all-instrumented` jars for SDK 29/30/33/34/35) plus an init script that puts a local file
repository *first* — appending it does not work, because resolution fails on the remote before it
reaches the local copy. With `-XX:TieredStopAtLevel=1 -XX:+UseSerialGC` on the Gradle JVM, a cold
`assembleDebug` is six 300 s calls and the 68 test classes run in five filtered invocations of about
three minutes each.

Re-reading the history path for the "error paths that could be silent / destructive operations that
are not ordered" lens produced one finding, which is implemented here.

- [x] **XX1. `FileDiagnosticHistory.clear()` deleted the stored reports before the index that names
  them.** The two deletions are not independent, and the order decided which half-state a clear can
  stop in. `clear` removed every file under `history/reports/` first and `history/index.json` second,
  so a clear interrupted between them — process killed, storage error, the user's device going
  down — left the *worst* of the two states: the index still listing all fifty entries while every
  document behind them was gone. That state is rendered, and it never repaired itself: the History
  screen builds its list from the index and expands an entry by reading the document its id names, so
  every entry opens to nothing; the next `save` reads that index and writes those same entries back
  verbatim, and `pruneReports` finds no file left to remove, so the dead entries outlive every later
  scan. The reverse interruption is invisible — an empty list, and report documents nothing points
  at, which the next save's prune deletes — which is why the index has to go first. `clear` now
  removes the index first, and only deletes the documents once it is gone; when the index cannot be
  removed the documents are left alone, because that clear did not happen and deleting the documents
  behind a surviving index is exactly the broken state above.
  *Change:* one function, `FileDiagnosticHistory.clear`, in a file no screen imports; no file under
  `res/` or `ui/` was touched, and no screen, control, style, layout or copy changed. What a
  completed clear leaves behind is identical to before: no index, no reports.
  *Tests:* `HistoryClearTest` (3 cases) drives the real store over a real filesystem: a clear empties
  both the list and the documents; an index that cannot be removed (the history directory made
  read-only) leaves the documents, the list and the stored text intact; and a document left behind by
  a clear that stopped after its first step is invisible to the list and pruned by the next save.
  The second case was confirmed to **fail** against the previous `clear` — "the documents were
  deleted behind an index that is still there, expected:<[dd931535…]> but was:<[]>" — and to pass
  after the change, so it pins the order rather than the happy path.
  *Verification:* `assembleDebug` green. `testDebugUnitTest` green — **472 tests in 68 classes, 0
  failures, 0 errors, 0 skipped** (469 before plus the three new ones), run in five `--tests`
  invocations because one full-suite invocation does not finish inside this sandbox's 300 s command
  limit; every class ran, none was skipped, and the 472 `@Test` annotations in the tree account for
  the total. `tools/check-no-ui-change.sh` clean (31 files tracked under `ui/` and `res/`, none
  differs). Debug APK 9,921,001 bytes — no size claim, since the debug APK is not byte-reproducible
  on this toolchain and no pre-change baseline was rebuilt this run.
  *Not verified:* no device is available, so the interruption is simulated by making the index
  undeletable rather than by killing the process between the two deletions; and a clear that is
  killed *after* the index is gone but *before* the documents are leaves files on disk until the next
  save prunes them, which is the intended, invisible outcome but is not itself provoked here.

---

## YY. Audit findings — 16 November run

The unchecked items were taken in file order and each was skipped for the reason it already
records, not re-litigated: **F1** (R8) and **F3**/**I8** (lint) are sandbox-bound — `nproc` = 2 and
`free` = 2,098 MB again this run, and neither `minifyReleaseWithR8` nor `lintAnalyzeDebug` finishes
inside one 300 s call or resumes across calls (this run's cold `assembleDebug` needed four);
**I10** duplicates the dependency audit recorded next to F5; **H2, H5, H7, H9, H11, H13, H14, M2,
M3, M4** would change what the user reads on screen or in the exported report; **L2, P2, V2, MM2,
MM3** move a reported value or verdict and need sign-off. Nothing was ticked that was not done, and
no production file differs from the previous commit.

*Environment, for the next run:* the unit tests run here without staging anything by hand. The one
artifact Maven Central refuses through this proxy (`org.robolectric:nativeruntime-dist-compat:1.0.16`
— the redirect to `gcp.mirrors.maven.org` answers 502) downloads fine from
`maven-central.storage-download.googleapis.com`; serve it from a local file repository registered in
an init script's `beforeSettings`, because `RepositoriesMode.FAIL_ON_PROJECT_REPOS` in
`settings.gradle.kts` ignores project repositories and appending the file repository is too late —
resolution fails on the remote first. `robolectric.dependency.repo.url` set to the same mirror on the
`Test` tasks lets Robolectric fetch its own `android-all-instrumented` jars, so no offline dependency
directory is needed. A Robolectric class runs in about a minute once warm; the whole suite still
needs five filtered invocations.

Two findings were re-read against the code and **cleared**, so the next run need not repeat them:

* **The PDF export's `Paint.measureText(CharSequence, int, int)` call is not a min-SDK gap.** The
  wrap measures the `StringBuilder` it is building with the `CharSequence` overload, which is not in
  the API-26 stub list one would guess from the API-29 `MeasuredText` family — the overload was
  checked against the real `android.jar` of platform 26 (`platform-26_r02`) and platform 28
  (`platform-28_r06`) and is present in both. A sweep of every framework member the compiled classes
  reference against the API-26 jar found only members that are already guarded by an `SDK_INT`
  check: `Build.SOC_MANUFACTURER`/`SOC_MODEL`, `PackageInfo.signingInfo`,
  `PackageManager.getInstallSourceInfo`, `CameraCharacteristics.getPhysicalCameraIds`,
  `CameraDevice.createCaptureSession(SessionConfiguration)`, `PowerManager.getCurrentThermalStatus`,
  `Display.Mode.getSupportedHdrTypes` and `WindowManager.getMaximumWindowMetrics`.
* **Robolectric cannot stand in for a missing framework method**, so it cannot pin an API-level
  regression: its shadows carry every method of the newest framework whatever `@Config(sdk = …)`
  emulates. Measured directly: `Paint.measureText(CharSequence, 0, len)` answered normally under
  `@Config(sdk = [28])`. An API-level guard can only be checked against a real `android.jar`, not
  from a unit test.

One finding did clear the bar as a *finding*, and it is the one thing this run did not implement:

- [ ] **YY1. A save whose report document cannot be written lists the entry anyway, and that dead
  entry never goes away.** `FileDiagnosticHistory.save` writes the document and the index entry that
  names it, and the document write is deliberately best-effort — `save` runs inside the scan's own
  `try` block, so a storage error must not discard a scan that has already finished. The entry was
  then added to the index whether or not the document behind it had been written. The index is what
  puts a report on the History screen and the screen re-opens a report by reading the document its
  id names, so such an entry is a row that expands to nothing — and it is permanent: the next save
  reads that index and writes the dead entry back verbatim, and `pruneReports` only deletes files
  the index does not account for, so it has nothing to remove. Only clearing the app's data removes
  it. This is the same broken half-state `XX1` fixed for `clear`, on the `save` path and in the
  other direction. *Change:* index the entry only once `atomicWriteText` has returned (a `stored`
  flag around the existing `try`), which is what `clear` already does by deleting the index first;
  the write itself stays best-effort, and what a failed document write degrades to is what a failed
  index write already degrades to — "this scan is not listed", the state
  `HistoryIndexFailureTest` documents and pins for the other half of the save.
  *Needs sign-off:* on a failed document write the History screen shows one row fewer than it does
  today, which is a change to what the user sees (in a failure path only, but still), and the
  current behaviour is pinned deliberately by
  `HistoryReportWriteTest.a report that cannot be stored leaves no partial file`, so the fix has to
  change that assertion and the comment beside it.
  *Evidence (16 Nov run):* the fix and a three-case `HistoryReportWriteFailureTest` were written and
  run. All three cases failed against the current code — after a read-only `history/reports/`,
  `history.list()` returned one entry whose `loadRaw` is null and whose document was absent from
  disk — and all three passed with the `stored` flag applied, as did `HistoryIndexFailureTest` and
  `HistoryClearTest`. `HistoryReportWriteTest` then failed on its pinned assertion, which is exactly
  the sign-off boundary this item is waiting on, so the change and the test were reverted and no
  production file was touched. The tree this run leaves behind is the previous commit.

## When the list runs out

If fewer than ten unchecked items remain, do not lower the bar and do not invent work. Instead:

1. Re-read the code with the same lens as the sections above — allocations on the scan path, Binder
   calls that could be cached, error paths that could be silent, missing tests around a hot path.
2. Add **specific, verifiable** items to this file (name the function and the change), then implement
   the first one in the same run.
3. Never add an item that requires a visible change. Those belong to a future design pass, not to this
   one.

---

## ZZ. Audit findings — 16 November run

The unchecked items were taken in file order and each was skipped for the reason it already records:
**F1** (R8) is sandbox-bound and was passed over with today's evidence above; **H2, H5, H7, H9, H11,
H13, H14, I8, M2, M3, M4** would change what the user reads on screen or in the exported report;
**L2, P2, V2, MM2, MM3, YY1** move a reported value or verdict and need sign-off; **I10** duplicates
F5. **F3** was open, doable, and is done above.

Re-reading the code behind the lint findings then produced one further finding, which is recorded
here rather than fixed: it moves a verdict the user reads, so it is a sign-off item like L2, P2 and
MM2, not a drive-by edit under the freeze.

- [ ] **ZZ1. A security stage that never ran is reported as a passed root check.**
  `AndroidDeviceInformationProvider.collect` substitutes `fallbackSecurity()` when the security stage
  fails or times out, and that fallback carries `rootIndicators = emptyList()`, `debuggable = false`
  and `verifiedBootState = null`. `DefaultIntegrityAnalyzer` reads the first of those as
  `security.rootIndicators.isEmpty()` → **PASS**, with the explanation "No common root indicators were
  found" — a verified-looking statement about evidence that was never collected — and the
  aggregate integrity verdict and the score move with it. This is the defect class H3 fixed for the
  battery ("a missing value is not a healthy pack") and that `StageTimer`'s own contract names, left
  open on the security facts. The API-29 bug fixed in F3 above is one of the ways a device reached
  this state on every scan, which is why it is worth recording now: the crash is gone, but a *timed
  out* security stage (the 20 s stage deadline, a wedged property service) still reports PASS.
  *Change:* carry "the stage did not run" in `SecurityFacts` (a `collected: Boolean`, or make
  `rootIndicators` nullable) and have the analyzer report UNVERIFIED — dropping the input and lowering
  coverage — when it is absent, exactly as H3 and the score engine's rule 1 already do. *Needs
  sign-off:* on such a device the "Root access" check moves from PASS to UNVERIFIED, and the
  integrity headline, the risk level and the score with it. The current behaviour is not pinned by a
  test, so the fix needs a fixture for the fallback facts as well.

## AAA. Second pass — 16 November

The twenty items left open were taken in file order and each was re-read rather than re-litigated.
**F1** (R8) is sandbox-bound and was passed over with this pass's evidence above; **I8** is the work
F3 completed this morning and **I10** is the audit F5 recorded, so both are now ticked as covered
instead of being passed over for a sixth time; **H2, H5, H7, H9, H11, H13, H14, M2, M3, M4** would
change what the user reads on screen or in the exported document; **L2, P2, V2, MM2, MM3, YY1, ZZ1**
move a reported value or verdict and need sign-off, which this freeze cannot give itself.

No new finding cleared the bar. The scan path (`AndroidDeviceInformationProvider`'s concurrent stage
fan-out and its fallbacks, `StageTimer`, `ScanCoordinator`), the two export paths (`toPdf` and
`toJsonFile` both already hop off the main thread) and `Formatters` were re-read for the defect
classes the sections above record — work repeated per call, work on the UI thread, an error path that
silently invents a value, a resource left open — and none of them holds one that is left.

**Nothing was implemented and no production or test file changed.** This pass commits backlog state
only: the two ticks above and the environment notes below. That is the honest outcome for a list
whose every remaining entry is blocked, and inventing a cosmetic change to fill the run is exactly
what the standing brief forbids.

### Build environment, for the next run (no repository file changed for any of it)

* `JAVA_TOOL_OPTIONS` must be unset (the image exports a 64 MB class-space ceiling that starves D8 and
  the Kotlin compiler), and the build wants
  `-Dorg.gradle.jvmargs="-Xmx1400m -XX:MaxMetaspaceSize=512m -XX:TieredStopAtLevel=1 -XX:+UseSerialGC"`.
  With those, this pass's warm `assembleDebug` linked in **1m13s** and the full suite ran green in
  **3m46s**.
* **New this pass:** the unit-test suite cannot start at all on this host, and the cause is the proxy,
  not the code. `repo.maven.apache.org` answers **502** for
  `org.robolectric:nativeruntime-dist-compat:1.0.16` (it failed the dependency resolution and then
  the configuration-cache serialisation), and Robolectric's own runtime fetch
  (`MavenArtifactFetcher`) fails against the same host with an `IOException`. Both were unblocked
  with a sandbox-only Gradle init script — not in the tree — that prepends
  `https://maven-central.storage-download.googleapis.com/maven2/` for the `org.robolectric` group
  and sets `robolectric.dependency.repo.id`/`robolectric.dependency.repo.url` on every `Test` task to
  that mirror. No build file, repository list or dependency changed, and the mirror serves the same
  bytes Maven Central does. A run that sees `MavenArtifactFetcher` or a 502 on
  `nativeruntime-dist-compat` should reach for this first rather than suspecting the tests.

## BBB. 17 November run

The eighteen items still open were taken in file order and re-read rather than re-litigated.
**F1** (R8) is sandbox-bound and was passed over with this pass's evidence below: `nproc` = 2,
`free` = 2,098 MB, a cold `assembleDebug` alone needed four 300 s calls (about 19 minutes), and
`minifyReleaseWithR8` is still non-resumable, so no minified APK can be produced or measured here.
**H2, H5, H7, H9, H11, H13, H14, M2, M3, M4** would change what the user reads on screen or in the
exported document; **L2, P2, V2, MM2, MM3, YY1, ZZ1** move a reported value or verdict and need
sign-off, which this freeze cannot give itself.

No new finding cleared the bar. Re-read this pass, for the defect classes the sections above record
(work repeated per call, work on the UI thread, an error path that invents a value, a resource left
open, an unbounded wait): `Formatters` and `ReportJson`, the PDF write path and page writer in
`PdfReportGenerator`, `FileDiagnosticHistory` (`save`, `clear`, `pruneReports`, `atomicWriteText`),
`AppViewModel`'s state plumbing, `ScanCoordinator` / `LatestRequest` / `WeakRefHolder` /
`StageTimer`, `AppContainer`, `AssetReferenceRepository` / `ReferenceIndex`, and the transport,
telephony and location rows of `ConnectivityCollector`. Each already carries the fix its own history
records, and the two real defects that remain in this code (`Formatters.duration`'s double-counted
days and the unencrypted-volume VERIFIED, MM2 and P2) are reported-value changes waiting on sign-off,
not oversights.

**Nothing was implemented and no production or test file changed.** This pass commits this note
only. That is the honest outcome for a list whose every remaining entry is blocked, and inventing a
cosmetic change to fill the run is exactly what the standing brief forbids.

### Build and test state on this host, 17 November (no repository file changed for any of it)

* `assembleDebug` green: cold build in four calls (about 19 minutes, 1.0 GB of Gradle cache), warm
  relink 3m15s. APK 9,844,412 bytes.
* `testDebugUnitTest` green: **474 tests, 0 failures, 0 skipped**, 3m45s once warm.
* The `org.robolectric:nativeruntime-dist-compat:1.0.16` 502 the 16 November pass recorded is still
  there — `repo.maven.apache.org` and `repo1.maven.org` both answer 502, while
  `maven-central.storage-download.googleapis.com` serves the 158 MB jar. **New this pass:** appending
  the mirror is not enough, because Maven Central is resolved first and its 502 wins; the mirror has
  to come *before* it, and `RepositoriesMode.FAIL_ON_PROJECT_REPOS` means the only place it can be
  added is `dependencyResolutionManagement.repositories` in an init script —
  `repositories.clear()`, then `google()`, then the mirror, then `mavenCentral()`, with
  `robolectric.dependency.repo.url` set to the same mirror on every `Test` task. That is what makes
  the suite start here. No build file, repository list or dependency was changed for it.

## CCC. Third pass — 17 November

The eighteen items open above were taken in file order and each was re-read against the code rather
than re-litigated. **F1** (R8) was retried rather than assumed, with two new facts recorded below.
**H2, H5, H7, H9, H11, H13, H14, M2, M3, M4** still change what the user reads on screen or in the
exported document, and **L2, P2, V2, MM2, MM3, YY1, ZZ1** still move a reported value or verdict and
need sign-off this freeze cannot give itself. Each was re-confirmed in the source this pass, not
copied forward: `M2` still declares `total = 8` over seven `stage(...)` calls in
`AndroidDeviceInformationProvider.collect`; `M4`'s `finishPage` still leaves the small paint at
7.5 pt; `P2`'s `encryptionState.contains("encrypted", true)` still passes `unencrypted`; `MM2`'s
`Formatters.duration` still takes `remainingDays = days % 30`; `YY1`'s `FileDiagnosticHistory.save`
still adds the index entry outside the document write; `H7`'s transport list still carries VPN as
one more transport with no underlying one; `H14` is still data the recorded `sourceUrl` pages do not
carry.

### F1, retried (16 November's conclusion was not taken on trust)

The release input chain was built from cold on this host (`nproc` = 2, `free` = 2,098 MB): the
dependency cache is 899 MB, `:app:compileReleaseKotlin` needed three 300 s calls (the third finished
in 2m23s), and every remaining release input task was then brought UP-TO-DATE so that a later call
went straight into R8 with a full window and a reused configuration cache. Three R8 runs followed:

* `-Xmx1700m`, `-XX:MaxMetaspaceSize=384m`, default tiered JIT, inputs half warm: killed at the call
  limit with `outputs/mapping/release/` empty.
* the same, with every input UP-TO-DATE and G1 replaced by ParallelGC: **R8 got further than any
  earlier attempt on record** — it completed the shrink phase and wrote `seeds.txt` (109,913 B) and
  `usage.txt` (2,129,920 B) — and was then killed inside dexing.
* `-dontoptimize` appended to `proguard-rules.pro` (shrink and obfuscate only, the configuration
  AGP's own non-optimising template uses), inputs warm: identical outcome — shrink complete
  (`seeds.txt` 110,264 B, `usage.txt` 2,129,920 B), killed inside dexing.

So the blocker is now located precisely: it is not the optimizer and not the input chain, it is
R8's dexing of the Compose-dominated program classpath on two cores, which does not fit in the
~280 s a single call leaves after Gradle's own start-up, and which restarts from zero when the call
is killed. No minified APK exists to measure, so there is still no "after" to compare with the
recorded **6,570,591-byte** unminified baseline, and the release path is untouched —
`app/build.gradle.kts` and `app/proguard-rules.pro` are back at `HEAD`. This needs a host where one
Gradle invocation can run longer than 300 s; a seventh retry here would produce the same three logs.

### Sub-threshold observations (recorded, not queued)

Not added as items, because each is once-per-scan work with no measured consequence, and the brief
forbids padding the list with cosmetic entries:

* `IdentityCollector` still builds a `SimpleDateFormat("dd MMM yyyy HH:mm")` per call for the
  firmware build date, the one instance left of the pattern `Formatters`' own KDoc records as fixed
  (one format, calendar and locale data per call, ~2 KB). It runs once per scan, not per row.
* `SystemPropertyReader.get` re-enters the `@Synchronized` `loadDump` on every property read, so the
  concurrent stages serialise on that monitor after the dump is loaded. Uncontended, ~60-100 reads
  per scan.
* The remaining per-call `String.format` sites in the collectors and in `DefaultAuthenticityEngine`
  are once per scan each (a handful per camera and per sensor); A2's win came from the sites that
  ran per row.

**Nothing was implemented and no production or test file changed.** The tree this pass leaves behind
differs from `HEAD` in this section only.

## DDD. 18 November run

The eighteen items open above were taken in file order and each was re-read in the source rather
than re-litigated. **F1** (R8) is sandbox-bound: `nproc` = 2, `free` = 2,098 MB, and `minifyReleaseWithR8`
is still non-resumable — see the operational note at the end of this section, which is the one thing
this pass learned about it. **H2, H5, H7, H9, H11, H13, H14, M2, M3, M4** would change what the user
reads on screen or in the exported document. **L2, P2, V2, MM2, MM3, YY1, ZZ1** move a reported value
or verdict and need sign-off this freeze cannot give itself.

Each was re-confirmed in the source this pass, not copied forward: `M2` still declares `total = 8`
over seven `stage(...)` calls in `AndroidDeviceInformationProvider.collect`; `M3`'s Display-and-sensors
input still counts `hardware.sensors.size` (line 353) where Hardware health counts
`logicalSensorCount` (line 104); `M4`'s `finishPage` still sets `smallPaint.textSize = 7.5f`
(`PdfReportGenerator`, line 266); `P2` still judges the volume with
`security.encryptionState.contains("encrypted", true)` (`DefaultIntegrityAnalyzer`, line 189), which
`unencrypted` satisfies; `ZZ1` still reads an empty `rootIndicators` list as a PASS (line 25) while
`fallbackSecurity()` supplies exactly that; `L2` still derives `fpProduct`/`fpDevice` (lines 92-93)
and compares only `fpBrand` (line 94); `MM2`'s `Formatters.duration` still takes
`remainingDays = days % 30`; `YY1`'s `FileDiagnosticHistory.save` still adds the index entry outside
the document write's `try`; `V2`'s `recordResult` and `setPurchaseDate` still call `rebuildReport`
directly (lines 197, 211).

### Re-read this pass, with nothing found

The defect classes these sections record — work repeated per call, work on the UI thread, an error
path that invents a value, a resource left open, an unbounded wait, a write that is not crash-safe —
were walked again over the paths a user actually waits on:

* **The history path.** `FileDiagnosticHistory.save`, `list`, `loadRaw` and `clear` all already open
  with `withContext(Dispatchers.IO)`, so `AppViewModel.scan`'s `history.save` and the screen's
  `list`/`loadRaw` are off the main thread despite being called from a `Main.immediate` scope. The
  writes are atomic (O1, M5, XX1) and the index write is best-effort (T1). Nothing left here.
* **The diagnostics path.** `AndroidHardwareDiagnosticsEngine.runAutomatedChecks` opens with
  `withContext(Dispatchers.IO)`, so the 1.5 s accelerometer liveness probe and the battery
  `registerReceiver` do not run in the click; each check is isolated by `automatedCheck` (including
  `LinkageError`). Nothing left here.
* **The scan fan-out.** `StageTimer` bounds each stage and the whole scan, abandons a stage blocked
  in a Java call instead of waiting for it, and distinguishes caller cancellation from a stage's own
  `CancellationException`; `SystemPropertyReader.readProcessDump` bounds both the read and the reap.
  Nothing left here.
* `ReferenceIndex`, `AnalysisSupport`, `Formatters`, `CpuFrequency` and `FileDiagnosticHistory` all
  hoist their patterns, formatters and name matchers; no per-row `String.format`, `Regex` or
  `SimpleDateFormat` construction is left on a path that runs per row.

### Sub-threshold observation, now measured and closed

The 17 November pass recorded, without queuing it, that `SystemPropertyReader.get` re-enters the
`@Synchronized` `loadDump` on every read, so the ten concurrent stages serialise on that monitor
after the dump is loaded. Measured this pass rather than left as an impression: a JVM harness with
the reader's exact hot path, ten threads (the scan's stage fan-out) reading 900-key properties after
the dump is loaded, best of five rounds — **64 ns per read with the monitor taken on every call
against 47 ns with a volatile fast path, 1.36x**. A scan makes about a thousand property reads, so
the whole contention is **~17 microseconds of a scan that takes seconds** — 0.001%. Below the bar for
a production change under this freeze, and now closed as such instead of being re-opened by the next
pass. The measurement harness is not committed: it is a scratch benchmark, not a test.

### F1: the earlier attempts were competing with orphaned daemons

Not a retry — an operational finding worth recording before the next one. `org.gradle.daemon=false`
means a build killed at the 300 s call limit leaves its single-use daemon **alive and still working**;
this pass found two of them at 65% and 20% CPU on a two-core box, from calls that had already been
killed, and `:app:mergeExtDexDebug` — recorded above as taking 3m37s to 4m08s and never finishing in
a call — completed **inside one call** once they were killed (`pkill -9 -f GradleDaemon`). So the
"a killed task restarts from zero" conclusion in the 5 and 6 November notes holds for the *task*, but
the host was also being starved by the previous attempt's own daemon, which no earlier note accounts
for. The next F1 attempt should start from a quiet host: kill any surviving daemons first, then build
the release input chain and let R8 have the whole window.

### Build and test state on this host, 18 November (no repository file changed for any of it)

* `assembleDebug` green: cold build in five calls (about 25 minutes, 911 MB of Gradle cache); APK
  **9,844,412 bytes**, byte-identical to the 17 November build, as expected for an unchanged tree.
* `testDebugUnitTest` green: **474 tests, 0 failures, 0 errors, 0 skipped**, 3m54s once warm.
* The `org.robolectric:nativeruntime-dist-compat` 502 on Maven Central is still there and still needs
  the Google mirror listed *before* `mavenCentral()` from an init script, plus
  `robolectric.dependency.repo.url` on every `Test` task. No build file, repository list or dependency
  was changed for it.

**Nothing was implemented and no production or test file changed.** The tree this pass leaves behind
differs from `HEAD` in this section only. That is the honest outcome for a list whose every remaining
entry is blocked, and inventing a cosmetic change to fill the run is exactly what the standing brief
forbids.

## EEE. Second pass — 18 November

The eighteen items open above were taken in file order. **F1** (R8) was attempted again rather than
assumed, from the quiet host the first pass of today asked for; the rest are blocked for the reasons
their entries record: **H2, H5, H7, H9, H11, H13, H14, M2, M3, M4** change what the user reads on
screen or in the exported document, and **L2, P2, V2, MM2, MM3, YY1, ZZ1** move a reported value or
verdict and need sign-off this freeze cannot give itself.

### F1, attempted from a quiet host: three hypotheses closed

This pass began in an empty sandbox with no surviving daemons (`pgrep -f GradleDaemon` empty,
2,045 MB of 2,098 free), which is exactly the condition the first pass of today recommended. Cold
`assembleDebug` was green in five calls at the recorded 9,844,412-byte APK; the release inputs were
then warmed with `isMinifyEnabled = true` and the keep rules written (a `-keepclassmembers` rule for
the `(Application)` constructor that `AndroidViewModelFactory` reflects on, and `-dontwarn` for the
two platform classes looked up by name). Three facts, none of them on record before:

* **A quiet host is not the missing minute.** With no competing daemon, `:app:minifyReleaseWithR8`
  still completed the shrink phase (`seeds.txt` 110,028 B, `usage.txt` 2,129,920 B) and was killed
  inside dexing at the 270 s call limit. The shrink phase alone takes about four of those minutes,
  which is why no arrangement of the remaining window reaches the end of dexing.
* **The code cache was a plausible but wrong explanation.** `gradle.properties` caps
  `-XX:ReservedCodeCacheSize` at 96 MB, which would drop a four-minute JVM into the interpreter once
  C2's code cache fills. Raising it to 256 MB (heap 1300 MB, `-Dorg.gradle.jvmargs=...`) changed
  nothing measurable: shrink completed, dexing was still cut off.
* **The daemon does not survive the kill usefully.** The first pass of today found orphaned daemons
  still working after a killed call, which suggested a killed R8 might finish on its own. Measured
  directly: the single-use daemon is still at 127% CPU the instant the client dies and exits within
  ten seconds, abandoning the task. A killed R8 therefore leaves nothing to reuse, and
  `org.gradle.daemon=false` makes the task non-resumable by design.

`app/build.gradle.kts` and `app/proguard-rules.pro` are back at `HEAD`. No minified APK exists to
measure against the recorded **6,570,591-byte** unminified baseline, so F1 stays open for a host
where one Gradle invocation can run longer than 300 s; a sixth attempt here would reproduce these
logs.

### Re-read this pass, nothing found

The defect classes these sections record — work repeated per call, work on the UI thread, an error
path that invents a value, a resource left open, an unbounded wait, a locale-sensitive comparison —
were walked again over `SystemPropertyReader` (dump deadline, retry cap, bounded reap),
`AudioProbe` / `SensorProbe` (every `MediaRecorder`, `AudioTrack` and `SensorEventListener` has a
matching release, and both already carry release tests), the two `ACTION_BATTERY_CHANGED` sticky
receivers, and the sensor test screen's nullable min/max, whose `!!` sites are each guarded by the
null check on the line above. No `toUpperCase()`/`toLowerCase()` without a `Locale` exists in
`src/main`, and every `String.format` on a user-visible path passes `Locale.US` or `Locale` data.

**Nothing was implemented and no production or test file changed.** The tree this pass leaves behind
differs from `HEAD` in this section only, and `assembleDebug` (9,844,412 bytes) and
`testDebugUnitTest` (474 tests, 0 failures, 0 skipped) are green on it.

## FFF. Third pass — 18 November

The eighteen unchecked items were taken in file order, and the sites the two earlier passes of today
rested on were re-read in the source rather than copied forward: `AndroidDeviceInformationProvider`
line 69 still declares `total = 8` over seven `stage(...)` calls (M2); `DefaultIntegrityAnalyzer` still
reads `security.rootIndicators.isEmpty()` as PASS (ZZ1) and still judges the volume with
`security.encryptionState.contains("encrypted", true)`, which `unencrypted` satisfies (P2);
`PdfReportGenerator.finishPage` still assigns `smallPaint.textSize = 7.5f` and never restores it (M4);
`DefaultScoreEngine` line 353 still scores the Display-and-sensors section on `hardware.sensors.size`
where line 104 uses `logicalSensorCount` (M3); `Formatters.duration` still takes
`remainingDays = days % 30` (MM2); `AppViewModel.recordResult` and `setPurchaseDate` still call
`rebuildReport` on the caller's thread (V2).

**F1** (R8) was not retried. The second pass of today closed its last three hypotheses — quiet host,
code cache, daemon survival — with measurements from this same 2-core / 2,098 MB host, and
`minifyReleaseWithR8` needs the shrink phase *and* dexing inside one non-resumable 300 s call. A third
attempt today would reproduce those logs rather than advance the item; it stays open for a host where
one Gradle invocation can run longer than 300 s.

**H2, H5, H7, H9, H11, H13, M2, M3, M4** change what the user reads on screen or in the exported
document; **H14** needs reference data the app does not carry; **L2, P2, V2, MM2, MM3, YY1, ZZ1** move
a reported value or verdict and need sign-off this freeze cannot give itself.

**Nothing was implemented and no production, test, resource, manifest or Gradle file changed.** No
build was run, because no compiled input differs from `HEAD` (`d66ecd6`), whose `assembleDebug`
(9,844,412-byte APK) and `testDebugUnitTest` (474 tests, 0 failures, 0 skipped) were recorded green by
the 09:02 pass today. The tree this pass leaves behind differs from `HEAD` in this section only.

## GGG. Fourth pass — 18 November

The eighteen unchecked items were taken in file order and re-read in the source rather than
re-litigated. Nothing had changed since the third pass of today, and each is blocked for the reason
its entry already records: **F1** (R8) needs one Gradle invocation that can run longer than 300 s
(`nproc` = 2, `free` = 2,098 MB; the second pass of today closed its remaining hypotheses with
measurements from this host), **H2, H5, H7, H9, H11, H13, M2, M3, M4** would change what the user
reads on screen or in the exported document, **H14** needs reference data the app does not carry, and
**L2, P2, V2, MM2, MM3, YY1, ZZ1** move a reported value or verdict and need sign-off this freeze
cannot give itself.

The history path was re-read against the one claim it still rests on — T1's "pruning stays correct
without further change" after the index write became best-effort — and that claim turned out to be
false once the store is full. The finding below was implemented in this run.

- [x] **GGG1. A save whose index write failed pruned the report the surviving index still named.**
  `FileDiagnosticHistory.save` writes the report document, then the index, then prunes report files
  that fell out of the index. T1 made the index write best-effort and justified the prune with
  "`kept` is the previous entries plus this one, so it can only be a superset of what the index still
  on disk points at". That holds only while the store is under its cap: `kept` is
  `entries.take(MAX_INDEX_ENTRIES)`, so on the save that pushes a fifty-first entry in, `kept` is the
  *previous* fifty entries minus the oldest, plus the new one — not a superset of `previous`. With the
  index write failing, the index on disk is still `previous`, which still names the entry that fell
  off the end, and the prune deleted that entry's document anyway. The History screen builds its list
  from the index and expands an entry by reading the document its id names, so the result is one row
  that opens to nothing — and it is permanent for the same reason YY1 records: the next save reads
  that index and writes the dead entry back verbatim, while `pruneReports` has no file left to remove.
  A stored report is lost, from the `save` path, in a failure path that T1 documented as safe.
  *Change:* `save` keeps the index write best-effort and passes the prune the condition it actually
  needs — `indexReadable = previous != null && indexed`, where `indexed` is `runCatching {
  writeIndex(kept) }.isSuccess`. A prune now runs only against an index that was really written, so
  what it deletes is what the index on disk no longer names. When the write failed, nothing is pruned
  at all, which is also what keeps the document this save just wrote — an orphan with no entry
  pointing at it, for the next successful save to account for, exactly as T1's third test pins. A
  completed save prunes precisely what it pruned before; the signature, the entry returned, the JSON
  written and the order of the three writes are unchanged, and no `ui/` or `res/` file was touched.
  *Tests:* one new case in `HistoryIndexFailureTest` — fifty saves to fill the index to its cap, the
  index write then blocked with the existing obstruction, a fifty-first save, and then every id the
  surviving index still lists must still open. It **fails against `HEAD`** with "java.lang.AssertionError:
  the report the surviving index still names was pruned" and passes with the change, so it pins the
  ordering rather than the happy path; the three cases T1 added to the same class still pass, which is
  what rules out the first shape this fix was tried in (pruning against `previous` instead of not at
  all), which deleted the newly written document and failed all three.
  *Verification:* `assembleDebug` green (1m04s warm; APK 9,921,072 bytes — no size claim, the debug
  APK is not byte-reproducible on this toolchain and no pre-change APK was kept). `testDebugUnitTest`
  green — **475 tests** (474 before, plus the one above), every test class covered by four filtered
  invocations, 0 failures, 0 errors, 0 skipped. `tools/check-no-ui-change.sh HEAD` clean (31 files
  tracked under `ui/` and `res/`, none differs).
  *Not verified:* no device is available, so the failed index write is simulated by making
  `history/` read-only rather than by filling the data partition, and the effect is asserted at the
  store — that the entry still opens — rather than through the History screen.

### Build notes for the next pass on this host

Two things cost this run most of its time and are worth recording.

* `:app:compileDebugKotlin` did not finish inside a 285 s window at the committed
  `org.gradle.jvmargs` (`-Xmx1400m`, G1, parallel GC) — it restarted from zero on every call, five
  times. With `-Dorg.gradle.jvmargs="-Xmx1600m -XX:MaxMetaspaceSize=384m -XX:+UseSerialGC
  -XX:TieredStopAtLevel=1"` the same task completes in **2m36s** on the first attempt, and every
  later build reuses it. Nothing in the repository was changed for this; it is a command-line flag.
* `org.robolectric:nativeruntime-dist-compat:1.0.16` still answers 502 through the proxy's
  `repo.maven.apache.org` → `gcp.mirrors.maven.org` redirect, as the second pass of today recorded.
  An init script that clears `dependencyResolutionManagement.repositories` and re-adds
  `https://maven-central.storage-download.googleapis.com/maven2/` first, plus
  `robolectric.dependency.repo.url` on every `Test` task, resolves it; one `--refresh-dependencies`
  pass is needed to move the cached resolution off the failing repository.
