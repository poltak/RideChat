package com.ridechat.transport

import com.ridechat.core.AudioFrame
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportSessionPolicyTest {
    @Test
    fun admissionCountsPendingConnectionsAgainstThreePeerLimit() {
        assertTrue(TransportSessionPolicy.canAdmitPeer(activePeers = 2, pendingPeers = 0))
        assertFalse(TransportSessionPolicy.canAdmitPeer(activePeers = 2, pendingPeers = 1))
        assertFalse(TransportSessionPolicy.canAdmitPeer(activePeers = 3, pendingPeers = 0))
    }

    @Test
    fun disconnectedReservationsKeepTheirSeatButCanResume() {
        assertFalse(
            TransportSessionPolicy.canAdmitHostPeer(
                activePeers = 0,
                pendingPeers = 0,
                reservedMembers = 3,
                isResume = false,
            ),
        )
        assertTrue(
            TransportSessionPolicy.canAdmitHostPeer(
                activePeers = 0,
                pendingPeers = 0,
                reservedMembers = 3,
                isResume = true,
            ),
        )
        assertFalse(
            TransportSessionPolicy.canAdmitHostPeer(
                activePeers = 2,
                pendingPeers = 1,
                reservedMembers = 2,
                isResume = true,
            ),
        )
    }

    @Test
    fun acceptedBeforeResumeProofIsRejected() {
        assertFalse(
            TransportSessionPolicy.canAcceptResumeAccepted(
                rideId = "ride",
                senderId = "member",
                expectedRideId = "ride",
                expectedSenderId = "member",
                resumeProofVerified = false,
            ),
        )
        assertTrue(
            TransportSessionPolicy.canAcceptResumeAccepted(
                rideId = "ride",
                senderId = "member",
                expectedRideId = "ride",
                expectedSenderId = "member",
                resumeProofVerified = true,
            ),
        )
    }

    @Test
    fun relayRequiresTheSenderBoundToTheConnectionAndOmitsSelf() {
        val frame = AudioFrame("ride", "member-a", 1, 1, 960, byteArrayOf(1))
        assertTrue(TransportSessionPolicy.canRelay("endpoint-a", frame, "ride", "member-a"))
        assertFalse(TransportSessionPolicy.canRelay("endpoint-a", frame.copy(senderId = "member-b"), "ride", "member-a"))
        assertFalse(TransportSessionPolicy.shouldForward("member-a", frame))
        assertTrue(TransportSessionPolicy.shouldForward("member-b", frame))
    }

    @Test
    fun staleCallbacksCannotChangeAReplacedSession() {
        assertFalse(TransportSessionPolicy.isCurrentGeneration(4, 5))
        assertTrue(TransportSessionPolicy.isCurrentGeneration(5, 5))
    }

    @Test
    fun muteStateSurvivesResume() {
        assertTrue(TransportSessionPolicy.preserveMuteOnResume(previous = true))
        assertFalse(TransportSessionPolicy.preserveMuteOnResume(previous = false))
    }

    @Test
    fun rosterAndEndMessagesRequireAnAuthenticatedHost() {
        assertFalse(TransportSessionPolicy.isVerifiedHost(peerAuthenticated = false, peerSenderId = "host"))
        assertFalse(TransportSessionPolicy.isVerifiedHost(peerAuthenticated = true, peerSenderId = "member"))
        assertTrue(TransportSessionPolicy.isVerifiedHost(peerAuthenticated = true, peerSenderId = "host"))
    }

    @Test
    fun audioRequiresAnActiveAuthenticatedSession() {
        assertFalse(TransportSessionPolicy.canAcceptInboundAudio(sessionActive = false, peerAuthenticated = true))
        assertFalse(TransportSessionPolicy.canAcceptInboundAudio(sessionActive = true, peerAuthenticated = false))
        assertTrue(TransportSessionPolicy.canAcceptInboundAudio(sessionActive = true, peerAuthenticated = true))
    }

    @Test
    fun inProgressPayloadUpdatesDoNotCloseAudioStreams() {
        assertFalse(TransportSessionPolicy.isFailedPayloadStatus(status = 1))
        assertTrue(TransportSessionPolicy.isFailedPayloadStatus(status = 2))
        assertFalse(TransportSessionPolicy.isFailedPayloadStatus(status = 3))
        assertTrue(TransportSessionPolicy.isFailedPayloadStatus(status = 4))
    }

    @Test
    fun phaseProgressesFromDiscoveryToLobbyActiveReconnectAndResume() {
        assertTrue(
            TransportSessionPolicy.derivePhase(
                rideSelected = false,
                discovering = true,
                selectedHost = false,
                rideStarted = false,
                peerAuthenticated = false,
                reconnecting = false,
            ) == Phase.DISCOVERING,
        )
        assertTrue(
            TransportSessionPolicy.derivePhase(
                rideSelected = true,
                discovering = true,
                selectedHost = true,
                rideStarted = false,
                peerAuthenticated = false,
                reconnecting = false,
            ) == Phase.LOBBY,
        )
        assertTrue(
            TransportSessionPolicy.derivePhase(
                rideSelected = true,
                discovering = false,
                selectedHost = true,
                rideStarted = false,
                peerAuthenticated = true,
                reconnecting = false,
            ) == Phase.LOBBY,
        )
        assertTrue(
            TransportSessionPolicy.derivePhase(
                rideSelected = true,
                discovering = false,
                selectedHost = true,
                rideStarted = true,
                peerAuthenticated = true,
                reconnecting = false,
            ) == Phase.ACTIVE,
        )
        assertTrue(
            TransportSessionPolicy.derivePhase(
                rideSelected = true,
                discovering = false,
                selectedHost = true,
                rideStarted = true,
                peerAuthenticated = false,
                reconnecting = true,
            ) == Phase.RECONNECTING,
        )
        assertTrue(
            TransportSessionPolicy.derivePhase(
                rideSelected = true,
                discovering = false,
                selectedHost = true,
                rideStarted = true,
                peerAuthenticated = true,
                reconnecting = false,
            ) == Phase.ACTIVE,
        )
    }
}
