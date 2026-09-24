package com.ridechat.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.ridechat.audio.AudioRoutePreference
import com.ridechat.service.RideService
import com.ridechat.transport.Confirmation
import com.ridechat.transport.DiscoveredPeer
import com.ridechat.transport.Member
import com.ridechat.transport.Phase
import com.ridechat.transport.RideState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel

class MainActivity : ComponentActivity() {
    private lateinit var viewModel: RideViewModel
    private var rideService: RideService? = null
    private var serviceBound = false
    private var bindRequested = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val serviceBinder = binder as? RideService.LocalBinder ?: return
            rideService = serviceBinder.service()
            serviceBound = true
            viewModel.attach(rideService!!)
            if (PermissionPolicy.missing(this@MainActivity).isEmpty() &&
                viewModel.state.value.playServicesStatus == ConnectionResult.SUCCESS
            ) {
                startAndRunPending()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceBound = false
            rideService = null
            viewModel.detach()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        refreshEnvironment()
        if (PermissionPolicy.missing(this).isEmpty()) {
            startAndRunPending()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[RideViewModel::class.java]
        refreshEnvironment()
        setContent {
            val state by viewModel.state.collectAsState()
            RideChatTheme {
                RideChatApp(
                    state = state,
                    onNameChanged = viewModel::setDisplayName,
                    onCommand = ::submit,
                    onRequestPermissions = ::requestPermissions,
                    onOpenSettings = ::openAppSettings,
                    onOpenPlayServices = ::showPlayServicesDialog,
                    onShareDiagnostics = ::shareDiagnostics,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (!bindRequested) {
            bindRequested = bindService(
                Intent(this, RideService::class.java),
                serviceConnection,
                Context.BIND_AUTO_CREATE,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        refreshEnvironment()
    }

    override fun onStop() {
        if (bindRequested) {
            unbindService(serviceConnection)
            bindRequested = false
        }
        serviceBound = false
        rideService = null
        viewModel.detach()
        super.onStop()
    }

    private fun refreshEnvironment() {
        val availability = GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(this)
        val playMessage = if (availability == ConnectionResult.SUCCESS) {
            null
        } else {
            "Google Play services must be installed or updated before an offline ride."
        }
        viewModel.setEnvironment(
            missing = PermissionPolicy.missing(this),
            missingOptional = PermissionPolicy.missingOptional(this),
            playMessage = playMessage,
            playStatus = availability,
        )
    }

    private fun requestPermissions() {
        val granted = PermissionPolicy.requestable().filter {
            androidx.core.content.ContextCompat.checkSelfPermission(this, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }.toSet()
        val missing = PermissionPolicy.requestableForRequest(granted)
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    private fun submit(command: UiCommand) {
        when (command) {
            UiCommand.OpenPermissions -> requestPermissions()
            UiCommand.OpenPlayServices -> showPlayServicesDialog()
            UiCommand.StartRide,
            UiCommand.Host,
            UiCommand.Discover,
            is UiCommand.Connect,
            -> {
                viewModel.queue(command)
                if (PermissionPolicy.missing(this).isEmpty()) startAndRunPending()
                else requestPermissions()
            }

            is UiCommand.Confirm,
            UiCommand.ToggleMute,
            UiCommand.EndRide,
            UiCommand.Leave,
            is UiCommand.SelectAudioRoute,
            -> viewModel.execute(command)
        }
    }

    private fun startAndRunPending() {
        if (!viewModel.hasPendingCommand()) return
        if (PermissionPolicy.missing(this).isNotEmpty()) return
        if (viewModel.state.value.playServicesStatus != ConnectionResult.SUCCESS) {
            showPlayServicesDialog()
            return
        }
        val serviceIntent = Intent(this, RideService::class.java)
        if (viewModel.pendingNeedsForegroundStart()) {
            // Start the foreground service from this visible Activity before the
            // Activity can be locked or rotated. The service consumes ACTION_START.
            try {
                androidx.core.content.ContextCompat.startForegroundService(
                    this,
                    serviceIntent.setAction(RideService.ACTION_START),
                )
                viewModel.clearPending()
            } catch (error: RuntimeException) {
                viewModel.failPending(
                    "Could not start the ride service: ${error.message?.take(120) ?: "try again while RideChat is open"}",
                )
            }
            return
        }
        startService(serviceIntent)
        viewModel.executePending()
    }

    private fun shareDiagnostics() {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, viewModel.diagnosticsText())
        }
        startActivity(Intent.createChooser(shareIntent, "Share RideChat diagnostics"))
    }

    private fun openAppSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName"),
            ),
        )
    }

    private fun showPlayServicesDialog() {
        val availability = GoogleApiAvailability.getInstance()
        val result = viewModel.state.value.playServicesStatus
        if (result != ConnectionResult.SUCCESS) {
            availability.getErrorDialog(this, result, PLAY_SERVICES_REQUEST)?.show()
        }
    }

    companion object {
        private const val PLAY_SERVICES_REQUEST = 4301
    }
}

class RideViewModel(application: android.app.Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(
        UiState(displayName = "Rider", missingPermissions = emptyList()),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var service: RideService? = null
    private var serviceJob: Job? = null
    private var pending: UiCommand? = null

    init {
        _state.value = _state.value.copy(
            displayName = preferences.getString(DISPLAY_NAME, "Rider").orEmpty().ifBlank { "Rider" },
        )
    }

    fun attach(next: RideService) {
        serviceJob?.cancel()
        service = next
        serviceJob = scope.launch {
            next.state.collectLatest { serviceState ->
                _state.value = _state.value.copy(
                    ride = serviceState.ride,
                    audioState = serviceState.audioState,
                    routeName = serviceState.route?.name,
                    routePreference = serviceState.routePreference,
                    microphoneSilenced = serviceState.microphoneSilenced,
                    serviceError = serviceState.error,
                    status = serviceState.status,
                )
            }
        }
        // MainActivity dispatches pending commands after the permission and
        // Play Services checks. A bind alone must not execute a queued command.
    }

    fun detach() {
        serviceJob?.cancel()
        serviceJob = null
        service = null
    }

    fun setDisplayName(value: String) {
        val clean = value.take(MAX_NAME_LENGTH)
        _state.update { it.copy(displayName = clean) }
        preferences.edit().putString(DISPLAY_NAME, clean).apply()
    }

    fun setEnvironment(
        missing: List<String>,
        missingOptional: List<String>,
        playMessage: String?,
        playStatus: Int,
    ) {
        _state.update {
            it.copy(
                missingPermissions = missing,
                missingOptionalPermissions = missingOptional,
                playServicesMessage = playMessage,
                playServicesStatus = playStatus,
            )
        }
    }

    fun queue(command: UiCommand) {
        pending = command
    }

    fun hasPendingCommand(): Boolean = pending != null

    fun pendingNeedsForegroundStart(): Boolean = pending == UiCommand.StartRide

    fun clearPending() {
        pending = null
    }

    fun failPending(message: String) {
        pending = null
        _state.update {
            it.copy(
                serviceError = message.take(180),
                status = "Action needs attention",
            )
        }
    }

    fun executePending() {
        val command = pending ?: return
        if (service == null) return
        pending = null
        execute(command)
    }

    fun execute(command: UiCommand) {
        val current = service ?: return
        when (command) {
            UiCommand.Host -> current.host(displayNameForUse())
            UiCommand.Discover -> current.discover(displayNameForUse())
            is UiCommand.Connect -> current.connect(command.endpointId)
            is UiCommand.Confirm -> current.confirm(command.endpointId, command.accepted)
            UiCommand.StartRide -> current.startRide()
            UiCommand.ToggleMute -> current.setMuted(!_state.value.ride.muted)
            is UiCommand.SelectAudioRoute -> current.setAudioRoutePreference(command.preference)
            UiCommand.EndRide,
            UiCommand.Leave,
            -> current.endRide()

            UiCommand.OpenPermissions,
            UiCommand.OpenPlayServices,
            -> Unit
        }
    }

    /** Returns a short report without ride IDs, endpoint IDs, rider names, or audio data. */
    fun diagnosticsText(): String = buildString {
        appendLine("RideChat diagnostics")
        appendLine("phase=${_state.value.ride.phase.name}")
        appendLine("memberCount=${_state.value.ride.members.size.coerceAtMost(4)}")
        appendLine("audioState=${_state.value.audioState.name}")
        appendLine("audioRoutePreference=${_state.value.routePreference.name}")
        appendLine("audioRoute=${_state.value.routeName ?: "none"}")
        appendLine("microphoneSilenced=${_state.value.microphoneSilenced}")
        appendLine("requiredPermissionsMissing=${_state.value.missingPermissions.size}")
        appendLine("notificationPermissionMissing=${_state.value.missingOptionalPermissions.size}")
        appendLine("playServicesStatus=${_state.value.playServicesStatus}")
        appendLine("errorPresent=${_state.value.serviceError != null}")
    }.take(MAX_DIAGNOSTICS_LENGTH)

    private fun displayNameForUse(): String {
        return _state.value.displayName.trim().ifEmpty { "Rider" }.take(MAX_NAME_LENGTH)
    }

    override fun onCleared() {
        serviceJob?.cancel()
        scope.cancel()
        super.onCleared()
    }

    companion object {
        private const val PREFERENCES = "ridechat"
        private const val DISPLAY_NAME = "display_name"
        private const val MAX_NAME_LENGTH = 32
        private const val MAX_DIAGNOSTICS_LENGTH = 1000
    }
}

data class UiState(
    val displayName: String,
    val ride: RideState = RideState(),
    val audioState: com.ridechat.audio.AudioEngine.State = com.ridechat.audio.AudioEngine.State.IDLE,
    val routeName: String? = null,
    val routePreference: AudioRoutePreference = AudioRoutePreference.AUTOMATIC,
    val microphoneSilenced: Boolean = false,
    val missingPermissions: List<String> = emptyList(),
    val missingOptionalPermissions: List<String> = emptyList(),
    val playServicesMessage: String? = null,
    val playServicesStatus: Int = ConnectionResult.SUCCESS,
    val serviceError: String? = null,
    val status: String = "Ready",
)

sealed interface UiCommand {
    data object Host : UiCommand
    data object Discover : UiCommand
    data class Connect(val endpointId: String) : UiCommand
    data class Confirm(val endpointId: String, val accepted: Boolean) : UiCommand
    data object StartRide : UiCommand
    data object ToggleMute : UiCommand
    data class SelectAudioRoute(val preference: AudioRoutePreference) : UiCommand
    data object EndRide : UiCommand
    data object Leave : UiCommand
    data object OpenPermissions : UiCommand
    data object OpenPlayServices : UiCommand
}

@Composable
private fun RideChatApp(
    state: UiState,
    onNameChanged: (String) -> Unit,
    onCommand: (UiCommand) -> Unit,
    onRequestPermissions: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPlayServices: () -> Unit,
    onShareDiagnostics: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("RideChat", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text("Short-range voice for a group ride", style = MaterialTheme.typography.bodyLarge)
            StatusCard(state, onShareDiagnostics)
            when (state.ride.phase) {
                Phase.LOBBY -> LobbyScreen(state, onCommand)
                Phase.ACTIVE,
                Phase.RECONNECTING,
                -> ActiveScreen(state, onCommand)

                Phase.DISCOVERING -> DiscoveryScreen(state, onCommand)
                Phase.IDLE -> SetupScreen(
                    state = state,
                    onNameChanged = onNameChanged,
                    onCommand = onCommand,
                    onRequestPermissions = onRequestPermissions,
                    onOpenSettings = onOpenSettings,
                    onOpenPlayServices = onOpenPlayServices,
                )
            }
        }
    }
}

@Composable
private fun StatusCard(state: UiState, onShareDiagnostics: () -> Unit) {
    val audioText = when {
        state.microphoneSilenced -> "Microphone privacy switch is active"
        state.audioState == com.ridechat.audio.AudioEngine.State.ROUTE_UNAVAILABLE -> "Audio paused; choose phone speaker or reconnect a headset"
        state.audioState == com.ridechat.audio.AudioEngine.State.AUDIO_INTERRUPTED -> "Audio interrupted; check calls or another audio app"
        state.routeName != null -> "Audio: ${state.routeName}"
        state.routePreference == AudioRoutePreference.PHONE -> "Audio: phone mic and speaker selected"
        state.routePreference == AudioRoutePreference.HEADSET -> "Audio: headset selected"
        else -> "Audio: automatic; headset if connected, otherwise phone"
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(state.status, fontWeight = FontWeight.SemiBold)
            Text(audioText)
            state.serviceError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = onShareDiagnostics) { Text("Share diagnostics") }
        }
    }
}

@Composable
private fun SetupScreen(
    state: UiState,
    onNameChanged: (String) -> Unit,
    onCommand: (UiCommand) -> Unit,
    onRequestPermissions: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPlayServices: () -> Unit,
) {
    var name by remember(state.displayName) { mutableStateOf(state.displayName) }
    OutlinedTextField(
        value = name,
        onValueChange = {
            name = it
            onNameChanged(it)
        },
        label = { Text("Your rider name") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    PermissionCard(state, onRequestPermissions, onOpenSettings)
    state.playServicesMessage?.let {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(it)
                TextButton(onClick = onOpenPlayServices) { Text("Check Google Play services") }
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        Button(
            onClick = { onCommand(UiCommand.Host) },
            enabled = state.displayName.trim().isNotEmpty() && state.playServicesMessage == null,
            modifier = Modifier.weight(1f),
        ) { Text("Create group") }
        OutlinedButton(
            onClick = { onCommand(UiCommand.Discover) },
            enabled = state.displayName.trim().isNotEmpty() && state.playServicesMessage == null,
            modifier = Modifier.weight(1f),
        ) { Text("Find group") }
    }
}

@Composable
private fun PermissionCard(state: UiState, onRequest: () -> Unit, onSettings: () -> Unit) {
    Card {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Before a ride", style = MaterialTheme.typography.titleMedium)
            if (state.missingPermissions.isEmpty()) {
                Text("Microphone and nearby permissions ready")
                if (state.missingOptionalPermissions.isNotEmpty()) {
                    Text("Notification permission is optional; lock-screen controls may be unavailable.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onRequest) { Text("Allow notifications") }
                        TextButton(onClick = onSettings) { Text("Open settings") }
                    }
                }
            } else {
                Text("Needed: ${state.missingPermissions.joinToString { PermissionPolicy.label(it) }}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onRequest) { Text("Request permissions") }
                    TextButton(onClick = onSettings) { Text("Open settings") }
                }
            }
        }
    }
}

@Composable
private fun DiscoveryScreen(state: UiState, onCommand: (UiCommand) -> Unit) {
    Text("Looking for a host nearby…", style = MaterialTheme.typography.titleMedium)
    if (state.ride.discovered.isEmpty()) {
        Text("Keep the host phone close and make sure both phones have the app open.")
    } else {
        state.ride.discovered.forEach { peer -> DiscoveredPeerRow(peer, onCommand) }
    }
    OutlinedButton(onClick = { onCommand(UiCommand.Leave) }) { Text("Stop searching") }
}

@Composable
private fun DiscoveredPeerRow(peer: DiscoveredPeer, onCommand: (UiCommand) -> Unit) {
    Card {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(peer.name.ifBlank { "Nearby rider" }, fontWeight = FontWeight.SemiBold)
            Button(onClick = { onCommand(UiCommand.Connect(peer.endpointId)) }) { Text("Join") }
        }
    }
}

@Composable
private fun LobbyScreen(state: UiState, onCommand: (UiCommand) -> Unit) {
    Text(if (state.ride.isHost) "Host lobby" else "Group lobby", style = MaterialTheme.typography.titleLarge)
    Text("Confirm the matching code before audio is allowed.")
    state.ride.pending.forEach { confirmation -> ConfirmationRow(confirmation, onCommand) }
    MemberList(state.ride.members)
    AudioRouteControls(state, onCommand)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        Button(onClick = { onCommand(UiCommand.StartRide) }, modifier = Modifier.weight(1f)) { Text("Start ride") }
        OutlinedButton(onClick = { onCommand(UiCommand.Leave) }, modifier = Modifier.weight(1f)) { Text("Leave") }
    }
}

@Composable
private fun ConfirmationRow(confirmation: Confirmation, onCommand: (UiCommand) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Confirm ${confirmation.name.ifBlank { "rider" }}", fontWeight = FontWeight.SemiBold)
            Text("Code: ${confirmation.code}", style = MaterialTheme.typography.headlineSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onCommand(UiCommand.Confirm(confirmation.endpointId, true)) }) { Text("Accept") }
                TextButton(onClick = { onCommand(UiCommand.Confirm(confirmation.endpointId, false)) }) { Text("Reject") }
            }
        }
    }
}

@Composable
private fun ActiveScreen(state: UiState, onCommand: (UiCommand) -> Unit) {
    Text(if (state.ride.phase == Phase.RECONNECTING) "Reconnecting" else "Ride active", style = MaterialTheme.typography.titleLarge)
    Text("Use a headset for rides. Phone speaker is for stationary testing.")
    AudioRouteControls(state, onCommand)
    MemberList(state.ride.members)
    Button(
        onClick = { onCommand(UiCommand.ToggleMute) },
        modifier = Modifier.fillMaxWidth().height(64.dp).semantics { contentDescription = "Mute microphone" },
    ) {
        Text(if (state.ride.muted) "Unmute microphone" else "Mute microphone", style = MaterialTheme.typography.titleMedium)
    }
    OutlinedButton(onClick = { onCommand(UiCommand.EndRide) }, modifier = Modifier.fillMaxWidth()) { Text("End ride") }
}

@Composable
private fun AudioRouteControls(state: UiState, onCommand: (UiCommand) -> Unit) {
    Text("Audio route", style = MaterialTheme.typography.titleMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        listOf(
            AudioRoutePreference.AUTOMATIC to "Auto",
            AudioRoutePreference.PHONE to "Phone",
            AudioRoutePreference.HEADSET to "Headset",
        ).forEach { (preference, label) ->
            if (state.routePreference == preference) {
                Button(onClick = { onCommand(UiCommand.SelectAudioRoute(preference)) }, modifier = Modifier.weight(1f)) {
                    Text(label)
                }
            } else {
                OutlinedButton(onClick = { onCommand(UiCommand.SelectAudioRoute(preference)) }, modifier = Modifier.weight(1f)) {
                    Text(label)
                }
            }
        }
    }
    Text("Auto uses a headset when connected, or the phone mic and speaker.")
}

@Composable
private fun MemberList(members: List<Member>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Riders (${members.size}/4)", style = MaterialTheme.typography.titleMedium)
        if (members.isEmpty()) Text("Waiting for riders…")
        members.forEach { member ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(member.name.ifBlank { member.senderId })
                Text(
                    when {
                        !member.connected -> "Disconnected"
                        member.muted -> "Muted"
                        else -> "Connected"
                    },
                    color = if (member.connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun RideChatTheme(content: @Composable () -> Unit) {
    MaterialTheme(content = content)
}
