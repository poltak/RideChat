package com.ridechat.codec

internal object OpusNativeLibrary {
    private var loaded = false

    @Synchronized
    fun ensureLoaded() {
        if (!loaded) {
            System.loadLibrary("opus_jni")
            loaded = true
        }
    }
}

internal fun validateConfiguration(sampleRate: Int, channels: Int) {
    require(sampleRate in setOf(8_000, 12_000, 16_000, 24_000, 48_000)) {
        "sampleRate must be one of 8000, 12000, 16000, 24000, or 48000 Hz"
    }
    require(channels == 1 || channels == 2) { "channels must be 1 or 2" }
}

internal fun frameSamples(sampleRate: Int): Int = sampleRate / 50
