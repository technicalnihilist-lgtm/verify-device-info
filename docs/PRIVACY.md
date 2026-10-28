# Privacy

## Summary

* **No network access.** The app declares no `INTERNET` permission, makes no HTTP requests, and has no
  analytics, crash reporting or advertising SDK. Everything works in airplane mode.
* **No account, no cloud, no AI model.** Nothing is uploaded and nothing is downloaded.
* **Local history only.** Past scans are written to app-private storage (`filesDir/history/`). They are
  excluded from cloud backup and device transfer in `data_extraction_rules.xml`, and can be deleted
  from the History screen.
* **Sharing is explicit.** A report leaves the device only when you tap share and pick a destination
  yourself.
* **Redaction is on by default.** Serial number, Bluetooth adapter name and the APK signing-certificate
  digest are replaced with `REDACTED` in exports unless you turn redaction off.

## Permissions and why they exist

| Permission | Requested when | Used for |
|---|---|---|
| `CAMERA` | You open a camera or flashlight test | Live preview and torch. Nothing is captured or saved. |
| `RECORD_AUDIO` | You start the microphone test | Three-second sample, measured for level, then deleted. |
| `ACCESS_FINE_LOCATION` | You run the GNSS test | Reads the last known fix to prove the receiver works. Not stored, not transmitted. |
| `BLUETOOTH_CONNECT` | You run the Bluetooth test (Android 12+) | Reads the adapter's enabled state. |
| `ACCESS_WIFI_STATE`, `ACCESS_NETWORK_STATE` | Never prompted | Radio presence and active transport. |
| `NFC`, `VIBRATE`, `FLASHLIGHT` | Never prompted | Radio state, haptics, torch. |

Information collection itself requires **no dangerous permission**. Declining a permission turns that
one test into `PERMISSION_REQUIRED`; the rest of the app keeps working.

## What is deliberately not collected

* No location history, no SSIDs, no BSSIDs, no IMEI, no IMSI, no phone number, no contacts, no
  messages, no files, no photos, no app contents.
* Biometric authentication is queried for **availability only** — no template, image or identifier is
  ever read.
* Other installed apps are counted, not enumerated. The app does not request `QUERY_ALL_PACKAGES`.

## Identifiers in exports

By default the PDF and JSON exports redact the hardware serial (which is usually inaccessible anyway),
the Bluetooth adapter name, and the app's signing-certificate digest. Device model, build fingerprint
and SoC remain, because they are the substance of the report.
