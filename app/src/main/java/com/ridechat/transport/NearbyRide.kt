package com.ridechat.transport

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.ridechat.audio.AudioEngine
import com.ridechat.core.AudioFrame
import com.ridechat.core.AudioFrameStreamDecoder
import com.ridechat.core.Control
import com.ridechat.core.ProtocolException
import com.ridechat.core.ProtocolLimits
import com.ridechat.core.ResumeAuth
import com.ridechat.core.ResumeReplayGuard
import com.ridechat.core.ResumeRole
import com.ridechat.core.WireCodec
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Nearby Connections coordinator for one offline ride session. */
class NearbyRide(
    context: Context,
    private val listener: Listener,
) : Closeable {
    interface Listener {
        fun onAudio(frame: AudioFrame)
        fun onPeerDisconnected(senderId: String)
        fun onSessionEnded()
    }

    private val appContext = context.applicationContext
    private val connections: ConnectionsClient = Nearby.getConnectionsClient(appContext)
    private val sessionHandler = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(RideState())
    val state: StateFlow<RideState> = _state.asStateFlow()

    private val callbackExecutor: ExecutorService =
        Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "RideChatNearbyReader").apply { isDaemon = true }
        }
    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "RideChatNearbyControl").apply { isDaemon = true }
        }
    private val secureRandom = SecureRandom()
    private val replayGuard = ResumeReplayGuard()
    private val closed = AtomicBoolean(false)

    private var sessionGeneration = 0L
    private var role = Role.NONE
    private var rideId: String? = null
    private var localSenderId: String? = null
    private var localName = "Rider"
    private var localMuted = false
    private var rideStarted = false
    private var admissionClosed = false
    private var discoveryName: String? = null
    private var hostEndpointId: String? = null
    private var localResumeSecret: ByteArray? = null
    private var reconnectAttempt = 0
    private var reconnectTimeout: ScheduledFuture<*>? = null
    private var heartbeatFuture: ScheduledFuture<*>? = null
    private var leaveTimeout: ScheduledFuture<*>? = null

    private val discovered = LinkedHashMap<String, DiscoveredPeer>()
    private val discoveredRideIds = LinkedHashMap<String, String>()
    private val pending = LinkedHashMap<String, PendingConnection>()
    private val peers = LinkedHashMap<String, Peer>()
    private val records = LinkedHashMap<String, MemberRecord>()

    private val lifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            dispatchToSession(sessionGeneration) {
                handleConnectionInitiated(endpointId, connectionInfo)
            }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            dispatchToSession(sessionGeneration) {
                handleConnectionResult(endpointId, result)
            }
        }

        override fun onDisconnected(endpointId: String) {
            dispatchToSession(sessionGeneration) {
                handlePeerFailure(endpointId, "connection lost")
            }
        }
    }

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, endpointInfo: DiscoveredEndpointInfo) {
            dispatchToSession(sessionGeneration) {
                handleEndpointFound(endpointId, endpointInfo)
            }
        }

        override fun onEndpointLost(endpointId: String) {
            dispatchToSession(sessionGeneration) {
                discovered.remove(endpointId)
                discoveredRideIds.remove(endpointId)
                refreshState(status = "Nearby group unavailable")
            }
        }
    }

    fun host(name: String) {
        postToSession { hostOnSession(name) }
    }

    fun discover(name: String) {
        postToSession { discoverOnSession(name) }
    }

    fun connect(endpointId: String) {
        postToSession { connectOnSession(endpointId) }
    }

    fun confirm(endpointId: String, accepted: Boolean) {
        postToSession { confirmOnSession(endpointId, accepted) }
    }

    fun startRide() {
        postToSession { startRideOnSession() }
    }

    fun setMuted(muted: Boolean) {
        postToSession { setMutedOnSession(muted) }
    }

    fun sendAudio(frame: AudioEngine.OutgoingAudioFrame) {
        postToSession {
            val ride = rideId ?: return@postToSession
            val sender = localSenderId ?: return@postToSession
            sendAudioOnSession(
                AudioFrame(
                    rideId = ride,
                    senderId = sender,
                    generation = frame.generation,
                    sequence = frame.sequence,
                    sampleTime = frame.sampleTime,
                    payload = frame.payload.copyOf(),
                ),
            )
        }
    }

    /** Sends a validated core frame. The overload is useful to transport tests and relay code. */
    fun sendAudio(frame: AudioFrame) {
        postToSession { sendAudioOnSession(frame) }
    }

    fun leave() {
        postToSession { leaveOnSession(notify = true, graceful = true) }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        sessionHandler.post {
            sessionGeneration++
            leaveOnSession(notify = false)
            _state.value = RideState(status = "Closed")
            callbackExecutor.shutdownNow()
            scheduler.shutdownNow()
        }
    }

    private fun hostOnSession(name: String) {
        if (closed.get()) return
        resetForNewSession()
        role = Role.HOST
        localName = safeName(name)
        rideId = UUID.randomUUID().toString()
        localSenderId = HOST_SENDER_ID
        rideStarted = false
        admissionClosed = false
        updateState(phase = Phase.LOBBY, status = "Starting group")
        startHeartbeat()

        val generation = sessionGeneration
        val endpointName = advertiseName(rideId!!, localName)
        connections.startAdvertising(
            endpointName,
            SERVICE_ID,
            callbackFor(generation),
            AdvertisingOptions(Strategy.P2P_STAR),
        ).addOnFailureListener { error ->
            dispatchToSession(generation) {
                setError("Could not advertise nearby group: ${error.message ?: "unknown error"}")
            }
        }
    }

    private fun discoverOnSession(name: String) {
        if (closed.get()) return
        resetForNewSession()
        role = Role.MEMBER
        localName = safeName(name)
        localSenderId = localSenderId ?: memberHint()
        discoveryName = localName
        rideStarted = false
        admissionClosed = false
        updateState(phase = Phase.DISCOVERING, status = "Looking for nearby groups")
        startHeartbeat()

        val generation = sessionGeneration
        connections.startDiscovery(
            SERVICE_ID,
            discoveryCallbackFor(generation),
            DiscoveryOptions(Strategy.P2P_STAR),
        ).addOnFailureListener { error ->
            dispatchToSession(generation) {
                setError("Could not discover nearby groups: ${error.message ?: "unknown error"}")
            }
        }
    }

    private fun connectOnSession(endpointId: String) {
        if (role != Role.MEMBER || endpointId.isEmpty() || !discovered.containsKey(endpointId)) return
        val ride = rideId ?: discoveredRideIds[endpointId]
        val sender = localSenderId
        if (ride == null || sender == null) return
        rideId = ride
        hostEndpointId = endpointId
        connections.stopDiscovery()
        discovered.clear()
        discoveredRideIds.clear()
        updateState(phase = Phase.LOBBY, status = "Connecting to group")

        val generation = sessionGeneration
        connections.requestConnection(
            requestName(sender, localName),
            endpointId,
            callbackFor(generation),
        ).addOnFailureListener { error ->
            dispatchToSession(generation) {
                setError("Could not request connection: ${error.message ?: "unknown error"}")
            }
        }
    }

    private fun handleEndpointFound(endpointId: String, endpointInfo: DiscoveredEndpointInfo) {
        if (role != Role.MEMBER || endpointId.isEmpty()) return
        val parsed = parseEndpointName(endpointInfo.endpointName)
        if (parsed == null) return
        val existingRide = rideId
        if (existingRide != null && existingRide != parsed.first && rideStarted) return
        discovered[endpointId] = DiscoveredPeer(endpointId, parsed.second)
        discoveredRideIds[endpointId] = parsed.first
        if (_state.value.phase == Phase.RECONNECTING && existingRide == parsed.first) {
            reconnectTimeout?.cancel(false)
            reconnectTimeout = null
            hostEndpointId = endpointId
            requestResumeConnection(endpointId)
        } else {
            refreshState(status = "Nearby group found")
        }
    }

    private fun requestResumeConnection(endpointId: String) {
        val sender = localSenderId ?: return
        val generation = sessionGeneration
        connections.requestConnection(
            requestName(sender, localName),
            endpointId,
            callbackFor(generation),
        ).addOnFailureListener { error ->
            dispatchToSession(generation) {
                if (_state.value.phase == Phase.RECONNECTING) scheduleReconnect()
                else setError("Could not reconnect: ${error.message ?: "unknown error"}")
            }
        }
    }

    private fun handleConnectionInitiated(endpointId: String, info: ConnectionInfo) {
        if (closed.get() || endpointId.isEmpty()) return
        if (peers.containsKey(endpointId) || pending.containsKey(endpointId)) return

        val endpointName = info.endpointName.orEmpty()
        val parsed = parseEndpointName(endpointName)
        val autoResume = shouldAutoResume(parsed, info)
        if (role == Role.HOST && !TransportSessionPolicy.canAdmitHostPeer(
                activePeers = peers.size,
                pendingPeers = pending.size,
                reservedMembers = records.size,
                isResume = autoResume,
            )
        ) {
            connections.rejectConnection(endpointId)
            return
        }
        if (role != Role.HOST && !TransportSessionPolicy.canAdmitPeer(peers.size, pending.size)) {
            connections.rejectConnection(endpointId)
            return
        }
        if (role == Role.MEMBER && (peers.isNotEmpty() || pending.isNotEmpty())) {
            connections.rejectConnection(endpointId)
            return
        }
        if (role == Role.MEMBER && endpointId != hostEndpointId) {
            connections.rejectConnection(endpointId)
            return
        }
        if (role == Role.HOST && autoResume) {
            val claimedSender = parsed?.first
            if (claimedSender == null ||
                peers.values.any { it.senderId == claimedSender } ||
                pending.values.any { parseEndpointName(it.endpointName)?.first == claimedSender }
            ) {
                connections.rejectConnection(endpointId)
                return
            }
        }
        if (!autoResume && role == Role.HOST && admissionClosed) {
            connections.rejectConnection(endpointId)
            return
        }
        if (role != Role.HOST && role != Role.MEMBER) {
            connections.rejectConnection(endpointId)
            return
        }

        val confirmation = PendingConnection(
            endpointId = endpointId,
            info = info,
            endpointName = endpointName,
            displayName = parsed?.second ?: endpointName.ifEmpty { "Nearby rider" },
            code = info.authenticationDigits.orEmpty(),
            rawToken = info.rawAuthenticationToken?.copyOf() ?: ByteArray(0),
            autoResume = autoResume,
            generation = sessionGeneration,
        )
        pending[endpointId] = confirmation
        startPendingTimeout(confirmation)
        if (autoResume) {
            acceptPending(confirmation)
        } else {
            refreshState(status = "Confirm the connection code")
        }
    }

    private fun shouldAutoResume(parsed: ParsedEndpoint?, info: ConnectionInfo): Boolean {
        if (role == Role.MEMBER) {
            return _state.value.phase == Phase.RECONNECTING &&
                parsed?.first == rideId &&
                localResumeSecret != null &&
                info.rawAuthenticationToken?.isNotEmpty() == true
        }
        if (role != Role.HOST || !admissionClosed) return false
        val claimedSender = parsed?.first ?: return false
        return records[claimedSender]?.secret != null &&
            peers.values.none { it.senderId == claimedSender } &&
            info.rawAuthenticationToken?.isNotEmpty() == true
    }

    private fun startPendingTimeout(connection: PendingConnection) {
        val generation = connection.generation
        connection.timeout = scheduler.schedule({
            dispatchToSession(generation) {
                if (pending[connection.endpointId] === connection) {
                    removePending(connection.endpointId)
                    connections.rejectConnection(connection.endpointId)
                    refreshState(status = "Connection confirmation timed out")
                }
            }
        }, PENDING_CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private fun removePending(endpointId: String): PendingConnection? {
        val connection = pending.remove(endpointId) ?: return null
        connection.timeout?.cancel(false)
        connection.timeout = null
        return connection
    }

    private fun confirmOnSession(endpointId: String, accepted: Boolean) {
        val connection = pending[endpointId] ?: return
        if (connection.autoResume) return
        if (!accepted) {
            removePending(endpointId)
            connections.rejectConnection(endpointId)
            refreshState(status = "Connection declined")
            return
        }
        connection.userConfirmed = true
        acceptPending(connection)
    }

    private fun acceptPending(connection: PendingConnection) {
        if (connection.acceptRequested) return
        connection.acceptRequested = true
        val generation = connection.generation
        connections.acceptConnection(
            connection.endpointId,
            payloadCallbackFor(generation, connection.endpointId),
        ).addOnFailureListener { error ->
            dispatchToSession(generation) {
                removePending(connection.endpointId)
                connections.disconnectFromEndpoint(connection.endpointId)
                setError("Could not accept connection: ${error.message ?: "unknown error"}")
            }
        }
        refreshState(status = if (connection.autoResume) "Restoring group connection" else "Connecting")
    }

    private fun handleConnectionResult(endpointId: String, result: ConnectionResolution) {
        val connection = removePending(endpointId) ?: return
        if (role == Role.HOST && !TransportSessionPolicy.canAdmitHostPeer(
                activePeers = peers.size,
                pendingPeers = pending.size,
                reservedMembers = records.size,
                isResume = connection.autoResume,
            )
        ) {
            connections.disconnectFromEndpoint(endpointId)
            return
        }
        if (role != Role.HOST && !TransportSessionPolicy.canAdmitPeer(peers.size, pending.size)) {
            connections.disconnectFromEndpoint(endpointId)
            return
        }
        if (role == Role.HOST && connection.autoResume) {
            val claimedSender = parseEndpointName(connection.endpointName)?.first
            if (claimedSender == null ||
                peers.values.any { it.senderId == claimedSender } ||
                pending.values.any { parseEndpointName(it.endpointName)?.first == claimedSender }
            ) {
                connections.disconnectFromEndpoint(endpointId)
                return
            }
        }
        if (!result.status.isSuccess) {
            connections.disconnectFromEndpoint(endpointId)
            if (connection.autoResume && _state.value.phase == Phase.RECONNECTING) {
                scheduleReconnect()
            } else {
                setError("Connection failed: ${result.status.statusMessage}")
            }
            return
        }
        if (!connection.acceptRequested) {
            connections.disconnectFromEndpoint(endpointId)
            return
        }

        val peer = createPeer(endpointId, connection)
        peers[endpointId] = peer
        startAuthTimeout(peer)
        when {
            role == Role.HOST && peer.resumeCandidate -> {
                sendResumeHelloFromMemberIsNotNeeded(peer)
            }

            role == Role.HOST -> sendWelcome(peer)
            role == Role.MEMBER && connection.autoResume -> sendResumeHello(peer)
            else -> Unit
        }
        if (connection.autoResume) {
            reconnectAttempt = 0
            reconnectTimeout?.cancel(false)
            reconnectTimeout = null
            connections.stopDiscovery()
        }
        refreshState(status = "Connected; waiting for group handshake")
    }

    private fun createPeer(endpointId: String, connection: PendingConnection): Peer {
        val parsed = parseEndpointName(connection.endpointName)
        if (role == Role.HOST) {
            val candidate = parsed?.first?.let { records[it] }
            if (candidate != null && connection.autoResume) {
                return Peer(
                    endpointId = endpointId,
                    endpointName = connection.endpointName,
                    displayName = candidate.name,
                    senderId = candidate.senderId,
                    secret = candidate.secret.copyOf(),
                    muted = TransportSessionPolicy.preserveMuteOnResume(candidate.muted),
                    initialConfirmed = true,
                    resumeCandidate = true,
                    rawToken = connection.rawToken.copyOf(),
                    generation = sessionGeneration,
                )
            }
            val senderId = newMemberId()
            val secret = randomBytes(ProtocolLimits.RESUME_SECRET_BYTES)
            records[senderId] = MemberRecord(senderId, connection.displayName, secret.copyOf())
            return Peer(
                endpointId = endpointId,
                endpointName = connection.endpointName,
                displayName = connection.displayName,
                senderId = senderId,
                secret = secret,
                initialConfirmed = connection.userConfirmed,
                rawToken = connection.rawToken.copyOf(),
                generation = sessionGeneration,
                recordCreated = true,
            )
        }
        return Peer(
            endpointId = endpointId,
            endpointName = connection.endpointName,
            displayName = "Host",
            senderId = HOST_SENDER_ID,
            secret = localResumeSecret?.copyOf(),
            initialConfirmed = connection.userConfirmed,
            resumeCandidate = connection.autoResume,
            rawToken = connection.rawToken.copyOf(),
            generation = sessionGeneration,
        )
    }

    private fun startAuthTimeout(peer: Peer) {
        val generation = peer.generation
        peer.authTimeout = scheduler.schedule({
            dispatchToSession(generation) {
                if (peers[peer.endpointId] === peer && !peer.authenticated) {
                    handlePeerFailure(peer.endpointId, "authentication timed out", peer)
                }
            }
        }, AUTH_HANDSHAKE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    private fun markAuthenticated(peer: Peer) {
        if (peer.authenticated) return
        peer.authenticated = true
        peer.lastPongNanos = System.nanoTime()
        peer.authTimeout?.cancel(false)
        peer.authTimeout = null
        if (peer.writer == null) startOutgoingStream(peer)
        if (role == Role.MEMBER && peer.resumeCandidate) {
            reconnectAttempt = 0
            reconnectTimeout?.cancel(false)
            reconnectTimeout = null
            connections.stopDiscovery()
        }
    }

    private fun sendWelcome(peer: Peer) {
        val ride = rideId ?: return
        val sender = peer.senderId ?: return
        val secret = peer.secret ?: return
        peer.welcomeSent = true
        sendControl(peer, Control.Welcome(ride, sender, secret.copyOf()))
    }

    private fun sendResumeHello(peer: Peer) {
        val ride = rideId ?: return
        val sender = localSenderId ?: return
        if (localResumeSecret == null) return
        val token = peer.rawToken
        if (token.isEmpty()) return
        val nonce = randomBytes(ProtocolLimits.NONCE_BYTES)
        peer.resumeLocalNonce = nonce
        sendControl(
            peer,
            Control.ResumeHello(ride, sender, nonce, ResumeRole.MEMBER, token.copyOf()),
        )
    }

    private fun sendResumeHelloFromMemberIsNotNeeded(peer: Peer) {
        // The host waits for the resumed member's ResumeHello. Keeping this branch
        // explicit prevents an accidental host-as-member proof direction.
        peer.resumeLocalNonce = null
    }

    private fun handleControl(peer: Peer, encoded: ByteArray) {
        val control = try {
            WireCodec.decodeControl(encoded)
        } catch (_: ProtocolException) {
            disconnectPeer(peer, "invalid control message")
            return
        }
        when (control) {
            is Control.Welcome -> handleWelcome(peer, control)
            is Control.Accepted -> handleAccepted(peer, control)
            is Control.Members -> handleMembers(peer, control)
            is Control.Ready -> handleReady(peer, control)
            is Control.Mute -> handleMute(peer, control)
            is Control.Ping -> sendControl(peer, Control.Pong(control.nonce))
            is Control.Pong -> if (control.nonce == peer.lastPingNonce) peer.lastPongNanos = System.nanoTime()
            is Control.ResumeHello -> handleResumeHello(peer, control)
            is Control.Challenge -> handleChallenge(peer, control)
            is Control.Proof -> handleProof(peer, control)
            is Control.Leave -> handleLeave(peer, control)
            is Control.End -> endFromRemote(peer, control)
            is Control.Hello -> Unit
        }
    }

    private fun handleWelcome(peer: Peer, welcome: Control.Welcome) {
        val ride = rideId ?: return
        if (role != Role.MEMBER || peer.authenticated || !peer.initialConfirmed || peer.resumeCandidate ||
            welcome.rideId != ride || welcome.senderId.isEmpty() || welcome.senderId == HOST_SENDER_ID ||
            welcome.resumeSecret.size != ProtocolLimits.RESUME_SECRET_BYTES
        ) {
            disconnectPeer(peer, "unexpected welcome")
            return
        }
        localSenderId = welcome.senderId
        localResumeSecret = welcome.resumeSecret.copyOf()
        peer.secret = welcome.resumeSecret.copyOf()
        records[welcome.senderId] = MemberRecord(welcome.senderId, localName, welcome.resumeSecret.copyOf())
        markAuthenticated(peer)
        sendControl(peer, Control.Accepted(ride, welcome.senderId))
        updateMemberState()
        if (rideStarted) sendControl(peer, Control.Ready(ride, welcome.senderId))
    }

    private fun handleAccepted(peer: Peer, accepted: Control.Accepted) {
        val ride = rideId ?: return
        val sender = peer.senderId ?: return
        val initialAccepted = TransportSessionPolicy.canAcceptInitialAccepted(
            rideId = accepted.rideId,
            senderId = accepted.senderId,
            expectedRideId = ride,
            expectedSenderId = sender,
            welcomeSent = peer.welcomeSent && peer.initialConfirmed,
        )
        val resumeAccepted = TransportSessionPolicy.canAcceptResumeAccepted(
            rideId = accepted.rideId,
            senderId = accepted.senderId,
            expectedRideId = ride,
            expectedSenderId = sender,
            resumeProofVerified = peer.resumeProofVerified,
        ) && peer.resumeAwaitingAccepted
        if (role != Role.HOST || (!initialAccepted && !resumeAccepted)) {
            disconnectPeer(peer, "unexpected accepted message")
            return
        }
        markAuthenticated(peer)
        peer.resumeAwaitingAccepted = false
        records[sender]?.connected = true
        records[sender]?.muted = peer.muted
        broadcastMembers()
        updateMemberState()
    }

    private fun handleMembers(peer: Peer, members: Control.Members) {
        if (role != Role.MEMBER ||
            !TransportSessionPolicy.isVerifiedHost(peer.authenticated, peer.senderId) ||
            members.rideId != rideId || members.memberIds.isEmpty()
        ) {
            disconnectPeer(peer, "invalid member roster")
            return
        }
        knownMemberIds.clear()
        knownMemberIds += members.memberIds
        updateMemberState()
    }

    private fun handleReady(peer: Peer, ready: Control.Ready) {
        if (role != Role.HOST || !peer.authenticated ||
            ready.rideId != rideId || ready.senderId != peer.senderId
        ) {
            disconnectPeer(peer, "invalid ready message")
            return
        }
        peer.ready = true
        updateMemberState()
    }

    private fun handleMute(peer: Peer, mute: Control.Mute) {
        val ride = rideId ?: return
        if (mute.rideId != ride || !peer.authenticated) {
            disconnectPeer(peer, "invalid mute message")
            return
        }
        if (role == Role.HOST) {
            val sender = peer.senderId ?: return disconnectPeer(peer, "missing member identity")
            if (mute.senderId != sender) {
                disconnectPeer(peer, "invalid mute sender")
                return
            }
            peer.muted = mute.muted
            records[sender]?.muted = mute.muted
            for (other in peers.values) {
                if (other !== peer && other.authenticated) sendControl(other, mute)
            }
        } else {
            if (peer.senderId != HOST_SENDER_ID || mute.senderId.isEmpty() ||
                (knownMemberIds.isNotEmpty() && mute.senderId !in knownMemberIds)
            ) {
                disconnectPeer(peer, "invalid forwarded mute sender")
                return
            }
            records.getOrPut(mute.senderId) { MemberRecord(mute.senderId, mute.senderId, ByteArray(0)) }.muted = mute.muted
        }
        updateMemberState()
    }

    private fun handleResumeHello(peer: Peer, hello: Control.ResumeHello) {
        val ride = rideId ?: return
        if (role != Role.HOST || !peer.resumeCandidate || hello.rideId != ride ||
            hello.role != ResumeRole.MEMBER || hello.senderId != peer.senderId ||
            !hello.sessionToken.contentEquals(peer.rawToken) || hello.nonce.size != ProtocolLimits.NONCE_BYTES
        ) {
            disconnectPeer(peer, "invalid resume hello")
            return
        }
        val secret = peer.secret ?: return disconnectPeer(peer, "missing resume secret")
        val replayKey = "$ride:${peer.senderId}:member"
        if (!replayGuard.accept(replayKey, hello.nonce)) {
            disconnectPeer(peer, "replayed resume hello")
            return
        }
        peer.resumeRemoteNonce = hello.nonce.copyOf()
        peer.resumeLocalNonce = randomBytes(ProtocolLimits.NONCE_BYTES)
        sendControl(
            peer,
            Control.Challenge(
                rideId = ride,
                senderId = HOST_SENDER_ID,
                nonce = peer.resumeLocalNonce!!.copyOf(),
                role = ResumeRole.HOST,
                sessionToken = peer.rawToken.copyOf(),
            ),
        )
        if (secret.isEmpty()) disconnectPeer(peer, "invalid resume secret")
    }

    private fun handleChallenge(peer: Peer, challenge: Control.Challenge) {
        val ride = rideId ?: return
        val sender = localSenderId ?: return
        val secret = localResumeSecret ?: return disconnectPeer(peer, "missing resume secret")
        if (role != Role.MEMBER || !peer.resumeCandidate || challenge.rideId != ride ||
            challenge.senderId != HOST_SENDER_ID || challenge.role != ResumeRole.HOST ||
            !challenge.sessionToken.contentEquals(peer.rawToken) || challenge.nonce.size != ProtocolLimits.NONCE_BYTES
        ) {
            disconnectPeer(peer, "invalid resume challenge")
            return
        }
        peer.resumeRemoteNonce = challenge.nonce.copyOf()
        val localNonce = peer.resumeLocalNonce ?: return disconnectPeer(peer, "missing resume nonce")
        val proof = ResumeAuth.proof(
            secret = secret,
            version = ProtocolLimits.VERSION,
            rideId = ride,
            localMemberId = sender,
            remoteMemberId = HOST_SENDER_ID,
            role = ResumeRole.MEMBER,
            localNonce = localNonce,
            remoteNonce = challenge.nonce,
            sessionToken = peer.rawToken,
        )
        sendControl(
            peer,
            Control.Proof(ride, sender, localNonce.copyOf(), proof, ResumeRole.MEMBER, peer.rawToken.copyOf()),
        )
    }

    private fun handleProof(peer: Peer, proof: Control.Proof) {
        val ride = rideId ?: return
        val secret = peer.secret ?: return disconnectPeer(peer, "missing resume secret")
        if (proof.rideId != ride || !proof.sessionToken.contentEquals(peer.rawToken)) {
            disconnectPeer(peer, "invalid resume proof context")
            return
        }
        if (role == Role.HOST && proof.role == ResumeRole.MEMBER &&
            proof.senderId == peer.senderId && peer.resumeRemoteNonce != null && peer.resumeLocalNonce != null &&
            proof.nonce.contentEquals(peer.resumeRemoteNonce)
        ) {
            val clientNonce = peer.resumeRemoteNonce!!
            val hostNonce = peer.resumeLocalNonce!!
            val sender = peer.senderId ?: return disconnectPeer(peer, "missing member identity")
            val expected = ResumeAuth.proof(
                secret, ProtocolLimits.VERSION, ride, sender, HOST_SENDER_ID,
                ResumeRole.MEMBER, clientNonce, hostNonce, peer.rawToken,
            )
            if (!ResumeAuth.constantTimeEquals(expected, proof.proof)) {
                disconnectPeer(peer, "invalid member resume proof")
                return
            }
            peer.resumeProofVerified = true
            peer.resumeAwaitingAccepted = true
            val hostProof = ResumeAuth.proof(
                secret, ProtocolLimits.VERSION, ride, HOST_SENDER_ID, sender,
                ResumeRole.HOST, hostNonce, clientNonce, peer.rawToken,
            )
            sendControl(
                peer,
                Control.Proof(ride, HOST_SENDER_ID, hostNonce.copyOf(), hostProof, ResumeRole.HOST, peer.rawToken.copyOf()),
            )
            return
        }
        if (role == Role.MEMBER && proof.role == ResumeRole.HOST &&
            proof.senderId == HOST_SENDER_ID && peer.resumeRemoteNonce != null && peer.resumeLocalNonce != null &&
            proof.nonce.contentEquals(peer.resumeRemoteNonce)
        ) {
            val hostNonce = peer.resumeRemoteNonce!!
            val clientNonce = peer.resumeLocalNonce!!
            val memberSender = localSenderId ?: return disconnectPeer(peer, "missing member identity")
            val expected = ResumeAuth.proof(
                secret, ProtocolLimits.VERSION, ride, HOST_SENDER_ID, memberSender,
                ResumeRole.HOST, hostNonce, clientNonce, peer.rawToken,
            )
            if (!ResumeAuth.constantTimeEquals(expected, proof.proof)) {
                disconnectPeer(peer, "invalid host resume proof")
                return
            }
            peer.resumeProofVerified = true
            markAuthenticated(peer)
            sendControl(peer, Control.Accepted(ride, memberSender))
            if (rideStarted) sendControl(peer, Control.Ready(ride, memberSender))
            updateState(phase = Phase.ACTIVE, status = "Ride active")
            updateMemberState()
            return
        }
        disconnectPeer(peer, "unexpected resume proof")
    }

    private fun handleLeave(peer: Peer, leave: Control.Leave) {
        val sender = peer.senderId
        if (!peer.authenticated || sender == null || leave.rideId != rideId || leave.senderId != sender) {
            disconnectPeer(peer, "invalid leave message")
            return
        }
        handlePeerFailure(peer.endpointId, "peer left", peer, revokeRecord = role == Role.HOST)
    }

    private fun endFromRemote(peer: Peer, end: Control.End) {
        if (role != Role.MEMBER ||
            !TransportSessionPolicy.isVerifiedHost(peer.authenticated, peer.senderId) ||
            end.rideId != rideId
        ) {
            disconnectPeer(peer, "invalid end message")
            return
        }
        leaveOnSession(notify = true)
    }

    private fun handleIncomingAudio(peer: Peer, frame: AudioFrame) {
        val ride = rideId ?: return
        val sender = peer.senderId ?: return
        if (!TransportSessionPolicy.canAcceptInboundAudio(rideStarted, peer.authenticated)) return
        if (role == Role.HOST) {
            if (!peer.ready) return
            if (!TransportSessionPolicy.canRelay(peer.endpointId, frame, ride, sender)) {
                disconnectPeer(peer, "sender identity mismatch")
                return
            }
            listener.onAudio(frame)
            for (other in peers.values) {
                if (other !== peer && other.authenticated && other.ready &&
                    TransportSessionPolicy.shouldForward(other.senderId.orEmpty(), frame)
                ) {
                    other.writer?.enqueue(WireCodec.encodeAudioFrame(frame))
                }
            }
        } else {
            if (frame.rideId != ride || frame.senderId == localSenderId ||
                frame.senderId.isEmpty() || frame.payload.isEmpty() ||
                frame.payload.size > ProtocolLimits.MAX_AUDIO_PAYLOAD_BYTES ||
                (frame.senderId != HOST_SENDER_ID && frame.senderId !in knownMemberIds)
            ) return
            listener.onAudio(frame)
        }
    }

    private fun sendAudioOnSession(frame: AudioFrame) {
        val ride = rideId ?: return
        val sender = localSenderId ?: return
        if (!rideStarted || localMuted || frame.rideId != ride || frame.senderId != sender ||
            frame.generation < 0L || frame.sequence < 0L || frame.sampleTime < 0L ||
            frame.payload.isEmpty() || frame.payload.size > ProtocolLimits.MAX_AUDIO_PAYLOAD_BYTES
        ) return
        val encoded = try {
            WireCodec.encodeAudioFrame(frame)
        } catch (_: ProtocolException) {
            return
        }
        if (role == Role.HOST) {
            for (peer in peers.values) {
                if (peer.authenticated && peer.ready) peer.writer?.enqueue(encoded)
            }
        } else {
            peers.values.firstOrNull { it.authenticated }?.writer?.enqueue(encoded)
        }
    }

    private fun startOutgoingStream(peer: Peer) {
        peer.writer?.close()
        peer.writer = PeerStreamWriter(
            endpointId = peer.endpointId,
            generation = peer.generation,
            onFailure = { reason ->
                dispatchToSession(peer.generation) { handlePeerFailure(peer.endpointId, reason, peer) }
            },
        ).also { it.start() }
    }

    private fun startIncomingStream(peer: Peer, payload: Payload) {
        if (peer.incomingStreamActive) {
            payload.close()
            return
        }
        val stream = payload.asStream()
        if (stream == null) {
            payload.close()
            return
        }
        val input = stream.asInputStream()
        peer.incomingStreamActive = true
        peer.incomingInput = input
        peer.incomingPayload = payload
        callbackExecutor.execute {
            val decoder = AudioFrameStreamDecoder()
            val buffer = ByteArray(4096)
            try {
                while (!closed.get()) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    val frames = decoder.feed(buffer.copyOf(count))
                    for (frame in frames) {
                        dispatchToSession(peer.generation) {
                            if (peers[peer.endpointId] === peer) handleIncomingAudio(peer, frame)
                        }
                    }
                }
                decoder.finish()
            } catch (_: IOException) {
                // The peer is disconnected or its stream was cancelled.
            } catch (_: ProtocolException) {
                dispatchToSession(peer.generation) { handlePeerFailure(peer.endpointId, "invalid audio stream") }
            } finally {
                try {
                    input.close()
                    payload.close()
                } catch (_: IOException) {
                    // Nearby has already closed the payload.
                }
                dispatchToSession(peer.generation) {
                    if (peer.incomingInput === input) {
                        peer.incomingInput = null
                        peer.incomingPayload = null
                        peer.incomingStreamActive = false
                    }
                    if (peers[peer.endpointId] === peer && !closed.get()) {
                        handlePeerFailure(peer.endpointId, "audio stream ended", peer)
                    }
                }
            }
        }
    }

    private fun handlePayload(peerEndpointId: String, payload: Payload, callbackGeneration: Long) {
        val peer = peers[peerEndpointId]
        if (peer == null || peer.generation != callbackGeneration) {
            payload.close()
            return
        }
        when (payload.type) {
            Payload.Type.BYTES -> try {
                payload.asBytes()?.let { handleControl(peer, it.copyOf()) }
            } finally {
                payload.close()
            }
            Payload.Type.STREAM -> startIncomingStream(peer, payload)
            else -> payload.close()
        }
    }

    private fun sendControl(peer: Peer, control: Control) {
        if (peer.generation != sessionGeneration) return
        val encoded = try {
            WireCodec.encodeControl(control)
        } catch (_: ProtocolException) {
            return
        }
        connections.sendPayload(peer.endpointId, Payload.fromBytes(encoded))
            .addOnFailureListener { error ->
                dispatchToSession(peer.generation) {
                    handlePeerFailure(peer.endpointId, "control send failed: ${error.message ?: "unknown error"}", peer)
                }
            }
    }

    private fun handlePeerFailure(
        endpointId: String,
        reason: String,
        expectedPeer: Peer? = null,
        revokeRecord: Boolean = false,
    ) {
        val currentPeer = peers[endpointId]
        if (expectedPeer != null && currentPeer !== expectedPeer) return
        val peer = peers.remove(endpointId)
        removePending(endpointId)
        if (peer == null) return
        peer.authTimeout?.cancel(false)
        peer.authTimeout = null
        peer.closeIncomingStream()
        peer.writer?.close()
        peer.writer = null
        val sender = peer.senderId
        if (sender != null && role == Role.HOST) {
            if (revokeRecord || (!peer.authenticated && peer.recordCreated)) records.remove(sender)
            else records[sender]?.connected = false
        }
        if (sender != null) {
            try {
                listener.onPeerDisconnected(sender)
            } catch (_: Throwable) {
                // Listener failures must not stop transport cleanup.
            }
        }
        connections.disconnectFromEndpoint(endpointId)
        if (role == Role.HOST) broadcastMembers()
        if (role == Role.MEMBER && rideStarted) {
            updateState(phase = Phase.RECONNECTING, status = "Reconnecting to host")
            scheduleReconnect()
        } else {
            refreshState(status = reason)
        }
    }

    private fun scheduleReconnect() {
        if (role != Role.MEMBER || !rideStarted || closed.get() || reconnectTimeout?.isDone == false) return
        val delay = RECONNECT_DELAYS_SECONDS[reconnectAttempt.coerceAtMost(RECONNECT_DELAYS_SECONDS.lastIndex)]
        reconnectAttempt = (reconnectAttempt + 1).coerceAtMost(RECONNECT_DELAYS_SECONDS.lastIndex)
        val generation = sessionGeneration
        reconnectTimeout = scheduler.schedule({
            dispatchToSession(generation) {
                if (_state.value.phase != Phase.RECONNECTING || peers.isNotEmpty()) return@dispatchToSession
                connections.startDiscovery(
                    SERVICE_ID,
                    discoveryCallbackFor(generation),
                    DiscoveryOptions(Strategy.P2P_STAR),
                ).addOnFailureListener { dispatchToSession(generation) { scheduleReconnect() } }
                reconnectTimeout = scheduler.schedule({
                    dispatchToSession(generation) {
                        connections.stopDiscovery()
                        reconnectTimeout = null
                        scheduleReconnect()
                    }
                }, RECONNECT_ATTEMPT_WINDOW_SECONDS, TimeUnit.SECONDS)
            }
        }, delay, TimeUnit.SECONDS)
    }

    private fun startHeartbeat() {
        heartbeatFuture?.cancel(false)
        val generation = sessionGeneration
        heartbeatFuture = scheduler.scheduleWithFixedDelay({
            dispatchToSession(generation) {
                val now = System.nanoTime()
                for (peer in peers.values.toList()) {
                    if (!peer.authenticated) continue
                    if (now - peer.lastPongNanos > HEARTBEAT_TIMEOUT_NANOS) {
                        handlePeerFailure(peer.endpointId, "heartbeat timeout")
                        continue
                    }
                    peer.lastPingNonce = ThreadLocalRandom.current().nextLong()
                    sendControl(peer, Control.Ping(peer.lastPingNonce))
                }
            }
        }, HEARTBEAT_PERIOD_SECONDS, HEARTBEAT_PERIOD_SECONDS, TimeUnit.SECONDS)
    }

    private fun startPayloadReader(callbackGeneration: Long, endpointId: String): PayloadCallback =
        object : PayloadCallback() {
            override fun onPayloadReceived(endpointIdFromCallback: String, payload: Payload) {
                sessionHandler.post {
                    if (callbackGeneration != sessionGeneration || closed.get()) {
                        payload.close()
                        return@post
                    }
                    if (endpointIdFromCallback == endpointId) {
                        handlePayload(endpointId, payload, callbackGeneration)
                    } else {
                        payload.close()
                    }
                }
            }

            override fun onPayloadTransferUpdate(endpointIdFromCallback: String, update: PayloadTransferUpdate) {
                if (!TransportSessionPolicy.isFailedPayloadStatus(update.status)) return
                dispatchToSession(callbackGeneration) {
                    val peer = peers[endpointIdFromCallback]
                    if (peer == null || peer.endpointId != endpointId || peer.incomingPayload?.id != update.payloadId) return@dispatchToSession
                    peer.closeIncomingStream()
                    handlePeerFailure(peer.endpointId, "audio payload transfer failed", peer)
                }
            }
        }

    private fun payloadCallbackFor(callbackGeneration: Long, endpointId: String): PayloadCallback =
        startPayloadReader(callbackGeneration, endpointId)

    private fun callbackFor(callbackGeneration: Long): ConnectionLifecycleCallback =
        object : ConnectionLifecycleCallback() {
            override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
                dispatchToSession(callbackGeneration) {
                    handleConnectionInitiated(endpointId, connectionInfo)
                }
            }

            override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
                dispatchToSession(callbackGeneration) { handleConnectionResult(endpointId, result) }
            }

            override fun onDisconnected(endpointId: String) {
                dispatchToSession(callbackGeneration) { handlePeerFailure(endpointId, "connection lost") }
            }
        }

    private fun discoveryCallbackFor(callbackGeneration: Long): EndpointDiscoveryCallback =
        object : EndpointDiscoveryCallback() {
            override fun onEndpointFound(endpointId: String, endpointInfo: DiscoveredEndpointInfo) {
                dispatchToSession(callbackGeneration) { handleEndpointFound(endpointId, endpointInfo) }
            }

            override fun onEndpointLost(endpointId: String) {
                dispatchToSession(callbackGeneration) {
                    discovered.remove(endpointId)
                    discoveredRideIds.remove(endpointId)
                }
            }
        }

    private fun postToSession(action: () -> Unit) {
        if (closed.get()) return
        if (Looper.myLooper() == sessionHandler.looper) action() else sessionHandler.post(action)
    }

    private fun dispatchToSession(callbackGeneration: Long, action: () -> Unit) {
        sessionHandler.post {
            if (TransportSessionPolicy.isCurrentGeneration(callbackGeneration, sessionGeneration) && !closed.get()) {
                action()
            }
        }
    }

    private fun resetForNewSession() {
        sessionGeneration++
        heartbeatFuture?.cancel(false)
        heartbeatFuture = null
        reconnectTimeout?.cancel(false)
        reconnectTimeout = null
        leaveTimeout?.cancel(false)
        leaveTimeout = null
        connections.stopAdvertising()
        connections.stopDiscovery()
        connections.stopAllEndpoints()
        pending.keys.toList().forEach { removePending(it) }
        for (peer in peers.values) {
            peer.authTimeout?.cancel(false)
            peer.authTimeout = null
            peer.closeIncomingStream()
            peer.writer?.close()
        }
        peers.clear()
        records.clear()
        discovered.clear()
        discoveredRideIds.clear()
        knownMemberIds.clear()
        hostEndpointId = null
        role = Role.NONE
        rideId = null
        localSenderId = null
        localResumeSecret = null
        localMuted = false
        discoveryName = null
        reconnectAttempt = 0
        rideStarted = false
        admissionClosed = false
        replayGuard.clear()
    }

    private fun leaveOnSession(notify: Boolean, graceful: Boolean = false) {
        if (rideId == null && peers.isEmpty() && role == Role.NONE) return
        rideStarted = false
        admissionClosed = false
        val ride = rideId
        for (peer in peers.values.toList()) {
            if (ride != null && peer.authenticated) {
                if (role == Role.HOST) sendControl(peer, Control.End(ride))
                else localSenderId?.let { sendControl(peer, Control.Leave(ride, it)) }
            }
        }
        connections.stopAdvertising()
        connections.stopDiscovery()
        pending.keys.toList().forEach { endpointId ->
            connections.rejectConnection(endpointId)
            removePending(endpointId)
        }
        heartbeatFuture?.cancel(false)
        heartbeatFuture = null
        reconnectTimeout?.cancel(false)
        reconnectTimeout = null
        role = Role.NONE
        if (graceful && !closed.get()) {
            updateState(phase = Phase.IDLE, status = "Ending ride")
            val generation = sessionGeneration
            leaveTimeout?.cancel(false)
            leaveTimeout = scheduler.schedule({
                sessionHandler.post {
                    if (generation == sessionGeneration && !closed.get()) {
                        finishLeaveOnSession(notify)
                    }
                }
            }, LEAVE_GRACE_PERIOD_MS, TimeUnit.MILLISECONDS)
            return
        }
        finishLeaveOnSession(notify)
    }

    private fun finishLeaveOnSession(notify: Boolean) {
        leaveTimeout?.cancel(false)
        leaveTimeout = null
        for (peer in peers.values.toList()) {
            peer.authTimeout?.cancel(false)
            peer.authTimeout = null
            peer.closeIncomingStream()
            peer.writer?.close()
            connections.disconnectFromEndpoint(peer.endpointId)
        }
        connections.stopAdvertising()
        connections.stopDiscovery()
        connections.stopAllEndpoints()
        pending.keys.toList().forEach { removePending(it) }
        peers.clear()
        records.clear()
        discovered.clear()
        discoveredRideIds.clear()
        knownMemberIds.clear()
        replayGuard.clear()
        role = Role.NONE
        rideId = null
        localSenderId = null
        localResumeSecret = null
        hostEndpointId = null
        localMuted = false
        discoveryName = null
        reconnectAttempt = 0
        rideStarted = false
        admissionClosed = false
        sessionGeneration++
        _state.value = RideState(status = "Ready")
        if (notify) {
            try {
                listener.onSessionEnded()
            } catch (_: Throwable) {
                // Listener failures must not prevent teardown.
            }
        }
    }

    private fun startRideOnSession() {
        if (role == Role.NONE || rideId == null) return
        if (pending.isNotEmpty()) {
            setError("Finish connection confirmations before starting")
            return
        }
        if (peers.any { !it.value.authenticated }) {
            setError("Waiting for connection authentication")
            return
        }
        if (role == Role.MEMBER && peers.values.none { it.authenticated && it.senderId == HOST_SENDER_ID }) {
            setError("Connect to the host before starting")
            return
        }
        rideStarted = true
        admissionClosed = role == Role.HOST
        connections.stopDiscovery()
        updateState(phase = Phase.ACTIVE, status = "Ride active")
        if (role == Role.MEMBER) {
            peers.values.firstOrNull { it.authenticated }?.let { peer ->
                localSenderId?.let { sendControl(peer, Control.Ready(rideId!!, it)) }
            }
        } else {
            broadcastMembers()
        }
    }

    private fun setMutedOnSession(value: Boolean) {
        localMuted = value
        updateMemberState()
        val ride = rideId ?: return
        val sender = localSenderId ?: return
        val mute = Control.Mute(ride, sender, value)
        if (role == Role.HOST) {
            for (peer in peers.values) if (peer.authenticated) sendControl(peer, mute)
        } else {
            peers.values.firstOrNull { it.authenticated }?.let { sendControl(it, mute) }
        }
    }

    private fun broadcastMembers() {
        val ride = rideId ?: return
        val ids = ArrayList<String>()
        ids += HOST_SENDER_ID
        ids += records.values
            .filter { it.senderId != HOST_SENDER_ID && it.connected }
            .map { it.senderId }
            .take(ProtocolLimits.MAX_MEMBERS - 1)
        if (ids.isEmpty()) return
        val members = Control.Members(ride, ids)
        for (peer in peers.values) if (peer.authenticated) sendControl(peer, members)
    }

    private fun updateMemberState() {
        refreshState()
    }

    private val knownMemberIds = LinkedHashSet<String>()

    private fun refreshState(status: String = _state.value.status, phase: Phase = currentPhase()) {
        val ride = rideId
        val sender = localSenderId
        val memberList = if (role == Role.HOST) {
            buildList {
                add(Member(HOST_SENDER_ID, localName, connected = true, muted = localMuted))
                records.values.forEach { record ->
                    add(Member(record.senderId, record.name, record.connected, record.muted))
                }
            }
        } else {
            val ids = if (knownMemberIds.isEmpty()) listOfNotNull(sender) else knownMemberIds.toList()
            ids.map { id ->
                val own = id == sender
                val record = records[id]
                Member(
                    id,
                    record?.name ?: if (id == HOST_SENDER_ID) "Host" else id,
                    connected = own || id in knownMemberIds,
                    muted = if (own) localMuted else record?.muted ?: false,
                )
            }
        }
        _state.value = RideState(
            isHost = role == Role.HOST,
            phase = phase,
            rideId = ride,
            senderId = sender,
            discovered = discovered.values.toList(),
            members = memberList,
            pending = pending.values.map { Confirmation(it.endpointId, it.displayName, it.code) },
            muted = localMuted,
            error = _state.value.error,
            status = status,
        )
    }

    private fun currentPhase(): Phase = TransportSessionPolicy.derivePhase(
        rideSelected = rideId != null,
        discovering = _state.value.phase == Phase.DISCOVERING || discovered.isNotEmpty(),
        selectedHost = role == Role.MEMBER && hostEndpointId != null,
        rideStarted = rideStarted,
        peerAuthenticated = peers.values.any { it.authenticated },
        reconnecting = _state.value.phase == Phase.RECONNECTING,
    )

    private fun updateState(phase: Phase, status: String) {
        refreshState(status = status, phase = phase)
    }

    private fun setError(message: String) {
        val current = _state.value
        _state.value = current.copy(error = message, status = message)
    }

    private fun disconnectPeer(peer: Peer, reason: String) {
        connections.disconnectFromEndpoint(peer.endpointId)
        handlePeerFailure(peer.endpointId, reason, peer)
    }

    private fun safeName(name: String): String {
        val cleaned = name.trim().replace('|', ' ')
        return if (cleaned.isEmpty()) "Rider" else cleaned.take(MAX_NAME_CHARS)
    }

    private fun advertiseName(ride: String, name: String): String = "$ride|${safeName(name)}"

    private fun requestName(sender: String, name: String): String = "$sender|${safeName(name)}"

    private fun parseEndpointName(value: String): ParsedEndpoint? {
        val split = value.indexOf('|')
        if (split <= 0 || split == value.lastIndex) return null
        val first = value.substring(0, split)
        val second = value.substring(split + 1).take(MAX_NAME_CHARS)
        if (first.isEmpty() || second.isEmpty()) return null
        return ParsedEndpoint(first, second)
    }

    private fun memberHint(): String = "member-${UUID.randomUUID()}"

    private fun newMemberId(): String = "member-${UUID.randomUUID()}"

    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also(secureRandom::nextBytes)

    private inner class PeerStreamWriter(
        private val endpointId: String,
        private val generation: Long,
        private val onFailure: (String) -> Unit,
    ) : Closeable {
        private val input = PipedInputStream(STREAM_PIPE_BYTES)
        private val output = PipedOutputStream(input)
        private val queue = ArrayBlockingQueue<QueuedAudioFrame>(STREAM_QUEUE_FRAMES)
        private val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "RideChatNearbyWriter").apply { isDaemon = true }
        }
        private val active = AtomicBoolean(true)
        private var payload: Payload? = null
        @Volatile private var writing = false
        @Volatile private var writeStartedNanos = 0L
        private var watchdog: ScheduledFuture<*>? = null

        fun start() {
            payload = Payload.fromStream(input)
            connections.sendPayload(endpointId, payload!!).addOnFailureListener { error ->
                onFailure("audio stream send failed: ${error.message ?: "unknown error"}")
            }
            executor.execute {
                try {
                    while (active.get()) {
                        val queued = queue.take()
                        if (System.nanoTime() - queued.enqueuedNanos > STREAM_FRAME_MAX_AGE_NANOS) continue
                        writeStartedNanos = System.nanoTime()
                        writing = true
                        output.write(queued.bytes)
                        output.flush()
                        writing = false
                        writeStartedNanos = 0L
                    }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                } catch (_: IOException) {
                    if (active.get()) onFailure("audio stream writer stopped")
                } finally {
                    writing = false
                    writeStartedNanos = 0L
                }
            }
            watchdog = scheduler.scheduleWithFixedDelay({
                if (active.get() && writing && System.nanoTime() - writeStartedNanos > STREAM_STALL_NANOS) {
                    onFailure("audio stream writer stalled")
                    close()
                }
            }, STREAM_WATCHDOG_PERIOD_MS, STREAM_WATCHDOG_PERIOD_MS, TimeUnit.MILLISECONDS)
        }

        fun enqueue(frame: ByteArray) {
            if (!active.get()) return
            val now = System.nanoTime()
            while (queue.peek()?.let { now - it.enqueuedNanos > STREAM_FRAME_MAX_AGE_NANOS } == true) {
                queue.poll()
            }
            val queued = QueuedAudioFrame(frame.copyOf(), now)
            if (!queue.offer(queued)) {
                queue.poll()
                queue.offer(queued)
            }
        }

        override fun close() {
            if (!active.compareAndSet(true, false)) return
            watchdog?.cancel(false)
            watchdog = null
            queue.clear()
            try {
                output.close()
                input.close()
                payload?.close()
            } catch (_: IOException) {
                // Closing an already failed stream is harmless.
            }
            executor.shutdownNow()
        }
    }

    private data class ParsedEndpoint(val first: String, val second: String)

    private data class QueuedAudioFrame(
        val bytes: ByteArray,
        val enqueuedNanos: Long,
    )

    private class PendingConnection(
        val endpointId: String,
        val info: ConnectionInfo,
        val endpointName: String,
        val displayName: String,
        val code: String,
        val rawToken: ByteArray,
        val autoResume: Boolean,
        val generation: Long,
        var userConfirmed: Boolean = autoResume,
        var acceptRequested: Boolean = false,
        var timeout: ScheduledFuture<*>? = null,
    )

    private class Peer(
        val endpointId: String,
        val endpointName: String,
        val displayName: String,
        var senderId: String?,
        var secret: ByteArray?,
        var muted: Boolean = false,
        var ready: Boolean = false,
        val initialConfirmed: Boolean,
        val resumeCandidate: Boolean = false,
        val rawToken: ByteArray,
        val generation: Long,
        val recordCreated: Boolean = false,
        var authenticated: Boolean = false,
        var welcomeSent: Boolean = false,
        var resumeProofVerified: Boolean = false,
        var resumeAwaitingAccepted: Boolean = false,
        var resumeLocalNonce: ByteArray? = null,
        var resumeRemoteNonce: ByteArray? = null,
        var lastPongNanos: Long = System.nanoTime(),
        var lastPingNonce: Long = 0L,
        var authTimeout: ScheduledFuture<*>? = null,
        var incomingStreamActive: Boolean = false,
        var incomingInput: InputStream? = null,
        var incomingPayload: Payload? = null,
        var writer: PeerStreamWriter? = null,
    ) {
        fun closeIncomingStream() {
            incomingStreamActive = false
            val input = incomingInput
            val payload = incomingPayload
            incomingInput = null
            incomingPayload = null
            try {
                input?.close()
            } catch (_: IOException) {
                // The stream may already be closed by Nearby.
            }
            payload?.close()
        }
    }

    private class MemberRecord(
        val senderId: String,
        val name: String,
        val secret: ByteArray,
        var connected: Boolean = true,
        var muted: Boolean = false,
    )

    private enum class Role { NONE, HOST, MEMBER }

    companion object {
        private const val SERVICE_ID = "com.ridechat.app.nearby"
        private const val HOST_SENDER_ID = "host"
        private const val MAX_NAME_CHARS = 32
        private const val STREAM_QUEUE_FRAMES = 5
        private const val STREAM_PIPE_BYTES = 256
        private const val STREAM_FRAME_MAX_AGE_NANOS = 100_000_000L
        private const val STREAM_STALL_NANOS = 300_000_000L
        private const val STREAM_WATCHDOG_PERIOD_MS = 100L
        private const val AUTH_HANDSHAKE_TIMEOUT_MS = 5_000L
        private const val PENDING_CONNECTION_TIMEOUT_SECONDS = 60L
        private const val LEAVE_GRACE_PERIOD_MS = 250L
        private const val HEARTBEAT_PERIOD_SECONDS = 1L
        private const val HEARTBEAT_TIMEOUT_NANOS = 3_500_000_000L
        private const val RECONNECT_ATTEMPT_WINDOW_SECONDS = 4L
        private val RECONNECT_DELAYS_SECONDS = longArrayOf(1L, 2L, 4L, 8L)
    }
}
