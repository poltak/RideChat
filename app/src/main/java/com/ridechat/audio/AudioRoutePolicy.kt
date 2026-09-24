package com.ridechat.audio

enum class AudioRoutePreference { AUTOMATIC, PHONE, HEADSET }

enum class AudioRouteKind { PHONE, HEADSET }

/** Select the initial audio route from the available devices. */
object AudioRoutePolicy {
    fun select(
        preference: AudioRoutePreference,
        phoneAvailable: Boolean,
        headsetAvailable: Boolean,
    ): AudioRouteKind? = when (preference) {
        AudioRoutePreference.AUTOMATIC -> when {
            headsetAvailable -> AudioRouteKind.HEADSET
            phoneAvailable -> AudioRouteKind.PHONE
            else -> null
        }
        AudioRoutePreference.PHONE -> AudioRouteKind.PHONE.takeIf { phoneAvailable }
        AudioRoutePreference.HEADSET -> AudioRouteKind.HEADSET.takeIf { headsetAvailable }
    }
}
