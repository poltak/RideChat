package com.ridechat.transport

enum class Phase {
    IDLE,
    DISCOVERING,
    LOBBY,
    ACTIVE,
    RECONNECTING,
}

data class DiscoveredPeer(
    val endpointId: String,
    val name: String,
)

data class Member(
    val senderId: String,
    val name: String,
    val connected: Boolean = true,
    val muted: Boolean = false,
)

data class Confirmation(
    val endpointId: String,
    val name: String,
    val code: String,
)

data class RideState(
    val isHost: Boolean = false,
    val phase: Phase = Phase.IDLE,
    val rideId: String? = null,
    val senderId: String? = null,
    val discovered: List<DiscoveredPeer> = emptyList(),
    val members: List<Member> = emptyList(),
    val pending: List<Confirmation> = emptyList(),
    val muted: Boolean = false,
    val error: String? = null,
    val status: String = "Ready",
)
