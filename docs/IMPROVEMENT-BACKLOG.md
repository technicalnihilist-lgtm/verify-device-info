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

- [ ] **A1. Precompile the regex in `AnalysisSupport.normalise`.** `Regex("[^a-z0-9]")` is compiled on
  every call and this function runs in tight loops over model, brand and SoC strings. Hoist it to a
  `private val`. *Verify:* unit tests unchanged; no behaviour difference.
- [ ] **A2. Replace `String.format` in `Formatters`.** `Formatters.bytes` and friends allocate a
  `Formatter` per call and are used in list rendering. Implement the decimal formatting by hand.
  *Verify:* add unit tests pinning the exact output strings before changing anything.
- [ ] **A3. Cache the normalised reference patterns.** `AssetReferenceRepository.match` normalises every
  pattern on every scan. Normalise once at load time.
- [ ] **A4. Avoid rebuilding the identity blob per scan.** `DefaultAuthenticityEngine` lowercases and
  joins six strings on each run; build it once per call and reuse for all marker checks.
- [ ] **A5. Reuse `Paint` objects in the PDF writer.** `PageWriter` allocates paints per instance but
  re-creates some per page; hoist to fields and verify the PDF still renders identically.

## B. Correctness — real bugs and edge cases

- [ ] **B1. Prune old report files.** `FileDiagnosticHistory` caps the *index* at 50 entries but never
  deletes the JSON files in `history/reports/`, so storage grows forever. Delete reports that fall out
  of the index.
- [ ] **B2. Write the history index atomically.** A crash mid-write leaves a truncated JSON file that
  silently drops all history. Write to a temp file and `rename`.
- [ ] **B3. Guard against an empty report list on load.** `loadRaw` returns null for a missing file;
  confirm the History screen never shows a blank expanded card, and that a corrupt file degrades to a
  message rather than an exception.
- [ ] **B4. Fix the `sincePatch` off-by-one.** `DefaultIntegrityAnalyzer` computes patch age from the
  first of the month; confirm the boundary at exactly 365 and 730 days behaves as documented.
- [ ] **B5. Handle a zero-size sensor list.** `ScoreEngine` treats an empty sensor list as 0; on a
  device with no sensors reported this should be excluded, not scored as failure.
- [ ] **B6. `HardwareCollector.readDisplay` fallback.** If `getRealMetrics` throws (multi-display
  devices), fall back to `DisplayMetrics` from resources instead of returning null.
- [ ] **B7. Camera characteristics race.** Enumerating cameras while another app holds the camera can
  throw `CameraAccessException`; make sure it is caught per camera id, not per whole enumeration.
- [ ] **B8. Battery `healthPercent` sanity.** Clamp against a plausible range and refuse to report a
  percentage when the charge counter is clearly not at full charge.

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
