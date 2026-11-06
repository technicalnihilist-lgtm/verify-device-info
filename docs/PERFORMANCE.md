# Performance measurements

Numbers, not adjectives. Every row is produced by `tools/measure.sh`, so any claim of "smaller" or
"faster" in the backlog can be reproduced with one command:

    tools/measure.sh            # measure the debug APK that is already built
    tools/measure.sh --build    # time a debug build, then measure what it produced

| date (UTC) | apk | apk bytes | dex methods | dex classes | dex files | dex bytes | build seconds |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 2026-11-06 | app-debug.apk | 9811608 | 102504 | 14071 | 12 | 25462860 | - |

## What the baseline row says

Measured on 6 November 2026 against this checkout, debug variant, after a clean build: the APK is
**9,811,608 bytes**, and it carries **102,504 methods** and **14,071 classes** across **12 dex files**
(25,462,860 bytes of dex before compression). Min SDK 26 packages native multidex, which is why one
build produces twelve dex files rather than one.

The method and class counts are read from the DEX headers themselves, then cross-checked against
`dexdump` from build-tools 35.0.1: 102,504 in both, so the numbers in the table are the platform's
own view of the APK, not an estimate.

## Reproducing a row

    tools/measure.sh            # measure the debug APK that is already built
    tools/measure.sh --build    # time a debug build first, then measure what it produced
    tools/measure.sh --apk app/build/outputs/apk/release/app-release-unsigned.apk
    tools/measure.sh --print    # print the row without appending it to this file

Nothing beyond `unzip` and `python3` is required, so a row can be taken on any machine that can build
the app. `--apk` is there for the release artefact, which is the number that matters for shipping;
the debug APK is the one every daily run can measure.

A build time is only recorded when `--build` is used, and it is a wall-clock number for the machine
that produced it. The sandbox these runs happen in has two cores and 2 GB of RAM, where a cold debug
build takes far longer than on a developer machine, so its build-seconds column is not comparable
across hosts. The APK size, method count and class count are.

## Where the per-item numbers live

Every backlog item carries its own before/after measurement next to it, in
`docs/IMPROVEMENT-BACKLOG.md`, because that is where the change and the reason for it already are.
This file holds the artefact-level baseline those items move against. Two of those measurements are
hardware-independent and worth repeating here:

- **H4 (wake-up sensors):** the same sensor list that enumerated 4 handles now counts 2 logical
  sensors, and the "Sensors enumerated" figure and its score input agree.
- **H12 (per-core CPU frequency):** a big.LITTLE fixture whose `cpu0` node reports 1,800,000 kHz and
  whose `cpu7` reports 2,841,600 kHz now reports 2,841,600 kHz; before, it reported `cpu0`'s ceiling.

A number is only recorded here or in the backlog if a run actually produced it. No device is
available to these runs, so handset-level figures are the one thing this file cannot supply.
