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

- [ ] **C1. Single `SystemPropertyReader` instance.** It is currently constructed in three places, so
  the `getprop` dump is parsed up to three times per scan. Share one instance from `AppContainer`.
- [ ] **C2. Cancel an in-flight scan when a new one starts.** `AppViewModel.scan` returns early if
  `scanning` is true; a queued second tap should either be ignored cleanly or cancel and restart.
- [ ] **C3. Debounce `rebuildReport`.** Recording a test re-runs four engines; rapid test results
  (e.g. several sensors) trigger redundant work. Coalesce.
- [ ] **C4. Verify probe teardown.** Confirm `SensorProbe`, `AudioProbe` and `CameraProbe` release
  everything on every path, including when a test screen is left via the system back gesture.
- [ ] **C5. Avoid holding `Context` in long-lived objects.** Audit `AppContainer` and collectors for
  anything that keeps an `Activity` reference.

## D. Robustness — graceful degradation

- [ ] **D1. Timeout the whole scan.** A stuck camera or property read should not hang the scan forever;
  add a per-stage timeout with a clear "timed out" status instead of a silent hang.
- [ ] **D2. Fuzz the reference JSON loader.** A malformed entry currently aborts the whole load; skip
  the bad entry and keep the rest.
- [ ] **D3. Empty-state audit.** Every screen must render a sensible state when `snapshot` is null and
  when `report` is null — verify each one, no crash on first launch.
- [ ] **D4. `PermissionManager` preferences.** `activity.getPreferences(0)` opens a file per call;
  cache the `SharedPreferences` handle.

## E. Tests — raise confidence without a device

- [ ] **E1. JSON report round-trip test.** Assert every documented key exists and that redaction
  replaces exactly the sensitive fields.
- [ ] **E2. Parts provider matrix test.** One test per `ComponentState`, asserting the state chosen for
  a given evidence set.
- [ ] **E3. Age estimator boundary tests.** Launch date unknown, build date unknown, purchase date in
  the future, cycle count zero.
- [ ] **E4. Reference matching tests.** Sub-brand match, longest-pattern preference, no match, and a
  model string that contains two patterns.
- [ ] **E5. Score coverage tests.** Coverage exactly at the 30% floor, and just below it.
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
