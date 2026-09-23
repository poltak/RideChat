package com.ridechat.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.ridechat.app.MainActivity
import com.ridechat.app.R
import com.ridechat.audio.AudioEngine
import com.ridechat.core.AudioFrame
import com.ridechat.transport.NearbyRide
import com.ridechat.transport.RideState
import com.ridechat.transport.Phase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Owns the ride session and survives Activity rotation and screen locking. */
class RideService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(ServiceState())
    private var ride: NearbyRide? = null
    private var rideStateJob: Job? = null
    private var foregroundStarted = false
    private var audio: AudioEngine? = null

    val state: StateFlow<ServiceState> = _state.asStateFlow()

    private val audioListener = object : AudioEngine.Listener {
        override fun onLocalFrame(frame: AudioEngine.OutgoingAudioFrame) {
            serviceScope.launch {
                val currentRide = ride ?: return@launch
                if (_state.value.ride.phase != Phase.ACTIVE) return@launch
                try {
                    currentRide.sendAudio(frame)
                } catch (error: Throwable) {
                    reportError(error)
                }
            }
        }

        override fun onStateChanged(state: AudioEngine.State) {
            serviceScope.launch {
                _state.update { it.copy(audioState = state) }
                updateNotification()
            }
        }

        override fun onRouteChanged(route: AudioEngine.RouteInfo?) {
            serviceScope.launch { _state.update { it.copy(route = route) } }
        }

        override fun onMicrophoneSilenced(silenced: Boolean) {
            serviceScope.launch { _state.update { it.copy(microphoneSilenced = silenced) } }
        }

        override fun onError(error: Throwable) {
            reportError(error)
        }
    }

    private val rideListener = object : NearbyRide.Listener {
        override fun onAudio(frame: AudioFrame) {
            audio?.receive(
                AudioEngine.RemoteAudioFrame(
                    senderId = frame.senderId,
                    generation = frame.generation,
                    sequence = frame.sequence,
                    sampleTime = frame.sampleTime,
                    payload = frame.payload.copyOf(),
                ),
            )
        }

        override fun onPeerDisconnected(senderId: String) {
            audio?.clearRemote(senderId)
        }

        override fun onSessionEnded() {
            serviceScope.launch {
                audio?.stop()
                audio?.resetRemoteAudio()
                stopForegroundIfNeeded()
                _state.update {
                    it.copy(
                        ride = RideState(),
                        audioState = AudioEngine.State.IDLE,
                        route = null,
                        microphoneSilenced = false,
                        error = null,
                        status = "Ride ended",
                    )
                }
                stopSelf()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            audio = AudioEngine(applicationContext, audioListener)
        } catch (error: Throwable) {
            reportError(error)
        }
        ensureRide()
    }

    override fun onBind(intent: Intent?): IBinder = LocalBinder()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_MUTE -> setMuted(!_state.value.ride.muted)
            ACTION_END -> endRide()
            ACTION_START -> startRide(fromForegroundStart = true)
        }
        return START_NOT_STICKY
    }

    fun host(name: String) {
        prepareForNewSession()
        ensureRide()?.host(name)
    }

    fun discover(name: String) {
        prepareForNewSession()
        ensureRide()?.discover(name)
    }

    fun connect(endpointId: String) {
        clearServiceError()
        ensureRide()?.connect(endpointId)
    }

    fun confirm(endpointId: String, accepted: Boolean) {
        clearServiceError()
        ensureRide()?.confirm(endpointId, accepted)
    }

    fun startRide() {
        startRide(fromForegroundStart = false)
    }

    private fun startRide(fromForegroundStart: Boolean) {
        clearServiceError()
        val currentRide = ensureRide() ?: run {
            if (fromForegroundStart) stopSelf()
            return
        }
        val transportPhase = currentRide.state.value.phase
        if (transportPhase == Phase.ACTIVE || transportPhase == Phase.RECONNECTING) {
            // A double tap can deliver two ACTION_START intents. Keep the
            // running service alive when the first intent already succeeded.
            if (foregroundStarted) return
            if (!startForegroundNow() && fromForegroundStart) stopSelf()
            return
        }
        if (transportPhase != Phase.LOBBY) {
            reportError(IllegalStateException("Join or create a group before starting the ride"))
            if (fromForegroundStart) stopSelf()
            return
        }
        if (!startForegroundNow()) {
            if (fromForegroundStart) stopSelf()
            return
        }
        val engine = ensureAudio() ?: run {
            stopForegroundIfNeeded()
            if (fromForegroundStart) stopSelf()
            return
        }
        try {
            currentRide.startRide()
            val startedState = currentRide.state.value
            if (startedState.phase != Phase.ACTIVE) {
                reportError(
                    IllegalStateException(
                        startedState.error ?: "The group is not ready for a ride",
                    ),
                )
                engine.stop()
                stopForegroundIfNeeded()
                if (fromForegroundStart) stopSelf()
                return
            }
            engine.setMuted(startedState.muted)
            engine.start()
        } catch (error: Throwable) {
            reportError(error)
            engine.stop()
            stopForegroundIfNeeded()
            if (fromForegroundStart) stopSelf()
        }
    }

    fun setMuted(muted: Boolean) {
        clearServiceError()
        _state.update { it.copy(ride = it.ride.copy(muted = muted)) }
        ensureRide()?.setMuted(muted)
        audio?.setMuted(muted)
        updateNotification()
    }

    fun endRide() {
        val currentRide = ride
        val transportPhase = currentRide?.state?.value?.phase
        var stopAfterLeave = currentRide == null || transportPhase == null || transportPhase == Phase.IDLE
        audio?.stop()
        audio?.resetRemoteAudio()
        if (!stopAfterLeave) {
            try {
                currentRide?.leave()
            } catch (error: Throwable) {
                reportError(error)
                stopAfterLeave = true
            }
        }
        stopForegroundIfNeeded()
        _state.update {
            it.copy(
                ride = RideState(),
                audioState = AudioEngine.State.IDLE,
                route = null,
                microphoneSilenced = false,
                status = "Ride ended",
                error = null,
            )
        }
        // NearbyRide.leave() keeps its control channel alive for a short grace
        // period. onSessionEnded stops this service after the final End/Leave
        // control has had time to leave the phone.
        if (stopAfterLeave) stopSelf()
    }

    override fun onDestroy() {
        rideStateJob?.cancel()
        serviceScope.cancel()
        audio?.close()
        audio = null
        try {
            ride?.close()
        } catch (_: Throwable) {
            // The transport is already being torn down.
        }
        ride = null
        stopForegroundIfNeeded()
        super.onDestroy()
    }

    private fun ensureRide(): NearbyRide? {
        ride?.let { return it }
        return try {
            NearbyRide(applicationContext, rideListener).also { newRide ->
                ride = newRide
                rideStateJob = serviceScope.launch {
                    newRide.state.collectLatest { next ->
                        val previousPhase = _state.value.ride.phase
                        if (next.phase == Phase.RECONNECTING && previousPhase != Phase.RECONNECTING) {
                            // Drop queued voice from the old host path. Keep local
                            // capture running so a successful resume can continue.
                            audio?.resetRemoteAudio()
                        }
                        if (next.phase == Phase.IDLE) {
                            audio?.stop()
                            audio?.resetRemoteAudio()
                            stopForegroundIfNeeded()
                        }
                        _state.update { current ->
                            current.copy(
                                ride = next,
                                error = next.error?.takeIf { it == next.status },
                                status = next.status,
                            )
                        }
                        updateNotification()
                    }
                }
            }
        } catch (error: Throwable) {
            reportError(error)
            null
        }
    }

    private fun reportError(error: Throwable) {
        val message = error.message?.takeIf { it.isNotBlank() } ?: "Ride service error"
        val action = {
            _state.update { it.copy(error = message.take(180), status = "Action needs attention") }
            updateNotification()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) action() else serviceScope.launch { action() }
    }

    private fun clearServiceError() {
        _state.update { it.copy(error = null) }
    }

    /** Stop local audio before the transport replaces its session. */
    private fun prepareForNewSession() {
        audio?.stop()
        audio?.resetRemoteAudio()
        stopForegroundIfNeeded()
        _state.update {
            it.copy(
                ride = RideState(),
                audioState = AudioEngine.State.IDLE,
                route = null,
                microphoneSilenced = false,
                error = null,
                status = "Ready",
            )
        }
    }

    private fun ensureAudio(): AudioEngine? {
        audio?.let { return it }
        return try {
            AudioEngine(applicationContext, audioListener).also { audio = it }
        } catch (error: Throwable) {
            reportError(IllegalStateException("A communication headset and microphone permission are required", error))
            null
        }
    }

    private fun startForegroundNow(): Boolean {
        return try {
            createNotificationChannel()
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            foregroundStarted = true
            true
        } catch (error: SecurityException) {
            reportError(IllegalStateException("Microphone and nearby permissions are required before Start ride", error))
            false
        } catch (error: RuntimeException) {
            reportError(error)
            false
        }
    }

    private fun stopForegroundIfNeeded() {
        if (!foregroundStarted) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        foregroundStarted = false
    }

    private fun updateNotification() {
        if (!foregroundStarted) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notification_channel_description)
            },
        )
    }

    private fun buildNotification(): Notification {
        val ride = _state.value.ride
        val muteIntent = PendingIntent.getService(
            this,
            REQUEST_MUTE,
            Intent(this, RideService::class.java).setAction(ACTION_MUTE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val endIntent = PendingIntent.getService(
            this,
            REQUEST_END,
            Intent(this, RideService::class.java).setAction(ACTION_END),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_voice)
            .setContentTitle("RideChat ride active")
            .setContentText(notificationText(ride))
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    REQUEST_OPEN,
                    Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .addAction(R.drawable.ic_stat_voice, if (ride.muted) "Unmute" else "Mute", muteIntent)
            .addAction(R.drawable.ic_stat_voice, "End ride", endIntent)
            .build()
    }

    private fun notificationText(ride: RideState): String {
        val count = ride.members.size
        return when {
            _state.value.microphoneSilenced -> "Microphone privacy switch · $count riders"
            _state.value.audioState == AudioEngine.State.HEADSET_MISSING -> "Connect communication headset · $count riders"
            _state.value.audioState == AudioEngine.State.AUDIO_INTERRUPTED -> "Audio interrupted · $count riders"
            ride.muted -> "Muted · $count riders"
            else -> "Live · $count riders"
        }
    }

    inner class LocalBinder : android.os.Binder() {
        fun service(): RideService = this@RideService
    }

    data class ServiceState(
        val ride: RideState = RideState(),
        val audioState: AudioEngine.State = AudioEngine.State.IDLE,
        val route: AudioEngine.RouteInfo? = null,
        val microphoneSilenced: Boolean = false,
        val error: String? = null,
        val status: String = "Ready",
    )

    companion object {
        const val ACTION_MUTE = "com.ridechat.action.MUTE"
        const val ACTION_END = "com.ridechat.action.END"
        const val ACTION_START = "com.ridechat.action.START"
        private const val CHANNEL_ID = "ridechat-ride"
        private const val NOTIFICATION_ID = 4101
        private const val REQUEST_MUTE = 4102
        private const val REQUEST_END = 4103
        private const val REQUEST_OPEN = 4104
    }
}
