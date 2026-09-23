package com.ridechat.transport

import com.ridechat.core.AudioFrame
import com.ridechat.core.ProtocolLimits
import com.ridechat.core.RelayPolicy

/** Pure admission and relay checks shared by the Nearby coordinator. */
internal object TransportSessionPolicy {
    fun canAdmitPeer(activePeers: Int, pendingPeers: Int): Boolean {
        if (activePeers < 0 || pendingPeers < 0) return false
        return activePeers + pendingPeers < ProtocolLimits.MAX_MEMBERS - 1
    }

    fun canAdmitHostPeer(
        activePeers: Int,
        pendingPeers: Int,
        reservedMembers: Int,
        isResume: Boolean,
    ): Boolean {
        if (reservedMembers !in 0..(ProtocolLimits.MAX_MEMBERS - 1)) return false
        return canAdmitPeer(activePeers, pendingPeers) &&
            (isResume || reservedMembers < ProtocolLimits.MAX_MEMBERS - 1)
    }

    fun isCurrentGeneration(callbackGeneration: Long, sessionGeneration: Long): Boolean =
        callbackGeneration == sessionGeneration

    fun canAcceptInitialAccepted(
        rideId: String,
        senderId: String,
        expectedRideId: String,
        expectedSenderId: String,
        welcomeSent: Boolean,
    ): Boolean =
        welcomeSent &&
            rideId == expectedRideId &&
            senderId == expectedSenderId &&
            senderId.isNotEmpty()

    fun canAcceptResumeAccepted(
        rideId: String,
        senderId: String,
        expectedRideId: String,
        expectedSenderId: String,
        resumeProofVerified: Boolean,
    ): Boolean =
        resumeProofVerified &&
            rideId == expectedRideId &&
            senderId == expectedSenderId &&
            senderId.isNotEmpty()

    fun canRelay(
        sourceConnectionId: String,
        frame: AudioFrame,
        rideId: String,
        authorizedSenderId: String,
    ): Boolean = RelayPolicy.validate(
        sourceConnectionId = sourceConnectionId,
        frame = frame,
        expectedRideId = rideId,
        expectedSenderId = authorizedSenderId,
    )

    fun shouldForward(destinationSenderId: String, frame: AudioFrame): Boolean =
        RelayPolicy.shouldForwardTo(destinationSenderId, frame)

    fun preserveMuteOnResume(previous: Boolean): Boolean = previous

    fun isVerifiedHost(peerAuthenticated: Boolean, peerSenderId: String?): Boolean =
        peerAuthenticated && peerSenderId == "host"

    fun canAcceptInboundAudio(sessionActive: Boolean, peerAuthenticated: Boolean): Boolean =
        sessionActive && peerAuthenticated

    /** Nearby PayloadTransferUpdate: FAILURE=2, IN_PROGRESS=3, CANCELED=4. */
    fun isFailedPayloadStatus(status: Int): Boolean = status == 2 || status == 4

    fun derivePhase(
        rideSelected: Boolean,
        discovering: Boolean,
        selectedHost: Boolean,
        rideStarted: Boolean,
        peerAuthenticated: Boolean,
        reconnecting: Boolean,
    ): Phase = when {
        reconnecting && !peerAuthenticated -> Phase.RECONNECTING
        rideStarted -> Phase.ACTIVE
        !rideSelected && discovering -> Phase.DISCOVERING
        !selectedHost && discovering -> Phase.DISCOVERING
        rideSelected || selectedHost -> Phase.LOBBY
        else -> Phase.IDLE
    }
}
