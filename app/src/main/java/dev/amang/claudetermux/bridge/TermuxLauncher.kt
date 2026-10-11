package dev.amang.claudetermux.bridge

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
    private const val HOME = "/data/data/com.termux/files/home"
    private const val START_SCRIPT = "$HOME/.claude-bridge/start-bridge.sh"

    fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Asks Termux to start the bridge in the background. Returns a reason when that is not possible. */
    fun startBridge(context: Context, restart: Boolean = false): String? {
        if (!isInstalled(context)) return "Termux is not installed"
        if (!hasPermission(context)) return "Permission to run commands in Termux has not been granted"
        val intent = Intent("com.termux.RUN_COMMAND").apply {
            setClassName(PACKAGE, SERVICE)
            putExtra("com.termux.RUN_COMMAND_PATH", START_SCRIPT)
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", if (restart) arrayOf("--restart") else emptyArray())
            putExtra("com.termux.RUN_COMMAND_WORKDIR", HOME)
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
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
