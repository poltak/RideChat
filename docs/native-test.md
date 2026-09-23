# Native codec test

Run the desktop JNI smoke test from the project root:

```sh
JAVA_HOME=/path/to/jdk-17 \
ANDROID_HOME=/path/to/android-sdk \
  scripts/test-codec-host.sh
```

The script downloads Opus 1.5.2, verifies the SHA-256 checksum used by the
Android CMake build, compiles `opus_jni.cpp` with the desktop JDK JNI headers,
compiles the existing Kotlin wrappers and smoke test, and runs the test with a
desktop JVM. The temporary native build and downloaded archive are removed when
the script exits.

The smoke test covers:

- 48 kHz mono 20 ms encode/decode with 960 decoded samples;
- Opus packet loss concealment with 960 decoded samples;
- invalid sample counts and unsupported configurations;
- malformed and non-20 ms packets;
- idempotent close and use-after-close failures; and
- repeated encoder/decoder construction and destruction.

This is a host JNI test. It does not prove Android audio routing, ABI packaging,
locked-screen service behavior, or physical headset behavior. The Opus CMake
configuration can print its upstream package-version warning and the host build
can print a CPU capability warning; both are harmless when the script reports
`PASS OpusHostSmokeTest`.
