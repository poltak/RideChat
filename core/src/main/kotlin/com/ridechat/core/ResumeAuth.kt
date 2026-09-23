package com.ridechat.core

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** HMAC helpers for the post-admission resume handshake. */
object ResumeAuth {
    private val magic = "ridechat-resume-v1".toByteArray(StandardCharsets.US_ASCII)

    /**
     * Builds the canonical HMAC input. Every variable field is length-delimited,
     * so concatenation cannot make two different handshakes ambiguous.
     */
    fun canonicalInput(
        version: Int,
        rideId: String,
        localMemberId: String,
        remoteMemberId: String,
        role: ResumeRole,
        localNonce: ByteArray,
        remoteNonce: ByteArray,
        sessionToken: ByteArray,
    ): ByteArray {
        if (version !in 0..255) throw ProtocolException("protocol version out of bounds")
        validateText(rideId, ProtocolLimits.MAX_RIDE_ID_BYTES, "ride ID")
        validateText(localMemberId, ProtocolLimits.MAX_MEMBER_ID_BYTES, "local member ID")
        validateText(remoteMemberId, ProtocolLimits.MAX_MEMBER_ID_BYTES, "remote member ID")
        if (localNonce.size != ProtocolLimits.NONCE_BYTES) throw ProtocolException("local nonce must be 32 bytes")
        if (remoteNonce.size != ProtocolLimits.NONCE_BYTES) throw ProtocolException("remote nonce must be 32 bytes")
        if (sessionToken.isEmpty() || sessionToken.size > ProtocolLimits.MAX_TOKEN_BYTES) {
            throw ProtocolException("session token length out of bounds")
        }

        val result = ByteArrayOutputStream()
        DataOutputStream(result).use { output ->
            writeBytes(output, magic)
            output.writeByte(version)
            writeText(output, rideId)
            writeText(output, localMemberId)
            writeText(output, remoteMemberId)
            output.writeByte(role.ordinal)
            writeBytes(output, localNonce)
            writeBytes(output, remoteNonce)
            writeBytes(output, sessionToken)
        }
        return result.toByteArray()
    }

    fun proof(
        secret: ByteArray,
        version: Int,
        rideId: String,
        localMemberId: String,
        remoteMemberId: String,
        role: ResumeRole,
        localNonce: ByteArray,
        remoteNonce: ByteArray,
        sessionToken: ByteArray,
    ): ByteArray {
        if (secret.size != ProtocolLimits.RESUME_SECRET_BYTES) {
            throw ProtocolException("resume secret must be 32 bytes")
        }
        val input = canonicalInput(
            version = version,
            rideId = rideId,
            localMemberId = localMemberId,
            remoteMemberId = remoteMemberId,
            role = role,
            localNonce = localNonce,
            remoteNonce = remoteNonce,
            sessionToken = sessionToken,
        )
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.copyOf(), "HmacSHA256"))
        return mac.doFinal(input)
    }

    /** Constant-time comparison that does not return early on a length mismatch. */
    fun constantTimeEquals(left: ByteArray, right: ByteArray): Boolean {
        var difference = left.size xor right.size
        val count = maxOf(left.size, right.size)
        for (index in 0 until count) {
            val leftByte = if (index < left.size) left[index].toInt() else 0
            val rightByte = if (index < right.size) right[index].toInt() else 0
            difference = difference or (leftByte xor rightByte)
        }
        return difference == 0
    }

    private fun writeText(output: DataOutputStream, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        output.writeShort(bytes.size)
        output.write(bytes)
    }

    private fun writeBytes(output: DataOutputStream, value: ByteArray) {
        output.writeShort(value.size)
        output.write(value)
    }

    private fun validateText(value: String, maxBytes: Int, label: String) {
        if (value.isEmpty()) throw ProtocolException("$label must not be empty")
        if (value.indexOf('\u0000') >= 0) throw ProtocolException("$label contains NUL")
        if (value.toByteArray(StandardCharsets.UTF_8).size > maxBytes) {
            throw ProtocolException("$label exceeds $maxBytes UTF-8 bytes")
        }
    }
}

/**
 * Bounded replay memory for a resume channel. The key should include the ride,
 * member, role, and connection context chosen by the session layer.
 */
class ResumeReplayGuard(private val maxEntries: Int = 128) {
    private data class Entry(val key: String, val nonce: ByteArray)

    private val entries = LinkedHashMap<String, Entry>()

    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    @Synchronized
    fun accept(key: String, nonce: ByteArray): Boolean {
        if (key.isEmpty()) throw ProtocolException("replay key must not be empty")
        if (key.indexOf('\u0000') >= 0 || key.toByteArray(StandardCharsets.UTF_8).size > 256) {
            throw ProtocolException("replay key is out of bounds")
        }
        if (nonce.size != ProtocolLimits.NONCE_BYTES) throw ProtocolException("replay nonce must be 32 bytes")
        val mapKey = "$key:${nonce.toHex()}"
        val existing = entries[mapKey]
        if (existing != null && existing.key == key && ResumeAuth.constantTimeEquals(existing.nonce, nonce)) {
            return false
        }
        while (entries.size >= maxEntries) {
            val oldest = entries.entries.iterator()
            if (!oldest.hasNext()) break
            oldest.next()
            oldest.remove()
        }
        entries[mapKey] = Entry(key, nonce.copyOf())
        return true
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    @Synchronized
    fun size(): Int = entries.size

    private fun ByteArray.toHex(): String {
        val result = StringBuilder(size * 2)
        for (value in this) {
            result.append("0123456789abcdef"[(value.toInt() ushr 4) and 0x0f])
            result.append("0123456789abcdef"[value.toInt() and 0x0f])
        }
        return result.toString()
    }
}
