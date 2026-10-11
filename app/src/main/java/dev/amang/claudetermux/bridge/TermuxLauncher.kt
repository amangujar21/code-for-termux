package dev.amang.claudetermux.bridge

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Talks to the Termux app through its RUN_COMMAND intent (needs `allow-external-apps = true` there). */
object TermuxLauncher {
    const val PACKAGE = "com.termux"
    const val PERMISSION = "com.termux.permission.RUN_COMMAND"
    private const val SERVICE = "com.termux.app.RunCommandService"
    const val HOME = "/data/data/com.termux/files/home"
    private const val BASH = "/data/data/com.termux/files/usr/bin/bash"
    private const val START_SCRIPT = "$HOME/.claude-bridge/start-bridge.sh"

    fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /**
     * False for Termux builds that other apps cannot run commands in. The Play Store build is one:
     * it ships without the RUN_COMMAND permission and service.
     */
    fun supportsRunCommand(context: Context): Boolean = try {
        context.packageManager.getPermissionInfo(PERMISSION, 0)
        context.packageManager.getServiceInfo(ComponentName(PACKAGE, SERVICE), 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Asks Termux to start the bridge in the background. Returns a reason when that is not possible. */
    fun startBridge(context: Context, restart: Boolean = false): String? =
        run(context, START_SCRIPT, if (restart) arrayOf("--restart") else emptyArray(), background = true)

    /** Runs the one-time setup in the background; the script reports back to [SetupServer]. */
    fun runSetup(context: Context, command: String): String? =
        run(context, BASH, arrayOf("-c", command), background = true)

    /** Opens a Termux session that signs in to Claude and then returns to [returnTo] (a component name). */
    fun openSignIn(context: Context, returnTo: String): String? {
        val script = "printf '\\nSign in to Claude. Follow the prompts below.\\n\\n'; " +
            "if claude auth login; then printf '\\nSigned in. Returning to the app...\\n'; am start -n '$returnTo' >/dev/null 2>&1; sleep 1; " +
            "else printf '\\nSign-in did not finish. Press Enter to close, then try again from the app.\\n'; read -r _; fi"
        run(context, BASH, arrayOf("-c", script), background = false)?.let { return it }
        // Termux may not be allowed to bring itself forward from the background, so do it for it.
        open(context)
        return null
    }

    private fun run(context: Context, path: String, arguments: Array<String>, background: Boolean): String? {
        if (!isInstalled(context)) return "Termux is not installed"
        if (!hasPermission(context)) return "Permission to run commands in Termux has not been granted"
        val intent = Intent("com.termux.RUN_COMMAND").apply {
            setClassName(PACKAGE, SERVICE)
            putExtra("com.termux.RUN_COMMAND_PATH", path)
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arguments)
            putExtra("com.termux.RUN_COMMAND_WORKDIR", HOME)
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", background)
            // Switch to the new session and show it.
            if (!background) putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
        }
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
            null
        } catch (e: Exception) {
            e.message ?: "Termux refused the request"
        }
    }

    fun open(context: Context): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(PACKAGE) ?: return false
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
}
