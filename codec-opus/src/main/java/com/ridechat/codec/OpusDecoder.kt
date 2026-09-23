package com.ridechat.codec

/** Decodes one Opus packet into PCM. A null packet requests Opus loss concealment. */
class OpusDecoder(
    private val sampleRate: Int = 48_000,
    private val channels: Int = 1,
) : AutoCloseable {
    private var nativeHandle: Long

    init {
        OpusNativeLibrary.ensureLoaded()
        validateConfiguration(sampleRate, channels)
        nativeHandle = nativeCreate(sampleRate, channels)
        check(nativeHandle != 0L) { "Opus decoder creation failed" }
    }

    @Synchronized
    fun decode(data: ByteArray?): ShortArray {
        check(nativeHandle != 0L) { "Opus decoder is closed" }
        return nativeDecode(nativeHandle, data, sampleRate, channels)
    }

    @Synchronized
    override fun close() {
        if (nativeHandle != 0L) {
            nativeDestroy(nativeHandle)
            nativeHandle = 0L
        }
    }

    private external fun nativeCreate(sampleRate: Int, channels: Int): Long
    private external fun nativeDecode(handle: Long, data: ByteArray?, sampleRate: Int, channels: Int): ShortArray
    private external fun nativeDestroy(handle: Long)
}

