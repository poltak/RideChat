# RideChat

RideChat is a reviewed Android prototype for short-range, offline voice chat between riders. It uses Google Nearby Connections for phone-to-phone discovery and transport, and sends voice through a communication headset. The first app shape is a host-led group of up to four riders with an open microphone and local mute.

The phone must be prepared while the app is visible. Riders join and confirm the Nearby authentication code before starting a ride. The ride service then uses a microphone foreground service, so the screen can be locked after **Start ride**. The app stops audio when the communication headset is missing; it does not switch to the phone speaker or microphone.

## Build

Use JDK 17 and the installed Android SDK 36. From the project directory:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17 \
ANDROID_HOME=/Users/lamhucminh/Library/Android/sdk \
./gradlew :app:assembleDebug
```

Install the generated debug APK on phones before leaving coverage. Google Play services with Nearby Connections must be available and up to date before the ride. Internet service is not part of the ride path, but the initial build and dependency setup require normal Android development access.

The debug APK contains native Opus libraries for `arm64-v8a` and `x86_64`. The corresponding license is included in the APK assets as [Opus COPYING](app/src/main/assets/third-party/opus-COPYING).

The first launch asks for microphone, Nearby Bluetooth/Wi-Fi, and notification permissions as required by the Android version. API 31 and 32 also use the legacy coarse and fine location permissions required by the selected Nearby SDK. A denied notification permission does not block the microphone session; a denied microphone or Nearby permission does.

## Verified local checks

The following checks passed on 23 September 2026 with JDK 17 and Android SDK/API 36:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17 \
ANDROID_HOME=/Users/lamhucminh/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/lamhucminh/Library/Android/sdk \
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon

JAVA_HOME=/opt/homebrew/opt/openjdk@17 \
ANDROID_HOME=/Users/lamhucminh/Library/Android/sdk \
scripts/test-codec-host.sh
```

The JVM run passed 29 JUnit tests: 15 in `core` and 14 in `app`. Android lint reported 0 errors and 22 warnings, mainly newer dependency suggestions plus API-analysis and style warnings. The final ABI-filtered debug APK contains only `arm64-v8a` and `x86_64`; it passed signature verification, 16 KiB ZIP alignment, and Opus ELF alignment checks. The host JNI smoke test reported `PASS OpusHostSmokeTest`.

## Use

1. On the host phone, enter a rider name and select **Create group**.
2. On each other phone, enter a rider name, select **Find group**, then select **Join** for the host.
3. The host and each joining rider confirm the same Nearby code.
4. Every rider selects **Start ride** with a communication headset connected. The host-led group supports up to four riders.
5. Lock the screen after the ride is active. Use the headset, or the notification **Mute** and **End ride** actions.

Nearby chooses Bluetooth and direct Wi-Fi radio paths. The app does not require an Internet connection during a ride.

## Current limits

This is a reviewed prototype. The app currently targets API 36, supports Android API 31 and later, uses one host, and caps the group at four members. Range, delay, battery use, headset compatibility, locked-screen recovery, and four-phone stability are **UNVERIFIED** until physical-device tests are run. Audio cues and a dedicated headset self-test are not implemented. The app queue is 100 ms and the local pipe is 256 bytes; these limits do not bound buffering inside the Nearby SDK. There is no host election, multi-hop relay, recording, account, location tracking, iPhone build, or emergency-communication guarantee.

See [the implementation plan](ANDROID_IMPLEMENTATION_PLAN.md), [protocol v1](docs/protocol-v1.md), and [the feasibility record](docs/feasibility.md) for the intended gates and current evidence.
