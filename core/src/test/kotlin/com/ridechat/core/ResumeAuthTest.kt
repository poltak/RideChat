package com.ridechat.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumeAuthTest {
    @Test
    fun proofChangesWhenNonceTokenOrRoleChanges() {
        val secret = ByteArray(32) { 7 }
        val localNonce = ByteArray(32) { 1 }
        val remoteNonce = ByteArray(32) { 2 }
        val token = byteArrayOf(3, 4, 5)
        val baseline = proof(secret, localNonce, remoteNonce, ResumeRole.MEMBER, token)

        assertArrayEquals(baseline, proof(secret, localNonce, remoteNonce, ResumeRole.MEMBER, token))
        assertNotEquals(baseline.toList(), proof(secret, localNonce, ByteArray(32) { 9 }, ResumeRole.MEMBER, token).toList())
        assertNotEquals(baseline.toList(), proof(secret, ByteArray(32) { 8 }, remoteNonce, ResumeRole.MEMBER, token).toList())
        assertNotEquals(baseline.toList(), proof(secret, localNonce, remoteNonce, ResumeRole.HOST, token).toList())
        assertNotEquals(baseline.toList(), proof(secret, localNonce, remoteNonce, ResumeRole.MEMBER, byteArrayOf(99)).toList())
    }

    @Test
    fun wrongSecretDoesNotValidateAndComparisonIsConstantTimeValueCheck() {
        val secret = ByteArray(32) { 7 }
        val wrongSecret = ByteArray(32) { 8 }
        val localNonce = ByteArray(32) { 1 }
        val remoteNonce = ByteArray(32) { 2 }
        val token = byteArrayOf(3, 4, 5)
        val expected = proof(secret, localNonce, remoteNonce, ResumeRole.MEMBER, token)
        val received = proof(wrongSecret, localNonce, remoteNonce, ResumeRole.MEMBER, token)

        assertFalse(ResumeAuth.constantTimeEquals(expected, received))
        assertTrue(ResumeAuth.constantTimeEquals(expected, expected.copyOf()))
        assertFalse(ResumeAuth.constantTimeEquals(expected, expected.copyOf(expected.size - 1)))
    }

    @Test
    fun replayGuardAcceptsFreshNonceOnlyOncePerContext() {
        val guard = ResumeReplayGuard(maxEntries = 2)
        val first = ByteArray(32) { 1 }
        val second = ByteArray(32) { 2 }
        val third = ByteArray(32) { 3 }

        assertTrue(guard.accept("ride/member", first))
        assertFalse(guard.accept("ride/member", first))
        assertTrue(guard.accept("other-ride/member", first))
        assertTrue(guard.accept("ride/member", second))
        assertEqualsSize(2, guard)
        assertTrue(guard.accept("ride/member", third))
        assertEqualsSize(2, guard)
    }

    @Test
    fun invalidResumeInputsAreRejected() {
        val secret = ByteArray(32)
        val nonce = ByteArray(32)
        val token = byteArrayOf(1)
        expectProtocol {
            ResumeAuth.proof(ByteArray(31), 1, "ride", "local", "remote", ResumeRole.MEMBER, nonce, nonce, token)
        }
        expectProtocol {
            ResumeAuth.proof(secret, 1, "ride", "local", "remote", ResumeRole.MEMBER, ByteArray(31), nonce, token)
        }
        expectProtocol {
            ResumeAuth.proof(secret, 1, "ride", "local", "remote", ResumeRole.MEMBER, nonce, nonce, ByteArray(0))
        }
        expectProtocol { ResumeReplayGuard().accept("ride", ByteArray(31)) }
    }

    private fun proof(
        secret: ByteArray,
        localNonce: ByteArray,
        remoteNonce: ByteArray,
        role: ResumeRole,
        token: ByteArray,
    ): ByteArray {
        return ResumeAuth.proof(
            secret = secret,
            version = ProtocolLimits.VERSION,
            rideId = "ride-1",
            localMemberId = "alice",
            remoteMemberId = "host",
            role = role,
            localNonce = localNonce,
            remoteNonce = remoteNonce,
            sessionToken = token,
        )
    }

    private fun assertEqualsSize(expected: Int, guard: ResumeReplayGuard) {
        assertTrue(guard.size() == expected)
    }

    private fun expectProtocol(action: () -> Unit) {
        try {
            action()
            assertTrue("expected protocol error", false)
        } catch (_: ProtocolException) {
            // Expected.
        }
    }
}
