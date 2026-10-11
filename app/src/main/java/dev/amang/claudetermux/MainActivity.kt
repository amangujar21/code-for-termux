package dev.amang.claudetermux

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.amang.claudetermux.bridge.ConnectionState
import dev.amang.claudetermux.bridge.TermuxLauncher
import dev.amang.claudetermux.ui.chat.ChatScreen
import dev.amang.claudetermux.ui.onboarding.OnboardingScreen
import dev.amang.claudetermux.ui.sessions.SessionsScreen
import dev.amang.claudetermux.ui.theme.AppTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

class MainActivity : ComponentActivity() {
    /** Session to open because the user tapped a notification. */
    private var pendingSession by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) pendingSession = intent.getStringExtra(EXTRA_SESSION_ID)
        val app = application as App
        setContent {
            AppTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val onboarded by produceState<Boolean?>(null) { value = app.settings.flow.first().onboarded }
                    onboarded?.let { ready ->
                        val nav = rememberNavController()
                        AppNavigation(nav, start = if (ready) "sessions" else "onboarding")
                        BridgeAutoStart(app)
                        LaunchedEffect(pendingSession) {
                            val id = pendingSession ?: return@LaunchedEffect
                            pendingSession = null
                            if (ready) nav.navigate(chatRoute(id, null, null)) { launchSingleTop = true }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingSession = intent.getStringExtra(EXTRA_SESSION_ID)
    }

    companion object {
        const val EXTRA_SESSION_ID = "session_id"
    }
}

private fun chatRoute(sessionId: String?, cwd: String?, title: String?) =
    "chat/${sessionId ?: "new"}?cwd=${Uri.encode(cwd.orEmpty())}&title=${Uri.encode(title.orEmpty())}"

@Composable
private fun AppNavigation(nav: NavHostController, start: String) {
    NavHost(nav, startDestination = start) {
        composable("onboarding") {
            OnboardingScreen(viewModel()) {
                nav.navigate("sessions") {
                    popUpTo(0) { inclusive = true }
                }
            }
        }
        composable("sessions") {
            SessionsScreen(
                viewModel = viewModel(),
                onOpen = { nav.navigate(chatRoute(it.sessionId, it.cwd, it.title)) },
                onNew = { cwd -> nav.navigate(chatRoute(null, cwd, null)) },
                onSettings = { nav.navigate("onboarding") },
            )
        }
        composable(
            "chat/{sessionId}?cwd={cwd}&title={title}",
            arguments = listOf(
                navArgument("sessionId") { type = NavType.StringType },
                navArgument("cwd") { type = NavType.StringType; defaultValue = "" },
                navArgument("title") { type = NavType.StringType; defaultValue = "" },
            ),
        ) {
            ChatScreen(
                viewModel = viewModel(),
                onBack = { if (!nav.popBackStack()) nav.navigate("sessions") },
                onNewSession = { cwd ->
                    nav.navigate(chatRoute(null, cwd, null)) {
                        popUpTo("sessions")
                    }
                },
            )
        }
    }
}

/** If the bridge isn't running (e.g. after a reboot), ask Termux to start it, once per app launch. */
@Composable
private fun BridgeAutoStart(app: App) {
    val state by app.bridge.state.collectAsStateWithLifecycle()
    var attempted by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        if (state !is ConnectionState.Unreachable || attempted) return@LaunchedEffect
        if (!app.settings.current().onboarded) return@LaunchedEffect
        attempted = true
        if (TermuxLauncher.startBridge(app) == null) {
            delay(2500)
            app.bridge.retryNow()
        }
    }
}
