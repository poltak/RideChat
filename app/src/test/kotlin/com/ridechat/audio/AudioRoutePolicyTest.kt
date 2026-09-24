package com.ridechat.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioRoutePolicyTest {
    @Test
    fun automaticUsesPhoneWhenNoHeadsetIsConnected() {
        assertEquals(AudioRouteKind.PHONE, AudioRoutePolicy.select(AudioRoutePreference.AUTOMATIC, true, false))
    }

    @Test
    fun automaticPrefersHeadsetWhenBothRoutesExist() {
        assertEquals(AudioRouteKind.HEADSET, AudioRoutePolicy.select(AudioRoutePreference.AUTOMATIC, true, true))
    }

    @Test
    fun explicitHeadsetChoiceDoesNotFallBackToSpeaker() {
        assertNull(AudioRoutePolicy.select(AudioRoutePreference.HEADSET, true, false))
    }

    @Test
    fun explicitPhoneChoiceWorksWithHeadsetConnected() {
        assertEquals(AudioRouteKind.PHONE, AudioRoutePolicy.select(AudioRoutePreference.PHONE, true, true))
    }

    @Test
    fun noAudioDeviceReturnsNoRoute() {
        assertNull(AudioRoutePolicy.select(AudioRoutePreference.AUTOMATIC, false, false))
    }
}
