# Building

## Requirements

| Tool | Version |
|---|---|
| JDK | 17 (Temurin recommended) |
| Android SDK platform | 35 |
| Android SDK build-tools | 35.0.1 |
| Gradle | 8.9 (wrapper included) |
| Kotlin | 2.0.21 (via the version catalog in `build.gradle.kts`) |

`compileSdk = 35`, `targetSdk = 35`, `minSdk = 26`.

## Debug build

```bash
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

The debug variant carries the `.debug` application-id suffix so it can sit beside a release install.
It is the only variant where the mock-device fixture is reachable, and every screen that shows mock
data labels it as such.

## Unit tests

```bash
./gradlew testDebugUnitTest
# report: app/build/reports/tests/testDebugUnitTest/index.html
```

28 tests cover the analysis engines. They run on the JVM with no emulator, because `core` and
`domain` have no Android dependencies.

## Release build and signing

Create `keystore.properties` in the project root (it is git-ignored):

```properties
storeFile=/absolute/path/to/release.jks
storePassword=…
keyAlias=…
keyPassword=…
```

Generate a key if you do not have one:

```bash
keytool -genkeypair -v -keystore release.jks -alias verifydeviceinfo \
  -keyalg RSA -keysize 4096 -validity 10000
```

Then:

```bash
./gradlew assembleRelease
# app/build/outputs/apk/release/app-release.apk
```

If `keystore.properties` is absent the release APK is produced unsigned, which is what CI does when no
signing secret is configured.

Verify the result:

```bash
$ANDROID_HOME/build-tools/35.0.1/apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
$ANDROID_HOME/build-tools/35.0.1/aapt2 dump badging app/build/outputs/apk/release/app-release.apk
```

## Device testing

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -s VerifyDeviceInfo AndroidRuntime
```

Suggested matrix: one Android 8/9 device (API 26–28, where several fields are absent by design), one
Android 12/13 device, one Android 14+ device (cycle count available), and at least two manufacturers so
vendor-specific property behaviour is exercised. Tablets are worth testing for the responsive layouts
and for hardware that lacks telephony.

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `SDK location not found` | Set `ANDROID_HOME` or create `local.properties` with `sdk.dir=…`. |
| `Failed to find target with hash string 'android-35'` | Install platform 35 and build-tools 35.0.1 via `sdkmanager`. |
| Release build is slow on small machines | Dexing is the bottleneck. Run `./gradlew :app:mergeExtDexRelease` once on its own, then `assembleRelease`. |
| Behind a proxy | Add `systemProp.http(s).proxyHost/Port/User/Password` to `~/.gradle/gradle.properties`, not to the project file. |
| Camera test shows a black preview | Another app holds the camera, or the device is a multi-camera device where the first enumerated id is a logical camera — try the other lens. |

## CI sketch

```yaml
- run: ./gradlew testDebugUnitTest lint
- run: ./gradlew assembleRelease
  env:
    KEYSTORE_BASE64: ${{ secrets.KEYSTORE_BASE64 }}
```

Decode the keystore into a file, write `keystore.properties` pointing at it, and the signed APK lands in
`app/build/outputs/apk/release/`.
