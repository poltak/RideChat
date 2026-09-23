package com.ridechat.core

import java.util.TreeMap

/**
 * A bounded, per-speaker playout queue. The first three frames establish a
 * 60 ms startup buffer (48 kHz, 20 ms frame cadence by default). Once started,
 * [poll] advances exactly one 20 ms slot per call and returns null when the
 * slot is missing so the decoder can use Opus loss concealment or silence.
 * A newer frame received while full evicts the oldest retained frame.
 */
class PerSpeakerJitterBuffer(
    private val frameDurationSamples: Long = 960L,
    private val targetDepthFrames: Int = 3,
    private val capacityFrames: Int = 6,
) {
    private val frames = TreeMap<Long, AudioFrame>()
    private var activeGeneration: Long? = null
    private var nextSequence: Long? = null
    private var nextDueSampleTime: Long? = null
    private var highestSequence: Long? = null
    private var lastConsumedSequence: Long? = null

    init {
        require(frameDurationSamples > 0) { "frameDurationSamples must be positive" }
        require(targetDepthFrames > 0) { "targetDepthFrames must be positive" }
        require(capacityFrames >= targetDepthFrames) { "capacityFrames must cover target depth" }
        require(frameDurationSamples <= Long.MAX_VALUE / targetDepthFrames.toLong()) {
            "frame duration and target depth overflow"
        }
    }

    /** Returns false for invalid, stale, duplicate, or overflow frames. */
    @Synchronized
    fun offer(frame: AudioFrame): Boolean {
        if (frame.generation < 0L || frame.sequence < 0L || frame.sampleTime < 0L) return false
        if (frame.payload.isEmpty() || frame.payload.size > ProtocolLimits.MAX_AUDIO_PAYLOAD_BYTES) return false
        if (frame.rideId.isEmpty() || frame.senderId.isEmpty()) return false

        val current = activeGeneration
        if (current == null || frame.generation > current) {
            frames.clear()
            activeGeneration = frame.generation
            nextSequence = null
            nextDueSampleTime = null
            highestSequence = null
            lastConsumedSequence = null
        } else if (frame.generation < current) {
            return false
        }

        val consumed = lastConsumedSequence
        if (consumed != null && frame.sequence <= consumed) return false
        if (nextSequence != null && frame.sequence < nextSequence!!) return false
        if (frames.containsKey(frame.sequence)) return false
        if (frames.size >= capacityFrames) {
            val oldestSequence = frames.firstKey()
            if (frame.sequence <= oldestSequence) return false
            frames.remove(oldestSequence)
        }

        val highest = highestSequence
        if (highest != null && frame.sequence <= highest) {
            // Out-of-order frames are useful within the live window, but a
            // duplicate or a sequence that has already been observed is not.
            if (frames.containsKey(frame.sequence)) return false
        }
        frames[frame.sequence] = frame
        if (highest == null || frame.sequence > highest) highestSequence = frame.sequence
        if (nextSequence == null && frames.size >= targetDepthFrames) {
            val first = frames.firstEntry().value
            nextSequence = first.sequence
            nextDueSampleTime = safeAdd(first.sampleTime, frameDurationSamples * targetDepthFrames.toLong())
        } else if (nextSequence != null && frames.isNotEmpty()) {
            val firstRetained = frames.firstKey()
            val currentNext = nextSequence!!
            if (firstRetained > currentNext) {
                val skipped = firstRetained - currentNext
                nextSequence = firstRetained
                nextDueSampleTime = safeAdd(nextDueSampleTime ?: 0L, safeMultiply(skipped, frameDurationSamples))
            }
        }
        return true
    }

    /**
     * Consumes one playout slot. Call this from the output cadence (one call per
     * 20 ms); a missing slot returns null for decoder concealment or silence.
     */
    @Synchronized
    fun poll(): AudioFrame? {
        val sequence = nextSequence ?: return null
        return consume(sequence)
    }

    /**
     * Returns the next frame when due, or null for startup delay, missing data,
     * and an empty queue. This overload is for callers that have already mapped
     * the remote sample clock into the local media clock. Prefer [poll] when the
     * output loop itself provides the 20 ms cadence.
     */
    @Synchronized
    fun poll(nowSampleTime: Long): AudioFrame? {
        if (nowSampleTime < 0L) return null
        val sequence = nextSequence ?: return null
        val due = nextDueSampleTime ?: return null
        if (nowSampleTime < due) return null
        return consume(sequence)
    }

    @Synchronized
    fun reset() {
        frames.clear()
        activeGeneration = null
        nextSequence = null
        nextDueSampleTime = null
        highestSequence = null
        lastConsumedSequence = null
    }

    @Synchronized
    fun queuedFrames(): Int = frames.size

    @Synchronized
    fun generation(): Long? = activeGeneration

    private fun safeAdd(left: Long, right: Long): Long {
        if (right > 0L && left > Long.MAX_VALUE - right) return Long.MAX_VALUE
        return left + right
    }

    private fun safeMultiply(left: Long, right: Long): Long {
        if (left <= 0L || right <= 0L) return 0L
        if (left > Long.MAX_VALUE / right) return Long.MAX_VALUE
        return left * right
    }

    private fun consume(sequence: Long): AudioFrame? {
        val frame = frames.remove(sequence)
        lastConsumedSequence = sequence
        nextSequence = safeAdd(sequence, 1L)
        nextDueSampleTime = safeAdd(nextDueSampleTime ?: 0L, frameDurationSamples)
        return frame
    }
}

/** Adds PCM streams sample-by-sample and clips each result to signed 16-bit. */
object PcmMixer {
    fun mix(frames: Collection<ShortArray>): ShortArray {
        if (frames.isEmpty()) return ShortArray(0)
        val outputLength = frames.maxOf { it.size }
        val mixed = ShortArray(outputLength)
        for (index in 0 until outputLength) {
            var sum = 0L
            for (frame in frames) {
                if (index < frame.size) sum += frame[index].toLong()
            }
            mixed[index] = sum.coerceIn(Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong()).toShort()
        }
        return mixed
    }
}

/** Validates the identity attached by the transport before the host relays media. */
object RelayPolicy {
    fun validate(
        sourceConnectionId: String,
        frame: AudioFrame,
        expectedRideId: String,
        expectedSenderId: String,
    ): Boolean {
        if (sourceConnectionId.isEmpty() || expectedRideId.isEmpty() || expectedSenderId.isEmpty()) return false
        if (frame.rideId != expectedRideId || frame.senderId != expectedSenderId) return false
        if (frame.generation < 0L || frame.sequence < 0L || frame.sampleTime < 0L) return false
        return frame.payload.isNotEmpty() && frame.payload.size <= ProtocolLimits.MAX_AUDIO_PAYLOAD_BYTES
    }

    /** The host must omit the frame from the sender's own outgoing queue. */
    fun shouldForwardTo(destinationSenderId: String, frame: AudioFrame): Boolean {
        return destinationSenderId.isNotEmpty() && destinationSenderId != frame.senderId
    }
}
