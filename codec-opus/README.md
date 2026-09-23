# Native Opus codec

`OpusEncoder` and `OpusDecoder` expose a small JNI bridge around upstream
libopus 1.5.2. The native build downloads the source archive into Gradle's
ignored CMake build directory and verifies its SHA-256 before extraction.

The default profile is mono, 48 kHz, 20 ms frames (960 PCM samples), and a
24 kbit/s encoder target. Other Opus sample rates and mono/stereo channels are
accepted when the caller supplies a matching 20 ms frame. The decoder accepts
`null` as a packet-loss-concealment request.
