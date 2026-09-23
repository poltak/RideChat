package com.ridechat.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    @Test
    fun controlMessagesRoundTripWithoutChangingTypedFields() {
        val secret = ByteArray(32) { it.toByte() }
        val nonce = ByteArray(32) { (it + 1).toByte() }
        val token = byteArrayOf(1, 2, 3, 4)
        val messages = listOf<Control>(
            Control.Hello("ride-1", "alice"),
            Control.Welcome("ride-1", "host", secret),
            Control.Members("ride-1", listOf("host", "alice", "bob")),
            Control.Ready("ride-1", "alice"),
            Control.Mute("ride-1", "alice", true),
            Control.Ping(17L),
            Control.Pong(18L),
            Control.ResumeHello("ride-1", "alice", nonce, ResumeRole.MEMBER, token),
            Control.Challenge("ride-1", "host", nonce, ResumeRole.HOST, token),
            Control.Proof("ride-1", "alice", nonce, secret, ResumeRole.MEMBER, token),
            Control.Accepted("ride-1", "host"),
            Control.Leave("ride-1", "alice"),
            Control.End("ride-1"),
        )

        for (message in messages) {
            val decoded = WireCodec.decodeControl(WireCodec.encodeControl(message))
            when {
                message is Control.Welcome && decoded is Control.Welcome -> assertArrayEquals(message.resumeSecret, decoded.resumeSecret)
                message is Control.ResumeHello && decoded is Control.ResumeHello -> {
                    assertArrayEquals(message.nonce, decoded.nonce)
                    assertArrayEquals(message.sessionToken, decoded.sessionToken)
                }

                message is Control.Challenge && decoded is Control.Challenge -> {
                    assertArrayEquals(message.nonce, decoded.nonce)
                    assertArrayEquals(message.sessionToken, decoded.sessionToken)
                }

                message is Control.Proof && decoded is Control.Proof -> {
                    assertArrayEquals(message.nonce, decoded.nonce)
                    assertArrayEquals(message.proof, decoded.proof)
                    assertArrayEquals(message.sessionToken, decoded.sessionToken)
                }

                else -> assertEquals(message, decoded)
            }
        }
    }

    @Test
    fun audioStreamDecoderHandlesPartialAndMultipleFrames() {
        val first = frame(0L, 0L, 0L, byteArrayOf(1, 2))
        val second = frame(0L, 1L, 960L, byteArrayOf(3, 4, 5))
        val encoded = WireCodec.encodeAudioFrame(first) + WireCodec.encodeAudioFrame(second)
        val decoder = AudioFrameStreamDecoder()

        assertTrue(decoder.feed(encoded.copyOfRange(0, 3)).isEmpty())
        val decoded = decoder.feed(encoded.copyOfRange(3, encoded.size))
        assertEquals(listOf(first, second).size, decoded.size)
        assertEquals(first.rideId, decoded[0].rideId)
        assertEquals(second.sequence, decoded[1].sequence)
        assertArrayEquals(second.payload, decoded[1].payload)
        decoder.finish()
    }

    @Test
    fun malformedControlAndAudioAreRejected() {
        expectProtocol { WireCodec.decodeControl(byteArrayOf(1)) }
        expectProtocol { WireCodec.decodeControl(byteArrayOf(99, 1)) }
        expectProtocol { WireCodec.decodeControl(WireCodec.encodeControl(Control.Ping(1L)) + byteArrayOf(0)) }

        val frame = WireCodec.encodeAudioFrame(frame(0L, 1L, 0L, byteArrayOf(9)))
        expectProtocol { WireCodec.decodeAudioFrame(frame.copyOfRange(0, frame.size - 1)) }
        expectProtocol { WireCodec.decodeAudioFrame(frame + byteArrayOf(0)) }
        val invalidLength = frame.copyOf()
        ByteBuffer.wrap(invalidLength).order(ByteOrder.BIG_ENDIAN).putInt(ProtocolLimits.MAX_AUDIO_FRAME_BODY_BYTES + 1)
        expectProtocol { WireCodec.decodeAudioFrame(invalidLength) }
        expectProtocol { AudioFrameStreamDecoder().feed(invalidLength) }
    }

    @Test
    fun invalidBoundsAndVersionAreRejectedBeforeAllocation() {
        expectProtocol {
            WireCodec.encodeControl(Control.Welcome("ride", "host", ByteArray(31)))
        }
        expectProtocol {
            WireCodec.encodeControl(Control.Members("ride", listOf("one", "one")))
        }
        expectProtocol {
            WireCodec.encodeAudioFrame(frame(0L, 0L, 0L, ByteArray(ProtocolLimits.MAX_AUDIO_PAYLOAD_BYTES + 1)))
        }

        val encoded = WireCodec.encodeControl(Control.Ping(1L)).copyOf()
        encoded[0] = 2
        expectProtocol { WireCodec.decodeControl(encoded) }
    }

    private fun frame(generation: Long, sequence: Long, sampleTime: Long, payload: ByteArray): AudioFrame {
        return AudioFrame("ride-1", "alice", generation, sequence, sampleTime, payload)
    }

    private fun expectProtocol(action: () -> Unit) {
        try {
            action()
            throw AssertionError("expected protocol error")
        } catch (_: ProtocolException) {
            return
        }
    }
}
