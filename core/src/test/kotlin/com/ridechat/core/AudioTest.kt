package com.ridechat.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioTest {
    @Test
    fun jitterBufferWaitsForSixtyMillisecondsThenReturnsFramesOnTwentyMillisecondCadence() {
        val buffer = PerSpeakerJitterBuffer()
        repeat(3) { sequence ->
            assertTrue(buffer.offer(frame(0L, sequence.toLong(), sequence * 960L)))
        }

        assertNull(buffer.poll(2879L))
        assertEquals(0L, buffer.poll(2880L)?.sequence)
        assertEquals(1L, buffer.poll(3840L)?.sequence)
        assertEquals(2L, buffer.poll(4800L)?.sequence)
    }

    @Test
    fun missingSlotReturnsNullAndLateFrameCannotReplayStaleAudio() {
        val buffer = PerSpeakerJitterBuffer()
        repeat(3) { sequence ->
            if (sequence != 1) assertTrue(buffer.offer(frame(0L, sequence.toLong(), sequence * 960L)))
        }
        // The third packet completes startup, but sequence 1 is missing.
        assertTrue(buffer.offer(frame(0L, 3L, 2880L)))
        assertEquals(0L, buffer.poll(2880L)?.sequence)
        assertNull(buffer.poll(3840L))
        assertEquals(2L, buffer.poll(4800L)?.sequence)
        assertEquals(3L, buffer.poll(5760L)?.sequence)
        assertFalse(buffer.offer(frame(0L, 1L, 960L)))
    }

    @Test
    fun generationChangeClearsQueueAndOldGenerationIsRejected() {
        val buffer = PerSpeakerJitterBuffer()
        repeat(3) { sequence -> assertTrue(buffer.offer(frame(4L, sequence.toLong(), sequence * 960L))) }
        assertTrue(buffer.offer(frame(5L, 10L, 10000L)))
        assertEquals(5L, buffer.generation())
        assertEquals(1, buffer.queuedFrames())
        assertFalse(buffer.offer(frame(4L, 99L, 99999L)))
        assertFalse(buffer.offer(frame(5L, 10L, 10000L)))
    }

    @Test
    fun queueOverflowIsBoundedAndDuplicateSequenceIsDropped() {
        val buffer = PerSpeakerJitterBuffer(targetDepthFrames = 3, capacityFrames = 6)
        repeat(6) { sequence -> assertTrue(buffer.offer(frame(0L, sequence.toLong(), sequence * 960L))) }
        assertTrue(buffer.offer(frame(0L, 6L, 5760L)))
        assertFalse(buffer.offer(frame(0L, 5L, 4800L)))
        assertEquals(6, buffer.queuedFrames())
        assertEquals(1L, buffer.poll(3840L)?.sequence)
    }

    @Test
    fun queueOverflowAdvancesCadenceToTheOldestRetainedFrame() {
        val buffer = PerSpeakerJitterBuffer(targetDepthFrames = 3, capacityFrames = 6)
        repeat(6) { sequence -> assertTrue(buffer.offer(frame(0L, sequence.toLong(), sequence * 960L))) }
        assertTrue(buffer.offer(frame(0L, 6L, 5760L)))

        assertEquals(1L, buffer.poll()?.sequence)
    }

    @Test
    fun pcmMixerSaturatesAndPreservesLongestInput() {
        val mixed = PcmMixer.mix(
            listOf(
                shortArrayOf(Short.MAX_VALUE, 100, -200),
                shortArrayOf(1, Short.MAX_VALUE, 100),
                shortArrayOf(-2),
            ),
        )
        assertArrayEquals(shortArrayOf(32766, Short.MAX_VALUE, -100), mixed)
    }

    @Test
    fun relayValidationRejectsSpoofedSourceAndNeverSendsToSpeaker() {
        val accepted = frame(0L, 1L, 960L)
        assertTrue(RelayPolicy.validate("connection-1", accepted, "ride-1", "alice"))
        assertFalse(RelayPolicy.validate("connection-1", accepted.copy(senderId = "bob"), "ride-1", "alice"))
        assertFalse(RelayPolicy.validate("connection-1", accepted.copy(rideId = "other"), "ride-1", "alice"))
        assertFalse(RelayPolicy.shouldForwardTo("alice", accepted))
        assertTrue(RelayPolicy.shouldForwardTo("bob", accepted))
    }

    private fun frame(generation: Long, sequence: Long, sampleTime: Long): AudioFrame {
        return AudioFrame("ride-1", "alice", generation, sequence, sampleTime, byteArrayOf(1, 2, 3))
    }
}
