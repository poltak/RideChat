package com.ridechat.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import com.ridechat.core.AudioFrame
import com.ridechat.core.PerSpeakerJitterBuffer
import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

/**
 * Audio capture, Opus encoding, and per-speaker playback for a ride session.
 *
 * The engine deliberately accepts only a communication headset route. It does not
 * fall back to the phone microphone, earpiece, or speaker when the headset goes
 * away. All blocking audio work is done on dedicated workers.
 */
class AudioEngine(
    context: Context,
    private val listener: Listener,
    private val config: Config = Config(),
) : Closeable {

    private val appContext = context.applicationContext
    private val audioManager =
        requireNotNull(appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager) {
            "AudioManager is unavailable"
        }

    private val closed = AtomicBoolean(false)
    private val controlExecutor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "RideChatAudioControl").apply { isDaemon = true }
        }
    private val captureExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "RideChatAudioCapture").apply { isDaemon = true }
        }
    private val playbackExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "RideChatAudioPlayback").apply { isDaemon = true }
        }
    private val callbackThread = HandlerThread("RideChatAudioCallbacks").apply { start() }
    private val callbackHandler = Handler(callbackThread.looper)

    private val incomingFrames = ArrayBlockingQueue<RemoteAudioFrame>(config.maxIncomingFrames)
    private val speakerLock = Any()
    private val resourceLock = Any()
    private val routeListener = object : AudioRecord.OnRoutingChangedListener {
        override fun onRoutingChanged(audioRecord: AudioRecord) {
            postControl {
                // A released recorder can deliver a late callback after a new
                // recorder has been installed. Ignore that stale event. A
                // deliberately stopped recorder also reports a transient null
                // route when muted or silenced; output routing remains active.
                if (this@AudioEngine.audioRecord !== audioRecord || muted || captureSilenced) {
                    return@postControl
                }
                handleRouteEvent(audioRecord.routedDevice)
            }
        }
    }
    private val trackRouteListener = object : AudioTrack.OnRoutingChangedListener {
        override fun onRoutingChanged(audioTrack: AudioTrack) {
            postControl {
                if (this@AudioEngine.audioTrack !== audioTrack) {
                    return@postControl
                }
                handleRouteEvent(audioTrack.routedDevice)
            }
        }
    }
    private val communicationDeviceListener =
        AudioManager.OnCommunicationDeviceChangedListener { device ->
            postControl { handleRouteEvent(device) }
        }
    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
            val sessionId = audioRecord?.audioSessionId ?: -1
            val silenced = sessionId >= 0 && configs.any {
                it.clientAudioSessionId == sessionId && it.isClientSilenced
            }
            postControl { handleCaptureSilenced(silenced) }
        }
    }
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        postControl { handleFocusChange(change) }
    }

    @Volatile
    private var audioRecord: AudioRecord? = null
    @Volatile
    private var audioTrack: AudioTrack? = null
    private var captureFuture: java.util.concurrent.Future<*>? = null
    private var playbackFuture: java.util.concurrent.Future<*>? = null
    @Volatile
    private var captureLoop = false
    @Volatile
    private var playbackLoop = false
    @Volatile
    private var sessionRunning = false
    @Volatile
    private var routeVerified = false
    @Volatile
    private var routeVerificationPending = false
    @Volatile
    private var captureSilenced = false
    @Volatile
    private var muted = false
    @Volatile
    private var focusGranted = false
    private var modeChanged = false
    private var previousAudioMode = AudioManager.MODE_NORMAL
    private var focusRequest: AudioFocusRequest? = null
    private var routeRetry: java.util.concurrent.ScheduledFuture<*>? = null
    private var desiredRoute: RouteKey? = null
    private var routeVerificationAttempts = 0
    private var streamGeneration = 0L
    private var sequence = 0L
    private var sampleTime = 0L
    private var lastState = State.IDLE

    init {
        audioManager.addOnCommunicationDeviceChangedListener(controlExecutor, communicationDeviceListener)
    }

    /** Start the session. This method only schedules work and never blocks the caller. */
    fun start() {
        postControl { startOnControl() }
    }

    /** Stop the session and release its active audio resources. */
    fun stop() {
        postControl { stopOnControl() }
    }

    /** Mute controls local capture only. Incoming audio continues while muted. */
    fun setMuted(value: Boolean) {
        postControl { setMutedOnControl(value) }
    }

    /**
     * Queue one remote Opus frame. The caller owns its frame after this call; the
     * payload is copied so a transport buffer can be reused immediately.
     */
    fun receive(frame: RemoteAudioFrame) {
        if (closed.get() || !sessionRunning || !routeVerified || !playbackLoop ||
            frame.payload.size > config.maxOpusPayloadBytes
        ) {
            return
        }
        val copy = frame.copy(payload = frame.payload.copyOf())
        if (!incomingFrames.offer(copy)) {
            incomingFrames.poll()
            incomingFrames.offer(copy)
        }
    }

    /** Remove one peer's queued and decoded audio after a disconnect or stream reset. */
    fun clearRemote(senderId: String) {
        if (senderId.isEmpty()) {
            return
        }
        incomingFrames.removeIf { it.senderId == senderId }
        synchronized(speakerLock) {
            speakerBuffers.remove(senderId)?.close()
        }
    }

    /** Flush all remote audio without changing local capture, mute, or route state. */
    fun resetRemoteAudio() {
        incomingFrames.clear()
        resetSpeakerBuffers()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        try {
            controlExecutor.execute {
                stopOnControl()
                audioManager.removeOnCommunicationDeviceChangedListener(communicationDeviceListener)
            }
            controlExecutor.shutdown()
        } catch (_: RuntimeException) {
            releaseAudioResources()
        }
        captureExecutor.shutdownNow()
        playbackExecutor.shutdownNow()
        callbackThread.quitSafely()
    }

    private fun postControl(action: () -> Unit) {
        if (closed.get()) {
            return
        }
        try {
            controlExecutor.execute {
                try {
                    action()
                } catch (error: Throwable) {
                    notifyError(error)
                }
            }
        } catch (_: RuntimeException) {
            // The engine is already stopping.
        }
    }

    private fun startOnControl() {
        if (sessionRunning || closed.get()) {
            return
        }
        sessionRunning = true
        setState(State.STARTING)
        previousAudioMode = audioManager.mode
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        modeChanged = true
        if (!requestFocus()) {
            sessionRunning = false
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            focusRequest = null
            focusGranted = false
            if (modeChanged) {
                audioManager.mode = previousAudioMode
                modeChanged = false
            }
            setState(State.AUDIO_INTERRUPTED)
            return
        }
        audioManager.registerAudioRecordingCallback(recordingCallback, callbackHandler)
        desiredRoute = chooseInitialRoute()
        if (desiredRoute == null) {
            setState(State.HEADSET_MISSING)
            scheduleRouteRetry()
            return
        }
        openStreamsIfPossible()
    }

    private fun stopOnControl() {
        if (!sessionRunning && lastState == State.IDLE) {
            return
        }
        setState(State.STOPPING)
        sessionRunning = false
        routeRetry?.cancel(false)
        routeRetry = null
        audioManager.unregisterAudioRecordingCallback(recordingCallback)
        releaseAudioResources()
        incomingFrames.clear()
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
        focusGranted = false
        if (modeChanged) {
            audioManager.mode = previousAudioMode
            modeChanged = false
        }
        audioManager.clearCommunicationDevice()
        desiredRoute = null
        routeVerified = false
        setState(State.IDLE)
    }

    private fun setMutedOnControl(value: Boolean) {
        if (muted == value) {
            return
        }
        muted = value
        if (!sessionRunning) {
            setState(if (muted) State.MUTED else State.IDLE)
            return
        }
        if (muted) {
            stopCapture()
            setState(State.MUTED)
        } else if (routeVerified && focusGranted && !captureSilenced) {
            val recordStopped = audioRecord?.recordingState != AudioRecord.RECORDSTATE_RECORDING
            if (recordStopped) {
                // Recreate the recorder before unmuting. This drops any startup
                // samples buffered while the local microphone was muted.
                releaseAudioResources()
                incomingFrames.clear()
                openStreamsIfPossible()
            } else {
                startCaptureWhenReady()
                setState(State.ACTIVE)
            }
        }
    }

    private fun requestFocus(): Boolean {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener(focusListener, callbackHandler)
            .build()
        focusRequest = request
        return try {
            val result = audioManager.requestAudioFocus(request)
            focusGranted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            focusGranted
        } catch (error: RuntimeException) {
            notifyError(error)
            focusGranted = false
            false
        }
    }

    private fun handleFocusChange(change: Int) {
        if (!sessionRunning) {
            return
        }
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                focusGranted = true
                if (desiredRoute != null) {
                    openStreamsIfPossible()
                }
            }

            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                focusGranted = false
                suspendAudio(State.AUDIO_INTERRUPTED)
            }
        }
    }

    private fun handleCaptureSilenced(silenced: Boolean) {
        if (captureSilenced == silenced) {
            return
        }
        captureSilenced = silenced
        listener.onMicrophoneSilenced(silenced)
        if (!sessionRunning) {
            return
        }
        if (silenced) {
            stopCapture()
            setState(State.AUDIO_INTERRUPTED)
        } else if (routeVerified && focusGranted && !muted) {
            if (audioRecord?.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                releaseAudioResources()
                incomingFrames.clear()
                openStreamsIfPossible()
            } else {
                startCaptureWhenReady()
                setState(State.ACTIVE)
            }
        }
    }

    private fun handleRouteEvent(device: AudioDeviceInfo?) {
        if (!sessionRunning) {
            return
        }
        if (desiredRoute == null) {
            desiredRoute = chooseInitialRoute()
            if (desiredRoute == null) {
                setState(State.HEADSET_MISSING)
                scheduleRouteRetry()
            } else {
                openStreamsIfPossible()
            }
            return
        }
        if (routeVerificationPending) {
            return
        }
        if (device == null || !isAllowedHeadset(device)) {
            if (routeVerified) {
                suspendAudio(State.HEADSET_MISSING)
                scheduleRouteRetry()
            }
            return
        }
        if (routeVerified && !desiredRoute!!.matches(device)) {
            suspendAudio(State.HEADSET_MISSING)
            scheduleRouteRetry()
            return
        }
        if (!routeVerified) {
            openStreamsIfPossible()
        }
    }

    private fun chooseInitialRoute(): RouteKey? {
        val available = audioManager.availableCommunicationDevices
            .filter(::isAllowedOutput)
        if (available.isEmpty()) {
            return null
        }
        val preferred = config.preferredRouteAddress?.let { address ->
            available.firstOrNull { it.address == address }
        }
        val current = audioManager.communicationDevice.takeIf(::isAllowedOutput)
        val selected = preferred ?: current ?: available.minByOrNull { routePriority(it) }
        if (selected == null) {
            return null
        }
        val selectedRoute = RouteKey.from(selected)
        return try {
            if (!audioManager.setCommunicationDevice(selected)) {
                null
            } else {
                listener.onRouteChanged(selectedRoute.toInfo())
                selectedRoute
            }
        } catch (error: RuntimeException) {
            notifyError(error)
            null
        }
    }

    private fun findKnownRoute(): AudioDeviceInfo? {
        val expected = desiredRoute ?: return null
        return audioManager.availableCommunicationDevices
            .firstOrNull { isAllowedOutput(it) && expected.matches(it) }
    }

    private fun openStreamsIfPossible() {
        if (!sessionRunning || !focusGranted || routeVerified || routeVerificationPending) {
            return
        }
        if (desiredRoute == null) {
            desiredRoute = chooseInitialRoute()
            if (desiredRoute == null) {
                setState(State.HEADSET_MISSING)
                scheduleRouteRetry()
                return
            }
        }
        val route = findKnownRoute()
        if (route == null) {
            setState(State.HEADSET_MISSING)
            scheduleRouteRetry()
            return
        }
        try {
            if (!audioManager.setCommunicationDevice(route)) {
                setState(State.HEADSET_MISSING)
                scheduleRouteRetry()
                return
            }
            if (!createAudioStreams(route)) {
                setState(State.HEADSET_MISSING)
                scheduleRouteRetry()
                return
            }
            // AudioRecord and AudioTrack report their actual route only after they
            // are active. No captured samples or output are allowed before both
            // devices match the known communication headset.
            val record = audioRecord ?: return
            val track = audioTrack ?: return
            record.startRecording()
            track.play()
            beginRouteVerification(route, record, track)
        } catch (error: Throwable) {
            notifyError(error)
            suspendAudio(State.HEADSET_MISSING)
            scheduleRouteRetry()
        }
    }

    private fun createAudioStreams(route: AudioDeviceInfo): Boolean {
        if (audioRecord != null || audioTrack != null) {
            return true
        }
        if (appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("RECORD_AUDIO permission is required before starting audio capture")
        }
        val channelMask = AudioFormat.CHANNEL_IN_MONO
        val outputChannelMask = AudioFormat.CHANNEL_OUT_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val format = AudioFormat.Builder()
            .setSampleRate(config.sampleRate)
            .setEncoding(encoding)
            .setChannelMask(channelMask)
            .build()
        val outputFormat = AudioFormat.Builder()
            .setSampleRate(config.sampleRate)
            .setEncoding(encoding)
            .setChannelMask(outputChannelMask)
            .build()
        val minRecord = AudioRecord.getMinBufferSize(config.sampleRate, channelMask, encoding)
        val minTrack = AudioTrack.getMinBufferSize(config.sampleRate, outputChannelMask, encoding)
        if (minRecord <= 0 || minTrack <= 0) {
            return false
        }
        val frameBytes = config.frameSamples * 2
        val recordBuffer = max(minRecord, frameBytes * 4)
        val trackBuffer = max(minTrack, frameBytes * 4)
        val record = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(format)
            .setBufferSizeInBytes(recordBuffer)
            .build()
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(outputFormat)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(trackBuffer)
                .build()
        } catch (error: Throwable) {
            record.release()
            throw error
        }
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            record.release()
            track.release()
            return false
        }
        // The preferred input and output devices are separate objects on some
        // Android builds. Communication-device selection remains authoritative;
        // these preferences only help the two streams converge before verification.
        findInputDevice(route)?.let { record.setPreferredDevice(it) }
        findOutputDevice(route)?.let { track.setPreferredDevice(it) }
        record.addOnRoutingChangedListener(routeListener, callbackHandler)
        track.addOnRoutingChangedListener(trackRouteListener, callbackHandler)
        audioRecord = record
        audioTrack = track
        return true
    }

    private fun findInputDevice(route: AudioDeviceInfo): AudioDeviceInfo? =
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { device -> device.isSource && RouteKey.from(route).matches(device) }

    private fun findOutputDevice(route: AudioDeviceInfo): AudioDeviceInfo? =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { device -> device.isSink && RouteKey.from(route).matches(device) }

    private fun beginRouteVerification(
        expected: AudioDeviceInfo,
        record: AudioRecord,
        track: AudioTrack,
    ) {
        routeVerificationPending = true
        routeVerificationAttempts = 0
        pollRouteVerification(expected, record, track)
    }

    private fun pollRouteVerification(
        expected: AudioDeviceInfo,
        record: AudioRecord,
        track: AudioTrack,
    ) {
        if (!sessionRunning || audioRecord !== record || audioTrack !== track) {
            routeVerificationPending = false
            return
        }
        when (routeCheck(expected, record, track)) {
            RouteCheck.VERIFIED -> {
                routeVerificationPending = false
                routeVerified = true
                routeRetry?.cancel(false)
                routeRetry = null
                if (muted || captureSilenced) {
                    stopCapture()
                } else {
                    startCaptureWhenReady()
                }
                startPlaybackWhenReady()
                setState(if (muted || captureSilenced) State.MUTED else State.ACTIVE)
            }

            RouteCheck.PENDING -> {
                routeVerificationAttempts++
                if (routeVerificationAttempts >= ROUTE_VERIFICATION_ATTEMPTS) {
                    routeVerificationPending = false
                    suspendAudio(State.HEADSET_MISSING)
                    scheduleRouteRetry()
                } else {
                    controlExecutor.schedule(
                        { pollRouteVerification(expected, record, track) },
                        ROUTE_VERIFICATION_DELAY_MS,
                        TimeUnit.MILLISECONDS,
                    )
                }
            }

            RouteCheck.MISMATCH -> {
                routeVerificationPending = false
                suspendAudio(State.HEADSET_MISSING)
                scheduleRouteRetry()
            }
        }
    }

    private fun routeCheck(
        expected: AudioDeviceInfo,
        record: AudioRecord,
        track: AudioTrack,
    ): RouteCheck {
        val input = record.routedDevice
        val output = track.routedDevice
        if (input == null || output == null ||
            input.type == AudioDeviceInfo.TYPE_UNKNOWN || output.type == AudioDeviceInfo.TYPE_UNKNOWN
        ) {
            return RouteCheck.PENDING
        }
        if (!isAllowedInput(input) || !isAllowedOutput(output)) {
            return RouteCheck.MISMATCH
        }
        return if (RouteKey.from(input).matches(expected) && RouteKey.from(output).matches(expected)) {
            RouteCheck.VERIFIED
        } else {
            RouteCheck.MISMATCH
        }
    }

    private fun startCaptureWhenReady() {
        if (captureLoop || !routeVerified || muted || captureSilenced) {
            return
        }
        val record = audioRecord ?: return
        if (captureFuture?.isDone == false) {
            controlExecutor.schedule({ startCaptureWhenReady() }, 20, TimeUnit.MILLISECONDS)
            return
        }
        try {
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                record.startRecording()
            }
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                suspendAudio(State.HEADSET_MISSING)
                scheduleRouteRetry()
                return
            }
        } catch (error: Throwable) {
            notifyError(error)
            suspendAudio(State.HEADSET_MISSING)
            scheduleRouteRetry()
            return
        }
        captureLoop = true
        val generation = ++streamGeneration
        sequence = 0L
        sampleTime = 0L
        captureFuture = captureExecutor.submit {
            runCaptureLoop(record, generation)
        }
    }

    private fun runCaptureLoop(record: AudioRecord, generation: Long) {
        val encoder = try {
            com.ridechat.codec.OpusEncoder(
                sampleRate = config.sampleRate,
                channels = config.channels,
                bitrate = config.bitrate,
            )
        } catch (error: Throwable) {
            notifyError(error)
            captureLoop = false
            suspendAfterWorkerFailure()
            return
        }
        val samples = ShortArray(config.frameSamples)
        var filledSamples = 0
        try {
            while (captureLoop && sessionRunning && routeVerified && !muted && !captureSilenced) {
                val read = record.read(
                    samples,
                    filledSamples,
                    samples.size - filledSamples,
                    AudioRecord.READ_BLOCKING,
                )
                if (read <= 0) {
                    if (captureLoop && sessionRunning) {
                        val nextState = if (
                            read == AudioRecord.ERROR_DEAD_OBJECT ||
                            read == AudioRecord.ERROR_INVALID_OPERATION
                        ) {
                            State.HEADSET_MISSING
                        } else {
                            State.AUDIO_INTERRUPTED
                        }
                        notifyError(IllegalStateException("AudioRecord.read returned $read"))
                        suspendAfterWorkerFailure(nextState)
                    }
                    break
                }
                filledSamples += read
                if (filledSamples < samples.size) {
                    continue
                }
                filledSamples = 0
                if (!routeVerified || muted || captureSilenced) {
                    continue
                }
                val payload = encoder.encode(samples.copyOf())
                val frame = OutgoingAudioFrame(
                    generation = generation,
                    sequence = sequence++,
                    sampleTime = sampleTime,
                    payload = payload.copyOf(),
                )
                sampleTime += config.frameSamples.toLong()
                listener.onLocalFrame(frame)
            }
        } catch (error: Throwable) {
            if (captureLoop && sessionRunning) {
                notifyError(error)
                suspendAfterWorkerFailure()
            }
        } finally {
            try {
                encoder.close()
            } catch (error: Throwable) {
                notifyError(error)
            }
            captureLoop = false
        }
    }

    private fun startPlaybackWhenReady() {
        if (playbackLoop || !routeVerified) {
            return
        }
        val track = audioTrack ?: return
        if (playbackFuture?.isDone == false) {
            controlExecutor.schedule({ startPlaybackWhenReady() }, 20, TimeUnit.MILLISECONDS)
            return
        }
        playbackLoop = true
        playbackFuture = playbackExecutor.submit {
            runPlaybackLoop(track)
        }
    }

    private fun runPlaybackLoop(track: AudioTrack) {
        val frameNanos = config.frameSamples * 1_000_000_000L / config.sampleRate
        val mix = ShortArray(config.frameSamples)
        try {
            while (playbackLoop && sessionRunning && routeVerified) {
                drainIncomingFrames()
                val mixed = mixRemoteFrame(mix)
                var offset = 0
                while (offset < mixed.size && playbackLoop && sessionRunning && routeVerified) {
                    val written = track.write(mixed, offset, mixed.size - offset, AudioTrack.WRITE_BLOCKING)
                    if (written < 0) {
                        if (playbackLoop && sessionRunning) {
                            val nextState = if (
                                written == AudioTrack.ERROR_DEAD_OBJECT ||
                                written == AudioTrack.ERROR_INVALID_OPERATION
                            ) {
                                State.HEADSET_MISSING
                            } else {
                                State.AUDIO_INTERRUPTED
                            }
                            notifyError(IllegalStateException("AudioTrack.write returned $written"))
                            suspendAfterWorkerFailure(nextState)
                        }
                        return
                    }
                    if (written == 0) {
                        Thread.sleep(min(frameNanos / 1_000_000L, 20L))
                    } else {
                        offset += written
                    }
                }
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Throwable) {
            if (playbackLoop && sessionRunning) {
                notifyError(error)
                suspendAfterWorkerFailure()
            }
        } finally {
            playbackLoop = false
        }
    }

    private fun drainIncomingFrames() {
        synchronized(speakerLock) {
            while (true) {
                val frame = incomingFrames.poll() ?: break
                val existing = speakerBuffers[frame.senderId]
                if (existing != null) {
                    existing.offer(frame)
                    continue
                }
                if (speakerBuffers.size >= MAX_ACTIVE_SPEAKERS) {
                    continue
                }
                val candidate = SpeakerBuffer(frame.senderId)
                if (candidate.offer(frame)) {
                    speakerBuffers[frame.senderId] = candidate
                } else {
                    candidate.close()
                }
            }
        }
    }

    private val speakerBuffers = mutableMapOf<String, SpeakerBuffer>()

    private fun mixRemoteFrame(output: ShortArray): ShortArray {
        synchronized(speakerLock) {
            java.util.Arrays.fill(output, 0)
            var active = 0
            val mixed = IntArray(output.size)
            val iterator = speakerBuffers.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.value.isExpired()) {
                    entry.value.close()
                    iterator.remove()
                    continue
                }
                val samples = entry.value.render()
                if (samples == null) {
                    continue
                }
                if (samples.any { it != 0.toShort() }) {
                    active++
                }
                for (index in output.indices) {
                    mixed[index] += samples.getOrElse(index) { 0 }
                }
            }
            val scale = if (active == 0) 0f else 0.82f / active
            for (index in output.indices) {
                val value = (mixed[index] * scale).toInt()
                output[index] = value.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
        }
        return output
    }

    private fun suspendAudio(nextState: State) {
        if (!sessionRunning) {
            return
        }
        routeVerified = false
        releaseAudioResources()
        incomingFrames.clear()
        setState(nextState)
    }

    private fun suspendAfterWorkerFailure(nextState: State = State.AUDIO_INTERRUPTED) {
        postControl {
            if (sessionRunning) {
                suspendAudio(nextState)
                scheduleRouteRetry()
            }
        }
    }

    private fun stopCapture() {
        captureLoop = false
        audioRecord?.let { record ->
            try {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop()
                }
            } catch (_: RuntimeException) {
                // Release below still closes a dead recording path.
            }
        }
    }

    private fun stopPlayback() {
        playbackLoop = false
        audioTrack?.let { track ->
            try {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause()
                }
                track.flush()
            } catch (_: RuntimeException) {
                // Release below still closes a dead playback path.
            }
        }
    }

    private fun releaseAudioResources() {
        synchronized(resourceLock) {
            stopCapture()
            stopPlayback()
            audioRecord?.let { record ->
                try {
                    record.removeOnRoutingChangedListener(routeListener)
                    record.release()
                } catch (_: RuntimeException) {
                    // Resource is already dead.
                }
            }
            audioRecord = null
            audioTrack?.let { track ->
                try {
                    track.removeOnRoutingChangedListener(trackRouteListener)
                    track.release()
                } catch (_: RuntimeException) {
                    // Resource is already dead.
                }
            }
            audioTrack = null
            routeVerified = false
            routeVerificationPending = false
            resetSpeakerBuffers()
        }
    }

    private fun resetSpeakerBuffers() {
        // This method is normally called by the control thread. Playback may still
        // be finishing a blocking write, so the reset is also marked for its next
        // iteration through a direct map clear only after the loop has stopped.
        synchronized(speakerLock) {
            for (buffer in speakerBuffers.values) {
                buffer.close()
            }
            speakerBuffers.clear()
        }
    }

    private fun scheduleRouteRetry() {
        if (routeRetry?.isDone == false || !sessionRunning) {
            return
        }
        routeRetry = controlExecutor.schedule({
            routeRetry = null
            if (sessionRunning && !routeVerified) {
                if (desiredRoute == null) {
                    desiredRoute = chooseInitialRoute()
                }
                if (desiredRoute != null) {
                    openStreamsIfPossible()
                } else {
                    setState(State.HEADSET_MISSING)
                    scheduleRouteRetry()
                }
            }
        }, 1, TimeUnit.SECONDS)
    }

    private fun isAllowedHeadset(device: AudioDeviceInfo?): Boolean {
        if (device == null) {
            return false
        }
        return when (device.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE -> true

            else -> false
        }
    }

    private fun isAllowedInput(device: AudioDeviceInfo?): Boolean =
        isAllowedHeadset(device) && device!!.isSource

    private fun isAllowedOutput(device: AudioDeviceInfo?): Boolean =
        isAllowedHeadset(device) && device!!.isSink

    private fun routePriority(device: AudioDeviceInfo): Int = when (device.type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> 0
        AudioDeviceInfo.TYPE_BLE_HEADSET -> 1
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE -> 2
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> 3
        else -> 100
    }

    private fun setState(state: State) {
        if (lastState == state) {
            return
        }
        lastState = state
        listener.onStateChanged(state)
    }

    private fun notifyError(error: Throwable) {
        try {
            listener.onError(error)
        } catch (_: Throwable) {
            // Listener failures must not terminate an audio worker.
        }
    }

    data class Config(
        val sampleRate: Int = DEFAULT_SAMPLE_RATE,
        val channels: Int = 1,
        val frameSamples: Int = DEFAULT_FRAME_SAMPLES,
        val bitrate: Int = DEFAULT_BITRATE,
        val initialJitterFrames: Int = 3,
        val maxRemoteFrames: Int = 6,
        val maxIncomingFrames: Int = 64,
        val maxOpusPayloadBytes: Int = 2 * 1024,
        val preferredRouteAddress: String? = null,
    ) {
        init {
            require(sampleRate == DEFAULT_SAMPLE_RATE) { "The initial engine supports 48 kHz only" }
            require(channels == 1) { "The initial engine supports mono only" }
            require(frameSamples == DEFAULT_FRAME_SAMPLES) { "The initial engine supports 20 ms frames only" }
            require(initialJitterFrames in 2..6)
            require(maxRemoteFrames in initialJitterFrames..6)
            require(maxIncomingFrames >= 8)
            require(maxOpusPayloadBytes in 128..(2 * 1024))
        }
    }

    interface Listener {
        fun onLocalFrame(frame: OutgoingAudioFrame)
        fun onStateChanged(state: State)
        fun onRouteChanged(route: RouteInfo?) = Unit
        fun onMicrophoneSilenced(silenced: Boolean) = Unit
        fun onError(error: Throwable) = Unit
    }

    enum class State {
        IDLE,
        STARTING,
        ACTIVE,
        MUTED,
        HEADSET_MISSING,
        AUDIO_INTERRUPTED,
        STOPPING,
        CLOSED,
    }

    data class RouteInfo(
        val type: Int,
        val address: String,
        val name: String,
    )

    data class OutgoingAudioFrame(
        val generation: Long,
        val sequence: Long,
        val sampleTime: Long,
        val payload: ByteArray,
    )

    data class RemoteAudioFrame(
        val senderId: String,
        val generation: Long,
        val sequence: Long,
        val sampleTime: Long,
        val payload: ByteArray,
    )

    private data class RouteKey(
        val type: Int,
        val address: String,
        val id: Int,
    ) {
        fun matches(device: AudioDeviceInfo): Boolean {
            if (!sameRouteType(type, device.type)) {
                return false
            }
            if (address.isNotEmpty() && device.address.isNotEmpty()) {
                return address == device.address
            }
            // Wired headset input and output endpoints can expose different IDs
            // and no address even though they are one physical headset.
            if (type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                type == AudioDeviceInfo.TYPE_USB_DEVICE
            ) {
                return true
            }
            if (address.isNotEmpty() || device.address.isNotEmpty()) {
                return false
            }
            return id <= 0 || device.id <= 0 || id == device.id
        }

        private fun sameRouteType(expected: Int, actual: Int): Boolean =
            expected == actual ||
                (expected == AudioDeviceInfo.TYPE_USB_HEADSET && actual == AudioDeviceInfo.TYPE_USB_DEVICE) ||
                (expected == AudioDeviceInfo.TYPE_USB_DEVICE && actual == AudioDeviceInfo.TYPE_USB_HEADSET)

        fun toInfo(): RouteInfo = RouteInfo(type = type, address = address, name = "communication headset")

        companion object {
            fun from(device: AudioDeviceInfo): RouteKey =
                RouteKey(device.type, device.address.orEmpty(), device.id)
        }
    }

    private inner class SpeakerBuffer(
        private val senderId: String,
    ) {
        private val jitter = PerSpeakerJitterBuffer(
            frameDurationSamples = config.frameSamples.toLong(),
            targetDepthFrames = config.initialJitterFrames,
            capacityFrames = config.maxRemoteFrames,
        )
        private var decoder: com.ridechat.codec.OpusDecoder? = null
        private var missingFrames = 0
        private var lastFrameNanos = System.nanoTime()

        fun offer(frame: RemoteAudioFrame): Boolean {
            val currentGeneration = jitter.generation()
            if (currentGeneration != null && frame.generation > currentGeneration) {
                reset()
            }
            val coreFrame = AudioFrame(
                rideId = JITTER_RIDE_ID,
                senderId = senderId,
                generation = frame.generation,
                sequence = frame.sequence,
                sampleTime = frame.sampleTime,
                payload = frame.payload,
            )
            if (!jitter.offer(coreFrame)) {
                return false
            }
            lastFrameNanos = System.nanoTime()
            return true
        }

        fun render(): ShortArray? {
            val frame = jitter.poll()
            val samples = if (frame != null) {
                missingFrames = 0
                decoderFor(frame.generation).decode(frame.payload)
            } else if (missingFrames < config.initialJitterFrames) {
                missingFrames++
                decoder?.decode(null) ?: ShortArray(config.frameSamples)
            } else {
                missingFrames++
                ShortArray(config.frameSamples)
            }
            return samples.copyOf(config.frameSamples)
        }

        fun isExpired(): Boolean =
            System.nanoTime() - lastFrameNanos > SPEAKER_EXPIRATION_NANOS

        fun close() {
            reset()
        }

        private fun decoderFor(frameGeneration: Long): com.ridechat.codec.OpusDecoder {
            if (jitter.generation() != frameGeneration) {
                reset()
            }
            return decoder ?: com.ridechat.codec.OpusDecoder(
                sampleRate = config.sampleRate,
                channels = config.channels,
            ).also { decoder = it }
        }

        private fun reset() {
            jitter.reset()
            decoder?.close()
            decoder = null
            missingFrames = 0
        }
    }

    private companion object {
        const val DEFAULT_SAMPLE_RATE = 48_000
        const val DEFAULT_FRAME_SAMPLES = 960
        const val DEFAULT_BITRATE = 24_000
        const val ROUTE_VERIFICATION_ATTEMPTS = 20
        const val ROUTE_VERIFICATION_DELAY_MS = 25L
        const val JITTER_RIDE_ID = "audio-engine"
        const val MAX_ACTIVE_SPEAKERS = 3
        const val SPEAKER_EXPIRATION_NANOS = 2_000_000_000L
    }

    private enum class RouteCheck {
        VERIFIED,
        PENDING,
        MISMATCH,
    }
}
