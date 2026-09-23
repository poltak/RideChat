# Android offline ride intercom: implementation plan

Date: 22 September 2026. Status: proposed implementation plan; no app has been built or tested.

Build an Android app that lets a small group of motorcycle riders talk through their headsets without internet service. Riders join before departure, start a ride session, and lock their phones. The app maintains the session and reports connection changes through short audio cues.

The project folder was empty when inspected and is not yet a Git repository. This document is the first project file. All performance values below are proposed acceptance targets, not measured results.

## 1. Product decisions and assumptions

| Item | First-release decision |
| --- | --- |
| Platform | Native Android; Kotlin and Jetpack Compose |
| Supported OS | Android 12 / API 31 and later, subject to the tested-device list |
| Build target | Compile and target API 37; verify final SDK and stable toolchain availability at project setup [S1] |
| Group size | Two to four riders, including the host |
| Speech | Open conversation with local microphone mute; all four may speak at once |
| Connection | No internet or router required; Bluetooth and direct Wi-Fi are allowed |
| Headsets | Each rider uses a tested wired or Bluetooth headset with a microphone |
| Screen | Locked after joining and starting the session |
| Topology | One phone is the host and forwards audio to the other members |
| Account | None; a local display name is enough |
| Storage | Preferences only; no voice recordings or conversation history |
| Initial distribution | A signed test APK, installed before the ride |

Open conversation is the working default because it does not require an unconfirmed headset button. Exact phone and headset models remain open. Initial field tests target four riders within 30 metres of the host in open conditions. This is a test requirement, not a range claim. Also measure performance at 10, 50, and 100 metres to establish the actual limit.

The app will not promise Bluetooth-only operation, a fixed range, operation after force-stop, or automatic host replacement. iPhone support, more than four riders, multi-hop forwarding, voice activation, recording, location sharing, music mixing, and universal headset-button support are outside the first release.

## 2. User flow

1. On first use, enter a display name and grant the permissions needed for the selected OS version.
2. Connect the headset through Android settings. Run a short microphone and output test while stopped. Keep test audio in memory and discard it after playback.
3. One rider selects **Create group**. Other riders select **Join group** and choose the host.
4. Each joining rider and the host compare and confirm the connection code. A group name is not proof of identity.
5. The lobby shows the member count, headset readiness, mute state, and connection state. The limit is four members.
6. Each rider selects **Start ride** on their own phone while the app is visible. Starting the session explicitly enables microphone capture and background operation.
7. The rider locks the phone. The app continues to send and receive voice. Short, distinct cues report disconnection and recovery.
8. A persistent notification shows session and mute state. It provides mute and end-session actions. Test action availability on each supported lock-screen configuration; the core conversation must not depend on touching these actions.
9. A member can leave independently. When the host ends the group, all members hear a session-ended cue and stop the session.

Keep the interface to five views: setup, create/join, lobby, active session, and settings/diagnostics. Use large controls, text status in addition to colour, TalkBack labels, and clear permission recovery instructions. Do not require screen interaction during normal riding.

## 3. Connection choice and feasibility gate

### Primary candidate: Google Nearby Connections

Use `ConnectionsClient` with `Strategy.P2P_STAR`. The host advertises and accepts up to three members. Members discover and connect only to the host. Use a fixed application service ID, a random ride ID, and short public discovery metadata. Do not advertise secrets or a hardware identifier.

Nearby supplies offline discovery and encrypted links. It can select Bluetooth, BLE, and Wi-Fi internally; do not label the connection as a specific radio unless the SDK exposes reliable evidence. The Android implementation requires Google Play services. Check availability at startup and show an actionable error if it is absent or requires an update. Any update must happen before an offline ride. [S2, S3]

Use BYTES payloads for small control messages and framed STREAM payloads for audio. A stream is not a datagram channel: writes may block, and reliable delivery may retain old audio. STREAM support alone does not prove acceptable live-voice behaviour. [S4]

### Required experiment before the full interface

Build a small test app with the final foreground service and headset routing. Start with two phones, then use four. Send timestamped synthetic frames before live microphone input. Run with mobile data off, no access point, and Wi-Fi and Bluetooth enabled. Test first launch and first group creation while offline after installation.

Measure delay, queue growth, disconnect detection, reconnect time, power use, and audio routing with screens locked. Test a weak link and simultaneous headset traffic. Test a client-to-client path through the host, not only host-to-client traffic.

Compare one framed outgoing stream per peer with small BYTES audio payloads only if the stream path fails. Apply the same acceptance tests. Do not assume BYTES removes reliable-delivery delay.

**Decision:** keep Nearby only if the two-phone and four-phone tests meet section 11. Save the device matrix and measurements in `docs/feasibility.md` before continuing to the full product.

### Contingency: Android Wi-Fi Direct

If Nearby fails the delay or locked-screen recovery gate, test `WifiP2pManager` with one group owner and application sockets. This supports communication without internet. It needs separate pairing, group recovery, socket binding, and security work. [S5]

Use an authenticated standard media protocol, such as DTLS-SRTP through a maintained implementation, for any UDP voice path. Do not ship raw unencrypted UDP or invent a new encryption protocol. Select and obtain approval for that dependency before adoption. This is a replacement transport investigation, not an automatic runtime fallback or a second transport to build in parallel.

If neither transport meets the field range target, stop expansion and report the measured limit. Compression or an interface change cannot fix an inadequate radio link.

## 4. Application structure

Use a small Gradle project with an Android app, a pure Kotlin core, and a native codec module. Keep Android APIs out of the core so that connection failure and timing tests can run without phones.

```text
app/
  ui/                 Compose screens and view models
  service/            RideService, notification actions, resource lifecycle
  transport/          Nearby adapter and discovery callbacks
  audio/              Capture, playback, routing, focus, audio effects
  storage/            Preferences and local diagnostic export
core/
  session/            Session state, membership, reconnect rules
  protocol/           Control messages, framing, validation
  audio/              Frame timing, jitter buffer, mixing policy
  transport/          Interface and deterministic fake transport
codec-opus/
  src/main/cpp/       Small JNI bridge to pinned upstream libopus
docs/
  feasibility.md
  protocol-v1.md
  device-test-matrix.md
  field-test-report.md
```

These are proposed implementation paths; only this plan exists now.

`RideService` owns the active session. Activities and view models display its state and send commands. Rotation, navigation, or activity destruction must not own or stop the audio engine. Use `StateFlow` for view state and a single serialized session event loop for state changes. Put blocking transport I/O and audio work on dedicated workers, not the main thread.

Use explicit interfaces for `PeerTransport`, `AudioCapture`, `AudioOutput`, `VoiceCodec`, `AudioRouteController`, and `MonotonicClock`. Inject them through a small application container. Do not add a dependency-injection framework for the initial size.

State model:

```text
Idle → Preparing → Lobby → Active → Ending → Idle
                    ↑        ↕
                    └── Reconnecting

Active may also have AudioInterrupted or HeadsetMissing status.
Microphone mute is a separate local state and survives reconnects.
```

Network state and audio state must be separate. A connected phone with a disconnected headset is not ready to transmit.

## 5. Audio pipeline

Use Android `AudioRecord` and streaming `AudioTrack`, with communication audio attributes and `AudioManager.MODE_IN_COMMUNICATION`. Route input and output together using `setCommunicationDevice()` and verify the selected route through callbacks. This API supports HFP and LE Audio routes; do not implement the path with deprecated SCO methods. [S6]

Use Opus through a small native bridge. Start with mono, 20 ms frames, and a 24 kbit/s target per speaker. Use a 48 kHz codec clock, 960 samples per frame, and support explicit conversion when the capture route supplies a different sample rate. Do not assume a Bluetooth microphone supplies wideband or fullband audio. Choose a maintained resampler or a validated platform conversion path at the audio feasibility step; do not add ad hoc sample dropping. [S7]

Proposed pipeline:

```text
Headset input → AudioRecord → rate conversion if needed → Opus encoder
      → bounded frame queue → transport → host relay → remote transport
      → per-speaker jitter buffer → Opus decoder → local mix/limiter
      → AudioTrack → headset output
```

Start the jitter buffer at 60 ms and allow adjustment between 40 and 120 ms. Limit the application's outgoing audio queue to 100 ms. Drop expired unsent frames instead of allowing the queue to grow. Use Opus loss concealment for short gaps; emit silence after a bounded concealment period. Add discontinuous transmission only after continuous-frame operation is stable. Treat in-band FEC as a later measured option, since reliable transport may stall rather than expose packet loss.

Each recipient decodes one stream per remote speaker and mixes locally, with headroom and a limiter. The host forwards encoded frames without decoding and re-encoding them for relay. It still decodes remote audio for its own listener. Never send a speaker's frame back to that same speaker. This avoids an extra codec generation and self-audio loops.

For four continuously speaking users at 24 kbit/s each, host media ingress is about 72 kbit/s and host egress about 216 kbit/s, before framing and transport overhead. Client-to-client voice uses two wireless links. This is a sizing estimate, not proof of throughput or delay.

Use `VOICE_COMMUNICATION` capture and test available platform noise suppression and echo cancellation. Check effect availability and compare results on each supported route. Avoid blindly stacking effects on headset processing. Software processing cannot recover speech that wind has already overwhelmed at the microphone.

Local mute stops microphone capture and drops pending local frames. Incoming voice continues. Recreate capture on explicit unmute within the already running ride service, and verify this path while locked. Honour the Android microphone privacy switch and capture-silencing events.

## 6. Wire protocol and host relay

Document protocol version 1 before implementing the relay. Use bounded binary audio framing and a small, versioned control schema. Use typed Kotlin models; no Java object serialization.

An audio envelope contains: protocol version, ride ID, sender ID, connection generation, stream generation, sequence number, media sample timestamp, payload length, and Opus bytes. Define byte order, field sizes, and maximums in the protocol document. Reject frames above a 2 KiB application limit before allocating their declared size; normal speech frames should be much smaller. Validate codec parameters and decoded sample counts as well.

Control messages include `HELLO`, `WELCOME`, `MEMBERS`, `READY`, `MUTE_STATE`, `PING`, `PONG`, `STREAM_START`, `STREAM_RESET`, `LEAVE`, and `SESSION_END`. Set an 8 KiB control-message limit, bounded names, and per-peer rate limits. Negotiate one shared codec profile; reject incompatible versions clearly.

Control and media payloads can arrive in different orders. Do not use callback order as an implicit handshake. Require the matching authenticated ride and stream generation before playing audio; discard or briefly bound early media. Tag asynchronous callbacks with a connection generation so old callbacks cannot change a new session. [S4]

The host assigns sender IDs and validates the sender against its authenticated connection. A client cannot nominate another rider as the source. Only the host updates the roster or ends the whole group. Each peer has its own output queue and writer so a slow peer cannot block the group. Do not relay control messages blindly.

Phone clocks are not synchronized. Use media sample timestamps for playback progression and a monotonic ping/echo clock estimate for age monitoring. Store the estimate's uncertainty. Do not subtract timestamps from different phones without an offset estimate. Reset timing on reconnection and route changes.

Bound queues at every application stage. Measure SDK and OS buffering separately. If a writer blocks or observed playback age exceeds 300 ms, stop the affected stream, discard queued media, and establish a fresh stream generation. Test whether cancellation actually removes old data; if it cannot, disconnect that peer. Repeated stalls fail the transport gate. Do not claim that a 100 ms app queue alone guarantees low end-to-end delay.

## 7. Joining, authentication, and recovery

For first admission, show Nearby authentication digits on both phones and require explicit confirmation before membership or audio. Use the SDK's encrypted link. Host access to group audio is part of the trust model; do not describe this as encryption that hides audio from the host. [S2, S8]

After verified admission, generate a random 256-bit resume secret for that host/member pair and transfer it only over the confirmed link. Keep it only in memory for the current ride. Use cryptographically secure randomness. Never log secrets, raw authentication tokens, or audio. On member removal, delete its secret.

For a reconnect while locked, permit a candidate connection only into a restricted authentication state. Before sending or accepting media, require mutual HMAC-SHA256 proofs using the existing resume secret. Bind each proof to protocol version, ride ID, both fresh random nonces, both member IDs, the sender's role, and the new Nearby raw authentication token. Use a canonical length-delimited encoding and constant-time comparison. The raw token binds the proof to this connection; names and endpoint IDs do not. Android cryptographic primitives supply HMAC; no new encryption scheme is needed. Specify test vectors and obtain a focused review before enabling automatic resume. [S9]

No stored secret means no unattended rejoin. New members, app process restarts, and host restarts require a new visible join flow. Unknown peers must not get audio or the roster through the resume path. Apply handshake timeouts and rate limits to untrusted candidates.

Keep a heartbeat every second. After three missed replies, mark the peer degraded or disconnected and play one cue. Actual transport callbacks can detect failure sooner. Retry with approximately 1, 2, 4, then 8-second delays plus jitter, capped at 8 seconds. On SDK callbacks and radio restoration, retry within those limits. Stop continuous scanning after a bounded active attempt and use duty-cycled retry while the ride continues. Stop all retries when the user ends the session.

The host stops open admission when the ride begins. Enable bounded discovery/advertising windows when an admitted rider is lost, then require the resume proof. Test the effect of scanning on remaining riders' audio. Reconnection has a new generation and starts with empty buffers. Preserve local mute.

If a member disconnects, other riders continue. If the host disconnects, clients announce group loss and seek the same host. There is no host election in version 1. Do not silently form two groups or promise relay through another rider.

## 8. Locked-screen service, permissions, and interruptions

Start `RideService` from the visible Start ride action after permission checks. Promote it immediately with the required notification and foreground-service types. Use `microphone` and `connectedDevice`, with their matching manifest permissions and runtime prerequisites. Do not use `phoneCall` without implementing its required Telecom integration. Android restricts starting microphone services from the background. [S10]

| Area | Required handling |
| --- | --- |
| Microphone | Declare and request `RECORD_AUDIO`; handle denial, revocation, privacy mute, and silenced capture |
| Bluetooth | Declare/request scan, advertise, and connect permissions on API 31+ as required by Nearby |
| Wi-Fi discovery | Request `NEARBY_WIFI_DEVICES` on API 33+; test legacy location prerequisites on API 31/32 with the selected Nearby version |
| Local network | For target/API 37 operation, declare/request `ACCESS_LOCAL_NETWORK` where the transport accesses the LAN [S11] |
| Notifications | Request `POST_NOTIFICATIONS` on API 33+ and explain session controls; denial is not itself a microphone permission denial |
| Service | Declare base and type-specific foreground-service permissions; use an unexported service and explicit, immutable notification intents |
| Networking | Declare required network/Wi-Fi permissions from the pinned transport SDK and inspect the merged manifest; an `INTERNET` socket permission does not make internet service a product requirement |
| Files/location | No storage access, contacts, GPS tracking, or background location request for this feature |

Build a tested permission matrix, rather than copying the documentation's manifest example verbatim. The current Nearby sample and platform API boundaries need reconciliation, especially API 32/33. Permission denial must produce a recoverable setup state instead of repeated dialogs. [S3]

Request communication audio focus after the service starts. Handle failure instead of playing anyway. Target-35+ apps must be visible or running a foreground service to request focus. On focus loss or a call interruption, stop transmission, flush audio, and show an interrupted state. On focus return, verify the headset route before resuming the previous mute state and play a short cue. Do not take call audio or assume simultaneous microphone access. [S12]

On headset loss, immediately stop capture and playback. Do not switch a riding session to the phone speaker or pocket microphone. Keep the group connection and report the local route failure. When a known headset returns, verify both directions before restoring the previous mute state and giving a cue.

Use a partial wake lock only if device measurements show that the active audio/service path needs it. Scope it to the active session, renew a bounded timeout if needed, and release it on every exit. Do not request a blanket battery-optimization exemption as the default setup flow. Record OEM-specific failures and document any narrowly required setting.

Use `START_NOT_STICKY`. A force-stop, device restart, process death, or Android's user stop ends the local ride; there is no hidden microphone restart. Detect a normal activity swipe separately from these cases and test the intended continued service operation. End session must close streams, stop discovery, release audio and effects, clear the communication device, abandon focus, release locks, and remove the service notification.

## 9. Controls and future push-to-talk

Version 1 requires no hardware button for speech. Offer mute and leave in the app and notification. A headset-button mute shortcut is optional and only shown for a tested control path; generic call, media, and assistant buttons are not interchangeable.

Do not add a MediaSession solely on the assumption that it captures all headset buttons. Run a bounded compatibility experiment before choosing MediaSession, Telecom, or a supported BLE/HID remote. Test with the screen locked and other media apps installed.

If push-to-talk becomes a release requirement, add a host-controlled speaking grant. Only the current grant holder may send voice. Add press/release events, a short renewable grant lease, release on disconnect, and a timeout for lost button-up events. Test contention and long presses. On-screen push-to-talk alone does not meet the riding use case. This work follows a hardware choice; it is not included in the default open-conversation estimate.

## 10. Dependencies, privacy, and build setup

Proposed production components: Kotlin, AndroidX Core/Lifecycle, Compose/Material, coroutines, DataStore Preferences, Google Play services Nearby, and upstream libopus through JNI. Use Gradle Kotlin DSL, a checked-in wrapper, a version catalogue, and pinned versions. Use a stable JDK/AGP/Kotlin combination supported by the selected SDK. No JavaScript runtime or npm packages are needed for this native app.

Before adding production dependencies, present the exact package names, versions, purposes, licenses, and any native binaries for confirmation, as required by the user's working agreement. Planning these components does not install or approve them. Treat a resampler, remote-control library, or alternative media transport as an additional dependency decision.

Compile libopus from a pinned upstream release with a minimal JNI surface. Package arm64-v8a for initial devices and x86_64 for emulator tests. Confirm 32-bit device needs before adding armeabi-v7a. Use an NDK/build configuration that supports 16 KiB pages; inspect every packaged native library and test an appropriate emulator/device. [S13]

Store only the display name and non-sensitive preferences in DataStore. Session credentials and audio stay in memory. Diagnostic exports are user-initiated and contain version numbers, anonymous session-local member IDs, state transitions, queue lengths, timing statistics, route types, and battery/thermal measurements. Exclude audio, secrets, hardware addresses, and location. Use a bounded local log and a FileProvider export; no background upload.

No app analytics or crash-upload SDK is proposed. Google documents Nearby SDK usage diagnostics controlled through device settings. Review that SDK behaviour and reflect it in the privacy notice; do not promise that the whole device or Play services sends no telemetry when internet returns. Offline operation is the claim. [S2]

Create a Git repository at implementation setup if requested as part of that work. Keep signing keys, local SDK paths, and machine settings outside version control. Generate a test APK first. Prepare an AAB and Play declarations only when distribution is requested. Publication and signing-credential setup are separate from local validation.

## 11. Validation and acceptance targets

The device test matrix must name phone model, Android/API version, app build, Google Play services version, headset/firmware, audio route, phone placement, and radio conditions. Emulators can validate app behaviour but cannot prove the radio, headset, or locked-screen riding requirements.

| Check | Initial acceptance target |
| --- | --- |
| Offline setup | Install beforehand; cold launch, discover, authenticate, and start with no mobile data or access point |
| Join time | At least 19 of 20 attempts join within 15 seconds after discovery begins, excluding human confirmation time |
| Voice delay | Acoustic one-way delay: median at most 200 ms and p95 at most 300 ms on a healthy link, including client-to-client relay |
| Group | Four members; all speaker pairs work and overlapping speech remains usable |
| Stability | 60 minutes locked at close range, with no crash, service termination, or unexpected disconnect |
| Audio gaps | No gap above one second on the healthy-link baseline; report total missing-audio time, not just audible impressions |
| Recovery | At least 9 of 10 brief range-loss trials restore the admitted member within 10 seconds of returning to coverage, while still locked |
| Stale audio | No replay of pre-disconnect speech; repeated delay above 300 ms triggers stream recovery or fails the transport gate |
| Range | Four users within 30 metres of the host in the stated open-condition test; report degradation at 50 and 100 metres |
| Battery | Provisional goal: at most 15 percentage points per hour on each named reference phone, screen off; record host/client separately |
| Thermal | No sustained severe thermal state or thermal-related audio failure in the reference test conditions |
| Stop | Recording, playback, discovery, and service resources stop within two seconds of End session |

The range and battery goals need user/device confirmation. If they fail, report the data and revise scope explicitly. Do not remove failing devices from the report to imply broader support.

Measure acoustic delay with a common external recording clock or a calibrated loopback rig using test signals. Compare source and reproduced waveforms. Network RTT is not mouth-to-ear delay, and timestamps from separate phone clocks are not enough. Record p50, p95, sample count, route, and uncertainty.

Automated tests must cover:

- State transitions with a fake transport and virtual clock: join, cancellation, stale callbacks, member loss, host loss, and teardown.
- Frame parsing: partial reads, multiple frames per read, invalid lengths, truncation, unknown versions, bad codec data, and bounded allocations.
- Resume authentication: wrong secret, fresh-nonce replay, wrong ride/role, different channel token, expired membership, and handshake timeouts.
- Relay behaviour: no self-echo, no sender spoofing, no cross-session delivery, bounded queues, and isolation of a slow peer.
- Audio timing: duplicates, out-of-order input, drops, jitter changes, drift, route changes, resets, and mute persistence.
- Codec: encode/decode through JNI, allowed frame sizes, repeated lifecycle changes, and malformed decoder input.
- UI and service integration: permission denial/revocation, rotation, notification commands, process recreation, and cleanup.

Device tests must also cover API 31/32, 33, 34, 35/36, and 37 permission/lifecycle boundaries, with at least two phone vendors and more than one headset type. Test notification denial, Bluetooth/Wi-Fi toggles, battery saver, idle/Doze, headset power loss, an incoming call, navigation prompts, microphone privacy mute, host departure, and repeated reconnects. Mark combinations not tested as unverified.

Run stationary tests first, then tests with phones in actual pockets and helmets fitted, then a controlled riding trial. Include engine idle and riding noise. Keep device operation and measurement with a stopped tester or passenger. A field pass must include intelligible speech with the intended microphones, not only a clean desk recording.

## 12. Implementation sequence

Effort ranges assume one experienced Android developer, access to the test phones/headsets, and no transport replacement. They are estimates, not commitments.

| Phase | Work and deliverable | Exit check | Estimate |
| --- | --- | --- | --- |
| 0. Setup | Confirm defaults and device list; review proposed dependencies; create Gradle skeleton, core test module, codec build and service shell | Build and install on two devices; JNI loads; CI runs unit tests | 1–2 days |
| 1. Feasibility | Nearby star prototype, locked service, real headset routes, synthetic transport metrics, two-way voice, four-phone relay | `docs/feasibility.md` records section 11 results and transport decision | 3–5 days |
| 2. Sessions | Versioned protocol, verified admission, membership, secure resume, retries, host/client state machine | Fault and authentication tests pass; reconnect works while locked | 3–5 days |
| 3. Audio | Bounded capture/output, codec, jitter control, local mixing, mute, focus, route loss, stale-stream recovery | Acoustic delay and four-speaker tests pass on reference devices | 4–6 days |
| 4. Interface | Setup, lobby, active session, accessible state displays, notification, audio cues, diagnostics export | Full flow works from offline launch through locked ride and exit | 2–4 days |
| 5. Field and release candidate | OEM checks, interruption tests, battery/thermal soak, controlled range/noise trials, native packaging | Signed APK plus compatibility and field reports; all critical defects resolved | 4–7 days |

Total: approximately 17–29 working days, or 4–6 working weeks, plus hardware scheduling and any failed feasibility gate. Phase 1 contains minimal versions of later components for measurement; later phases harden those components rather than replace the prototype wholesale.

Keep work in reviewable changes: scaffold; service lifecycle; transport adapter; protocol and authentication; codec bridge; audio engine; host relay; reconnect; interface; device fixes. Add behaviour tests with each feature. Run `./gradlew test lint assembleDebug` in CI, instrumented tests on suitable emulators, and native packaging checks. Run physical-device checks before declaring radio or audio work complete.

Every phase ends with a short record: changes, tests passed, measured limits, and remaining failures. Do not proceed from feasibility to feature expansion with unexplained stalls or failed locked-screen recovery.

## 13. Release decision and open inputs

Release a test build only when offline startup, locked conversation, authenticated recovery, headset handling, and reliable teardown pass on the declared device matrix. Publish the tested member limit, host dependence, OS/headset compatibility, and measured range conditions. Do not present this as an emergency communication system or claim dedicated-intercom range without evidence.

Open inputs, with defaults so planning can proceed:

| Input | Working default | When it matters |
| --- | --- | --- |
| Rider count | Four total | Before group feasibility testing |
| Conversation mode | Open conversation plus mute | Before choosing physical controls |
| Phones/headsets | To be supplied; select at least two vendors | Before Phase 1 can provide meaningful proof |
| Useful spacing | 30 metres to host as first target | Before accepting field results |
| Google Play services | Required for the initial candidate | Before approving Nearby dependency |
| Long rides | One-hour acceptance soak, then a two-hour extended test | Before release candidate |
| App name/application ID | Temporary local name only | Before signing and distribution |

The first implementation action is the dependency review and native project scaffold. The first product decision after that is the measured transport pass/fail result.

## 14. Official references

Sources checked on 22 September 2026. Recheck SDK and permission details when implementation starts.

- [S1: Android 17 SDK setup](https://developer.android.com/about/versions/17/setup-sdk)
- [S2: Nearby Connections overview and SDK diagnostics](https://developers.google.com/nearby/connections/overview)
- [S3: Nearby Android setup and permissions](https://developers.google.com/nearby/connections/android/get-started)
- [S4: Nearby Android data payloads](https://developers.google.com/nearby/connections/android/exchange-data)
- [S5: Android Wi-Fi Direct](https://developer.android.com/develop/connectivity/wifi/wifi-direct)
- [S6: Android communication audio routing](https://developer.android.com/develop/connectivity/bluetooth/ble-audio/audio-manager)
- [S7: Opus codec](https://opus-codec.org/)
- [S8: Nearby connection authentication](https://developers.google.com/nearby/connections/android/manage-connections)
- [S9: Nearby ConnectionInfo and raw authentication token](https://developers.google.com/android/reference/com/google/android/gms/nearby/connection/ConnectionInfo)
- [S10: Android foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [S11: Android local network permission](https://developer.android.com/privacy-and-security/local-network-permission)
- [S12: Android audio focus](https://developer.android.com/media/optimize/audio-focus)
- [S13: Android native libraries and 16 KiB pages](https://developer.android.com/guide/practices/page-sizes)
- [S14: Nearby connection strategies](https://developers.google.com/nearby/connections/strategies)
- [S15: Android foreground service start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
