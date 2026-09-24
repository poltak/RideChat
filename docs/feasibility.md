# Feasibility record

Status: **REVIEWED PROTOTYPE; ONE-PHONE AUDIO CHECK PASSED; GROUP TESTS PENDING** (24 September 2026).

The project has a JVM-tested protocol core, an Android audio engine that accepts communication headsets or the built-in phone mic and speaker, a native Opus module, and an Android application shell. The current build target is API 36 because that is the installed SDK. The plan originally described API 37; that remains a later toolchain decision and is not claimed by this build.

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

On 24 September 2026, the updated APK was installed on a Samsung SM-A566B running API 36. A one-phone host session started with **Auto** and no headset. Android reported active `VOICE_COMMUNICATION` microphone capture, speaker output, and acoustic echo cancellation. Selecting **Headset** with no headset connected paused audio. Selecting **Phone** resumed active microphone capture. The test session was ended. This checks local route selection and capture; it does not test remote sound or echo quality.

The following product evidence is **UNVERIFIED**:

- offline first launch, discovery, authentication, and a ride with two or more phones;
- Bluetooth and direct Wi-Fi selection by Nearby;
- phone-to-headset input and output while the screen is locked;
- two-phone speaker echo and acoustic echo cancellation quality;
- two-way and four-member relay audio;
- one-way acoustic delay, missing-audio time, reconnect time, range, battery, and thermal behaviour;
- permission behavior on each API 31/32, 33, 34, 35/36 boundary;
- headset loss, incoming calls, navigation prompts, Doze, battery saver, and microphone privacy mute.

Audio cues and a dedicated headset self-test are not implemented. The status card only reports the route and audio state exposed by the engine.

The app queue is 100 ms and the local pipe is 256 bytes. These limits apply to app-side queues only; they do not bound buffering inside the Nearby SDK.

The next test uses two Android phones, then four, with mobile data disabled and no access point. Test phone mic and speaker while stationary, then use a named wired or Bluetooth communication headset for riding checks. The tester records join time, p50/p95 mouth-to-ear delay, audio gaps, reconnection, battery percentage, thermal state, route, OS/API level, Google Play services version, headset firmware, phone placement, and radio conditions. The four-member target is within 30 metres of the host in open conditions; this is a test target, not a range claim.

The current scope differs from the original plan in two deliberate ways: the build uses API 36 while API 37 is unavailable locally, and the UI is a small five-state Compose flow instead of a larger diagnostics/export surface. Neither change is evidence that the transport or locked-screen requirements pass. This record describes a reviewed prototype; physical ride behaviour remains unverified.
