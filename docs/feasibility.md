# Feasibility record

Status: **REVIEWED PROTOTYPE; PENDING PHYSICAL TESTS** (23 September 2026).

The project has a JVM-tested protocol core, an Android audio engine that only accepts communication headset routes, a native Opus module, and an Android application shell. The current build target is API 36 because that is the installed SDK. The plan originally described API 37; that remains a later toolchain decision and is not claimed by this build.

## Verified local evidence

On 23 September 2026, the following checks passed with JDK 17 and Android SDK/API 36:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17 \
ANDROID_HOME=/Users/lamhucminh/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/lamhucminh/Library/Android/sdk \
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon

JAVA_HOME=/opt/homebrew/opt/openjdk@17 \
ANDROID_HOME=/Users/lamhucminh/Library/Android/sdk \
scripts/test-codec-host.sh
```

The JVM run passed 29 JUnit tests: 15 in `core` and 14 in `app`. Android lint reported 0 errors and 22 warnings. The warnings are mainly newer dependency suggestions plus API-analysis and style warnings. The final ABI-filtered debug APK contains only `arm64-v8a` and `x86_64`; it passed signature verification, 16 KiB ZIP alignment, and Opus ELF alignment checks. The host JNI smoke test reported `PASS OpusHostSmokeTest`.

No supported phone, headset, radio condition, locked-screen session, or riding trial has been tested in this workspace. No ADB device or emulator is available here. Therefore the following product evidence is **UNVERIFIED**:

- offline first launch, discovery, authentication, and ride start;
- Bluetooth and direct Wi-Fi selection by Nearby;
- phone-to-headset input and output while the screen is locked;
- two-way and four-member relay audio;
- one-way acoustic delay, missing-audio time, reconnect time, range, battery, and thermal behaviour;
- permission behavior on each API 31/32, 33, 34, 35/36 boundary;
- headset loss, incoming calls, navigation prompts, Doze, battery saver, and microphone privacy mute.

Audio cues and a dedicated headset self-test are not implemented. The status card only reports the route and audio state exposed by the engine.

The app queue is 100 ms and the local pipe is 256 bytes. These limits apply to app-side queues only; they do not bound buffering inside the Nearby SDK.

The intended first test uses two Android phones, then four, with mobile data disabled and no access point. Each phone uses a named wired or Bluetooth communication headset. The tester records join time, p50/p95 mouth-to-ear delay, audio gaps, reconnection, battery percentage, thermal state, route, OS/API level, Google Play services version, headset firmware, phone placement, and radio conditions. The four-member target is within 30 metres of the host in open conditions; this is a test target, not a range claim.

The current scope differs from the original plan in two deliberate ways: the build uses API 36 while API 37 is unavailable locally, and the UI is a small five-state Compose flow instead of a larger diagnostics/export surface. Neither change is evidence that the transport or locked-screen requirements pass. This record describes a reviewed prototype; physical ride behaviour remains unverified.
