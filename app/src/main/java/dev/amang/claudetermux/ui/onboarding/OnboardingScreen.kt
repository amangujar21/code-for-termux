package dev.amang.claudetermux.ui.onboarding

import android.Manifest
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.amang.claudetermux.App
import dev.amang.claudetermux.bridge.BridgeStatus
import dev.amang.claudetermux.bridge.ConnectionState
import dev.amang.claudetermux.bridge.SetupServer
import dev.amang.claudetermux.bridge.TermuxLauncher
import dev.amang.claudetermux.data.Settings
import dev.amang.claudetermux.ui.theme.extraColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OnboardingUi(
    val termuxInstalled: Boolean = false,
    val runCommandGranted: Boolean = false,
    val setupCommand: String? = null,
    val connection: ConnectionState = ConnectionState.Idle,
    val status: BridgeStatus? = null,
    val statusError: String? = null,
    val host: String = Settings.DEFAULT_HOST,
    val port: Int = Settings.DEFAULT_PORT,
    val message: String? = null,
) {
    val connected: Boolean get() = connection is ConnectionState.Connected
    val signedIn: Boolean get() = status?.account?.signedIn == true
}

class OnboardingViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as App
    private var server: SetupServer? = null

    private val _ui = MutableStateFlow(OnboardingUi())
    val ui: StateFlow<OnboardingUi> = _ui

    init {
        viewModelScope.launch {
            val token = app.settings.ensureToken()
            val settings = app.settings.current()
            server = SetupServer(app, token, settings.port) { app.bridge.retryNow() }
            _ui.update { it.copy(setupCommand = server?.command, host = settings.host, port = settings.port) }
        }
        viewModelScope.launch {
            app.bridge.state.collect { state ->
                _ui.update { it.copy(connection = state) }
                if (state is ConnectionState.Connected) checkAccount()
            }
        }
        refreshDeviceState()
    }

    fun refreshDeviceState() {
        _ui.update {
            it.copy(
                termuxInstalled = TermuxLauncher.isInstalled(app),
                runCommandGranted = TermuxLauncher.hasPermission(app),
            )
        }
        if (_ui.value.connected) checkAccount() else app.bridge.retryNow()
    }

    fun checkAccount() {
        viewModelScope.launch {
            runCatching { app.bridge.request("bridge.status", BridgeStatus.serializer(), timeoutMs = 60_000) }
                .onSuccess { status -> _ui.update { it.copy(status = status, statusError = null) } }
                .onFailure { e -> _ui.update { it.copy(statusError = e.message) } }
        }
    }

    fun startBridge(restart: Boolean = false) {
        val problem = TermuxLauncher.startBridge(app, restart)
        _ui.update { it.copy(message = problem ?: "Asked Termux to start the bridge…") }
        if (problem == null) viewModelScope.launch {
            delay(2500)
            app.bridge.retryNow()
        }
    }

    fun saveEndpoint(host: String, port: Int, token: String?) {
        viewModelScope.launch {
            app.settings.setConnection(host.trim().ifEmpty { Settings.DEFAULT_HOST }, port, token?.trim()?.takeIf { it.isNotEmpty() })
            _ui.update { it.copy(host = host, port = port, message = "Saved") }
        }
    }

    fun finish(onDone: () -> Unit) {
        viewModelScope.launch {
            app.settings.setOnboarded(true)
            onDone()
        }
    }

    override fun onCleared() {
        server?.stop()
    }
}

@Composable
fun OnboardingScreen(viewModel: OnboardingViewModel, onDone: () -> Unit) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val extra = extraColors
    var advanced by rememberSaveable { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        viewModel.refreshDeviceState()
        onPauseOrDispose { }
    }
    val askRunCommand = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.refreshDeviceState() }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Set up Claude Code in Termux", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "This app is an unofficial client. Claude Code runs on your phone inside Termux; the app talks to it over a private local connection.",
            color = extra.muted,
            fontSize = 14.sp,
        )

        Step(1, "Install Termux", ui.termuxInstalled) {
            Text("Use the F-Droid or GitHub build. The Play Store build can't be started by other apps.", color = extra.muted, fontSize = 14.sp)
            if (!ui.termuxInstalled) {
                Button(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/packages/com.termux/")))
                }) { Text("Get Termux") }
            }
        }

        Step(2, "Run the setup command in Termux", ui.connected) {
            Text(
                "It installs Node.js, Claude Code and a small bridge, and turns on Termux's \"allow-external-apps\" setting so this app can start the bridge. " +
                    "That setting lets any app you grant \"Run commands in Termux\" run commands there, so only grant it to apps you trust.",
                color = extra.muted,
                fontSize = 14.sp,
            )
            val command = ui.setupCommand
            Text(
                command ?: "Preparing…",
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(extra.codeBackground)
                    .border(1.dp, extra.divider, RoundedCornerShape(10.dp))
                    .padding(12.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = command != null, onClick = { command?.let { clipboard.setText(AnnotatedString(it)) } }) { Text("Copy") }
                OutlinedButton(enabled = ui.termuxInstalled, onClick = { TermuxLauncher.open(context) }) { Text("Open Termux") }
            }
            Text("Paste it in Termux and wait for \"All set\". The command only works while this screen has been opened recently.", color = extra.muted, fontSize = 13.sp)
        }

        Step(3, "Sign in to Claude", ui.signedIn) {
            val account = ui.status?.account
            Text(
                when {
                    ui.signedIn -> listOfNotNull(account?.email, account?.subscriptionType, ui.status?.claudeCodeVersion?.let { "Claude Code $it" })
                        .joinToString(" · ").ifEmpty { "Signed in" }
                    !ui.connected -> "Available once the bridge is connected."
                    ui.statusError != null -> "Could not check: ${ui.statusError}"
                    ui.status == null -> "Checking…"
                    else -> "In Termux, run  claude  and follow the sign-in prompts, then come back."
                },
                color = extra.muted,
                fontSize = 14.sp,
            )
            if (ui.connected && !ui.signedIn) TextButton(onClick = viewModel::checkAccount) { Text("Check again") }
        }

        Step(4, "Let this app start the bridge", ui.runCommandGranted) {
            Text("Without this you would need to start the bridge from Termux yourself after each reboot.", color = extra.muted, fontSize = 14.sp)
            if (!ui.runCommandGranted) {
                Button(enabled = ui.termuxInstalled, onClick = { askRunCommand.launch(TermuxLauncher.PERMISSION) }) { Text("Grant permission") }
            } else if (!ui.connected) {
                OutlinedButton(onClick = { viewModel.startBridge() }) { Text("Start bridge now") }
            }
        }

        if (Build.VERSION.SDK_INT >= 33) {
            TextButton(onClick = { askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                Text("Allow notifications when Claude finishes or needs permission")
            }
        }

        ConnectionLine(ui)
        ui.message?.let { Text(it, color = extra.muted, fontSize = 13.sp) }

        Button(enabled = ui.connected, onClick = { viewModel.finish(onDone) }, modifier = Modifier.fillMaxWidth()) {
            Text(if (ui.connected) "Continue" else "Waiting for the bridge…")
        }

        TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Hide advanced" else "Advanced") }
        if (advanced) Advanced(ui, viewModel)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ConnectionLine(ui: OnboardingUi) {
    val (color, text) = when (val c = ui.connection) {
        is ConnectionState.Connected -> extraColors.success to "Connected to bridge ${c.hello.bridgeVersion}"
        is ConnectionState.Unauthorized -> MaterialTheme.colorScheme.error to "The bridge rejected this app's token. Run the setup command again."
        is ConnectionState.Unreachable -> extraColors.muted to "Bridge not reachable yet (${c.reason})"
        else -> extraColors.muted to "Looking for the bridge…"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 13.sp, color = extraColors.muted)
    }
}

@Composable
private fun Advanced(ui: OnboardingUi, viewModel: OnboardingViewModel) {
    var host by remember(ui.host) { mutableStateOf(ui.host) }
    var port by remember(ui.port) { mutableStateOf(ui.port.toString()) }
    var token by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Only change these if the bridge runs somewhere else, for example while developing against an emulator.", color = extraColors.muted, fontSize = 13.sp)
        OutlinedTextField(host, { host = it }, label = { Text("Host") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("Port") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(token, { token = it }, label = { Text("Pairing token (leave empty to keep)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { viewModel.saveEndpoint(host, port.toIntOrNull() ?: Settings.DEFAULT_PORT, token) }) { Text("Save") }
            OutlinedButton(enabled = ui.runCommandGranted, onClick = { viewModel.startBridge(restart = true) }) { Text("Restart bridge") }
        }
    }
}

@Composable
private fun Step(number: Int, title: String, done: Boolean, content: @Composable () -> Unit) {
    val extra = extraColors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(1.dp, extra.divider, RoundedCornerShape(16.dp))
            .padding(14.dp),
    ) {
        Box(
            Modifier.size(26.dp).clip(CircleShape).background(if (done) extra.success else extra.chip),
            contentAlignment = Alignment.Center,
        ) {
            if (done) Icon(Icons.Outlined.Check, "Done", tint = MaterialTheme.colorScheme.surface, modifier = Modifier.size(16.dp))
            else Text(number.toString(), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.width(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}
