package com.ridechat.codec

/**
 * Small executable test for the JNI wrapper. It does not use Android or JUnit so
 * that scripts/test-codec-host.sh can run it with a desktop JVM and the native
 * library built from the pinned Opus source.
 */
object OpusHostSmokeTest {
    private const val SAMPLE_RATE = 48_000
    private const val CHANNELS = 1
    private const val FRAME_SAMPLES = 960

    @JvmStatic
    fun main(args: Array<String>) {
        roundTripProducesOneTwentyMillisecondFrame()
        packetLossConcealmentProducesOneFrame()
        invalidInputIsRejected()
        malformedAndNonTwentyMillisecondPacketsAreRejected()
        closeIsIdempotentAndUseAfterCloseFails()
        repeatedInstancesReleaseNativeState()
        println("PASS OpusHostSmokeTest")
    }

    private fun roundTripProducesOneTwentyMillisecondFrame() {
        val encoder = OpusEncoder(SAMPLE_RATE, CHANNELS, 24_000)
        val decoder = OpusDecoder(SAMPLE_RATE, CHANNELS)
        try {
            val packet = encoder.encode(testFrame())
            check(packet.isNotEmpty()) { "encoder returned an empty packet" }
            val decoded = decoder.decode(packet)
            check(decoded.size == FRAME_SAMPLES) {
                "round-trip decoded ${decoded.size} samples, expected $FRAME_SAMPLES"
            }
            check(decoded.any { it != 0.toShort() }) { "round-trip output was silent" }
        } finally {
            decoder.close()
            encoder.close()
        }
    }

    private fun packetLossConcealmentProducesOneFrame() {
        val encoder = OpusEncoder(SAMPLE_RATE, CHANNELS, 24_000)
        val decoder = OpusDecoder(SAMPLE_RATE, CHANNELS)
        try {
            decoder.decode(encoder.encode(testFrame()))
            val concealed = decoder.decode(null)
            check(concealed.size == FRAME_SAMPLES) {
                "PLC returned ${concealed.size} samples, expected $FRAME_SAMPLES"
            }
        } finally {
            decoder.close()
            encoder.close()
        }
    }

    private fun invalidInputIsRejected() {
        val encoder = OpusEncoder(SAMPLE_RATE, CHANNELS, 24_000)
        try {
            expectThrows<IllegalArgumentException> {
                encoder.encode(ShortArray(FRAME_SAMPLES - 1))
            }
        } finally {
            encoder.close()
        }
        expectThrows<IllegalArgumentException> { OpusEncoder(44_100, CHANNELS, 24_000) }
        expectThrows<IllegalArgumentException> { OpusDecoder(SAMPLE_RATE, 3) }
    }

    private fun malformedAndNonTwentyMillisecondPacketsAreRejected() {
        val decoder = OpusDecoder(SAMPLE_RATE, CHANNELS)
        try {
            expectThrows<IllegalArgumentException> { decoder.decode(byteArrayOf(0x00)) }
            // Opus TOC config 16 is a 2.5 ms full-band packet. The JNI bridge
            // must reject it before decoding because this wrapper is 20 ms only.
            expectThrows<IllegalArgumentException> {
                decoder.decode(byteArrayOf(0x80.toByte(), 0x00))
            }
        } finally {
            decoder.close()
        }
    }

    private fun closeIsIdempotentAndUseAfterCloseFails() {
        val encoder = OpusEncoder(SAMPLE_RATE, CHANNELS, 24_000)
        encoder.close()
        encoder.close()
        expectThrows<IllegalStateException> { encoder.encode(testFrame()) }

        val decoder = OpusDecoder(SAMPLE_RATE, CHANNELS)
        decoder.close()
        decoder.close()
        expectThrows<IllegalStateException> { decoder.decode(null) }
    }

    private fun repeatedInstancesReleaseNativeState() {
        repeat(32) {
            val encoder = OpusEncoder(SAMPLE_RATE, CHANNELS, 24_000)
            val decoder = OpusDecoder(SAMPLE_RATE, CHANNELS)
            val packet = encoder.encode(testFrame())
            check(decoder.decode(packet).size == FRAME_SAMPLES)
            decoder.close()
            encoder.close()
        }
    }

    private fun testFrame(): ShortArray = ShortArray(FRAME_SAMPLES) { index ->
        val phase = index % 96
        ((phase - 48) * 500).toShort()
    }

    private inline fun <reified T : Throwable> expectThrows(block: () -> Unit) {
        try {
            block()
        } catch (error: Throwable) {
            check(error is T) {
                "expected ${T::class.java.simpleName}, got ${error::class.java.simpleName}"
            }
            return
        }
        error("expected ${T::class.java.simpleName}")
    }
}
