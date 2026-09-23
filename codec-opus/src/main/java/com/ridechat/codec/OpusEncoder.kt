package com.ridechat.codec

/** Encodes one 20 ms PCM frame per call with the upstream Opus codec. */
class OpusEncoder(
    private val sampleRate: Int = 48_000,
    private val channels: Int = 1,
    bitrate: Int = 24_000,
) : AutoCloseable {
    private var nativeHandle: Long

    init {
        OpusNativeLibrary.ensureLoaded()
        validateConfiguration(sampleRate, channels)
        nativeHandle = nativeCreate(sampleRate, channels, bitrate)
        check(nativeHandle != 0L) { "Opus encoder creation failed" }
    }

    @Synchronized
    fun encode(pcm: ShortArray): ByteArray {
        check(nativeHandle != 0L) { "Opus encoder is closed" }
        require(pcm.size == frameSamples(sampleRate) * channels) {
            "pcm must contain exactly one 20 ms frame (${frameSamples(sampleRate) * channels} samples)"
        }
        return nativeEncode(nativeHandle, pcm, sampleRate, channels)
    }

    @Synchronized
    override fun close() {
        if (nativeHandle != 0L) {
            nativeDestroy(nativeHandle)
            nativeHandle = 0L
        }
    }

    private external fun nativeCreate(sampleRate: Int, channels: Int, bitrate: Int): Long
    private external fun nativeEncode(handle: Long, pcm: ShortArray, sampleRate: Int, channels: Int): ByteArray
    private external fun nativeDestroy(handle: Long)
}

