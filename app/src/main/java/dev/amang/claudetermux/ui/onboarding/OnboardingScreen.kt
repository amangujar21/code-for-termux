package dev.amang.claudetermux.ui.onboarding

import android.Manifest
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import dev.amang.claudetermux.MainActivity
import dev.amang.claudetermux.bridge.BridgeStatus
import dev.amang.claudetermux.bridge.ConnectionState
import dev.amang.claudetermux.bridge.SetupServer
import dev.amang.claudetermux.bridge.TermuxAccess
import dev.amang.claudetermux.bridge.TermuxLauncher
import dev.amang.claudetermux.data.Settings
import dev.amang.claudetermux.ui.theme.extraColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.put

sealed interface SetupState {
    data object Idle : SetupState
    data class Running(val step: String) : SetupState
    data class Failed(val detail: String) : SetupState
}

/** The one thing the setup screen is waiting on. */
enum class SetupAction { GetTermux, GrantPermission, LinkTermux, RunSetup, Working, SignIn, Done }

data class OnboardingUi(
    val termuxInstalled: Boolean = false,
    /** False when the installed Termux is a build that cannot take commands from other apps. */
    val termuxSupported: Boolean = true,
    val runCommandGranted: Boolean = false,
    val termuxLinked: Boolean = false,
    /** Null until settings have loaded. */
    val alreadyOnboarded: Boolean? = null,
    val setup: SetupState = SetupState.Idle,
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

    val action: SetupAction
        get() = when {
            connected && signedIn -> SetupAction.Done
            connected && status == null && statusError == null -> SetupAction.Working
            connected -> SetupAction.SignIn
            setup is SetupState.Running -> SetupAction.Working
            !termuxInstalled || !termuxSupported -> SetupAction.GetTermux
            !runCommandGranted -> SetupAction.GrantPermission
            !termuxLinked -> SetupAction.LinkTermux
            else -> SetupAction.RunSetup
        }
}

class OnboardingViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as App
    private var server: SetupServer? = null
    private var watchdog: Job? = null

    private val _ui = MutableStateFlow(OnboardingUi())
    val ui: StateFlow<OnboardingUi> = _ui

    init {
        viewModelScope.launch {
            val token = app.settings.ensureToken()
            val settings = app.settings.current()
            server = SetupServer(app, token, settings.port, ::onSetupProgress, ::onSetupFailed) {
                watchdog?.cancel()
                _ui.update { it.copy(setup = SetupState.Idle) }
                app.bridge.retryNow()
            }
            _ui.update {
                it.copy(
                    setupCommand = server?.command,
                    host = settings.host,
                    port = settings.port,
                    termuxLinked = settings.termuxLinked,
                    alreadyOnboarded = settings.onboarded,
                )
            }
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
            val granted = TermuxLauncher.hasPermission(app)
            val installed = TermuxLauncher.isInstalled(app)
            it.copy(
                termuxInstalled = installed,
                termuxSupported = !installed || TermuxLauncher.supportsRunCommand(app),
                runCommandGranted = granted,
                // Drop the "permission needed" hint once the user has granted it in system settings.
                message = if (granted && !it.runCommandGranted) null else it.message,
            )
        }
        if (_ui.value.connected) checkAccount() else app.bridge.retryNow()
    }

    fun checkAccount() {
        // The bridge caches its answer; ask it to look again while we wait for the user to sign in.
        val fresh = !_ui.value.signedIn
        viewModelScope.launch {
            runCatching {
                app.bridge.request("bridge.status", BridgeStatus.serializer(), timeoutMs = 60_000) { put("fresh", fresh) }
            }
                .onSuccess { status -> _ui.update { it.copy(status = status, statusError = null) } }
                .onFailure { e -> _ui.update { it.copy(statusError = e.message) } }
        }
    }

    fun say(message: String?) = _ui.update { it.copy(message = message) }

    /** Called with the folder the user confirmed in the system picker. */
    fun linkTermux(tree: Uri, onLinked: () -> Unit) {
        viewModelScope.launch {
            val problem = withContext(Dispatchers.IO) { TermuxAccess.allowExternalApps(app, tree) }
            if (problem == null) app.settings.setTermuxLinked(true)
            _ui.update { it.copy(termuxLinked = it.termuxLinked || problem == null, message = problem) }
            if (problem == null) onLinked()
        }
    }

    /** Has Termux install everything in the background; the script reports back through [SetupServer]. */
    fun runSetup() {
        val command = server?.command ?: return
        val problem = TermuxLauncher.runSetup(app, command)
        if (problem != null) {
            _ui.update { it.copy(setup = SetupState.Failed(problem)) }
            return
        }
        _ui.update { it.copy(setup = SetupState.Running("Starting Termux…"), message = null) }
        watchdog?.cancel()
        watchdog = viewModelScope.launch {
            delay(25_000)
            _ui.update {
                if (it.connected) it
                else it.copy(
                    setup = SetupState.Failed(
                        "Termux did not respond. Open Termux once so it finishes its own first-time install, then come back and try again.",
                    ),
                )
            }
        }
    }

    private fun onSetupProgress(step: String) {
        watchdog?.cancel()
        val text = when (step) {
            "fetched" -> "Termux is getting ready…"
            "running" -> "Setup is already running in Termux…"
            "packages" -> "Installing packages. This can take several minutes…"
            "claude" -> "Downloading Claude Code…"
            "bridge" -> "Installing the bridge…"
            "starting" -> "Starting the bridge…"
            else -> return
        }
        _ui.update { it.copy(setup = SetupState.Running(text)) }
    }

    private fun onSetupFailed(log: String) {
        watchdog?.cancel()
        _ui.update { it.copy(setup = SetupState.Failed(log.ifEmpty { "Setup stopped without saying why." })) }
    }

    /** Opens Termux on Claude's sign-in prompt; Termux sends the user back here afterwards. */
    fun signIn() {
        val problem = TermuxLauncher.openSignIn(app, "${app.packageName}/${MainActivity::class.java.name}")
        say(problem?.let { "$it. You can also open Termux and run: claude auth login" })
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
    val extra = extraColors
    var advanced by rememberSaveable { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        viewModel.refreshDeviceState()
        onPauseOrDispose { }
    }

    // After the first tap, each finished step leads straight into the next one.
    val next = remember { object { var run: () -> Unit = {} } }
    // Android answers "no" by itself, without a prompt, once the user has refused twice or when
    // Termux was installed after this app. Only the app's system settings page can grant it then.
    var promptBlocked by rememberSaveable { mutableStateOf(false) }
    var askedAt by remember { mutableStateOf(0L) }
    val askRunCommand = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.refreshDeviceState()
        when {
            granted -> next.run()
            SystemClock.elapsedRealtime() - askedAt < 700 -> {
                promptBlocked = true
                viewModel.say(
                    "Android did not show the permission prompt. Tap Open app settings, then Permissions > Additional permissions > " +
                        "\"Run commands in Termux environment\" > Allow, and come back here. " +
                        "If that entry is missing, uninstall this app and install it again; Termux has to be installed first.",
                )
            }
            else -> viewModel.say("The app needs that permission to set up and start Claude Code in Termux.")
        }
    }
    val requestRunCommand = {
        if (promptBlocked) {
            context.startActivity(
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
            )
        } else {
            askedAt = SystemClock.elapsedRealtime()
            askRunCommand.launch(TermuxLauncher.PERMISSION)
        }
    }
    val pickTermuxHome = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) viewModel.linkTermux(tree) { next.run() }
        else viewModel.say("No folder was chosen. Tap Set up again and tap \"Use this folder\" on the Termux folder.")
    }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    next.run = {
        val now = viewModel.ui.value
        when (now.action) {
            SetupAction.GetTermux -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/packages/com.termux/")))
            SetupAction.GrantPermission -> requestRunCommand()
            SetupAction.LinkTermux -> try {
                pickTermuxHome.launch(TermuxAccess.homeUri)
            } catch (_: ActivityNotFoundException) {
                viewModel.say("This phone has no folder picker. Use the manual command under Advanced.")
            }
            SetupAction.RunSetup -> viewModel.runSetup()
            SetupAction.SignIn -> if (now.runCommandGranted) viewModel.signIn() else requestRunCommand()
            SetupAction.Done -> viewModel.finish(onDone)
            SetupAction.Working -> Unit
        }
    }

    // First-time setup moves on by itself once the user has signed in.
    LaunchedEffect(ui.action, ui.alreadyOnboarded) {
        if (ui.action == SetupAction.Done && ui.alreadyOnboarded == false) viewModel.finish(onDone)
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Set up Claude Code", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "This app is an unofficial client. Claude Code runs on your phone inside Termux; the app installs it there and talks to it over a private local connection.",
            color = extra.muted,
            fontSize = 14.sp,
        )

        val termuxReady = ui.connected || (ui.termuxInstalled && ui.termuxSupported && ui.runCommandGranted && ui.termuxLinked)
        Step(1, "Connect to Termux", termuxReady) {
            Text(
                when {
                    termuxReady -> "This app can run commands in Termux."
                    !ui.termuxInstalled -> "Install Termux from F-Droid or GitHub. The Play Store build can't be started by other apps."
                    !ui.termuxSupported -> "The Termux on this phone is the Play Store build, which other apps can't run commands in. " +
                        "Uninstall it (this deletes everything stored in Termux), install Termux from F-Droid or GitHub, open it once, then come back here."
                    else -> "Allow this app to run commands in Termux, then tap \"Use this folder\" and \"Allow\" on the Termux folder that opens. " +
                        "That lets the app turn on Termux's \"allow-external-apps\" setting. Any app you grant \"Run commands in Termux\" can then run commands there, so only grant it to apps you trust."
                },
                color = extra.muted,
                fontSize = 14.sp,
            )
        }

        Step(2, "Install Claude Code", ui.connected) {
            when (val setup = ui.setup) {
                is SetupState.Running -> if (!ui.connected) Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(setup.step, fontSize = 14.sp)
                }
                is SetupState.Failed -> if (!ui.connected) {
                    Text("Setup did not finish:", color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
                    Text(
                        setup.detail,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(extra.codeBackground)
                            .border(1.dp, extra.divider, RoundedCornerShape(10.dp))
                            .padding(12.dp),
                    )
                }
                SetupState.Idle -> Unit
            }
            Text(
                if (ui.connected) listOfNotNull("Installed", ui.status?.claudeCodeVersion?.let { "Claude Code $it" }).joinToString(" · ")
                else "The app has Termux install Node.js, Claude Code and a small bridge. It needs an internet connection and a few minutes; keep this screen open.",
                color = extra.muted,
                fontSize = 14.sp,
            )
        }

        Step(3, "Sign in to Claude", ui.signedIn) {
            val account = ui.status?.account
            Text(
                when {
                    ui.signedIn -> listOfNotNull(account?.email, account?.subscriptionType).joinToString(" · ").ifEmpty { "Signed in" }
                    !ui.connected -> "Available once Claude Code is installed."
                    ui.statusError != null -> "Could not check: ${ui.statusError}"
                    ui.status == null -> "Checking…"
                    else -> "Tap Sign in. Termux opens Claude's sign-in page; when you are done it brings you back here."
                },
                color = extra.muted,
                fontSize = 14.sp,
            )
            if (ui.connected && !ui.signedIn) TextButton(onClick = viewModel::checkAccount) { Text("Check again") }
        }

        ConnectionLine(ui)
        ui.message?.let { Text(it, color = extra.muted, fontSize = 13.sp) }

        Button(enabled = ui.action != SetupAction.Working, onClick = { next.run() }, modifier = Modifier.fillMaxWidth()) {
            Text(
                when (ui.action) {
                    SetupAction.GetTermux -> "Get Termux"
                    SetupAction.GrantPermission, SetupAction.LinkTermux, SetupAction.RunSetup -> when {
                        ui.action == SetupAction.GrantPermission && promptBlocked -> "Open app settings"
                        ui.setup is SetupState.Failed -> "Try again"
                        else -> "Set up"
                    }
                    SetupAction.Working -> if (ui.connected) "Checking…" else "Setting up…"
                    SetupAction.SignIn -> if (!ui.runCommandGranted && promptBlocked) "Open app settings" else "Sign in"
                    SetupAction.Done -> "Continue"
                },
            )
        }

        if (Build.VERSION.SDK_INT >= 33) {
            TextButton(onClick = { askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                Text("Allow notifications when Claude finishes or needs permission")
            }
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
        is ConnectionState.Unauthorized -> MaterialTheme.colorScheme.error to "The bridge rejected this app's token. Run setup again."
        is ConnectionState.Unreachable -> extraColors.muted to "Claude Code is not running yet (${c.reason})"
        else -> extraColors.muted to "Looking for Claude Code…"
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
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("If automatic setup does not work, paste this command into Termux yourself. It only works while this screen is open.", color = extraColors.muted, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val command = ui.setupCommand
            OutlinedButton(enabled = command != null, onClick = { command?.let { clipboard.setText(AnnotatedString(it)) } }) { Text("Copy setup command") }
            OutlinedButton(enabled = ui.termuxInstalled, onClick = { TermuxLauncher.open(context) }) { Text("Open Termux") }
        }
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
