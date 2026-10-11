package dev.amang.claudetermux.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.SecureRandom

private val Context.dataStore by preferencesDataStore("settings")

data class SettingsSnapshot(
    val token: String,
    val host: String,
    val port: Int,
    val onboarded: Boolean,
    val termuxLinked: Boolean,
    val model: String?,
    val effort: String?,
    val mode: String,
    val lastCwd: String?,
    val autoSpeak: Boolean,
)

class Settings(private val context: Context) {
    private object Keys {
        val token = stringPreferencesKey("token")
        val host = stringPreferencesKey("host")
        val port = intPreferencesKey("port")
        val onboarded = booleanPreferencesKey("onboarded")
        val termuxLinked = booleanPreferencesKey("termux_linked")
        val model = stringPreferencesKey("model")
        val effort = stringPreferencesKey("effort")
        val mode = stringPreferencesKey("mode")
        val lastCwd = stringPreferencesKey("last_cwd")
        val autoSpeak = booleanPreferencesKey("auto_speak")
    }

    val flow: Flow<SettingsSnapshot> = context.dataStore.data.map { p ->
        SettingsSnapshot(
            token = p[Keys.token].orEmpty(),
            host = p[Keys.host] ?: DEFAULT_HOST,
            port = p[Keys.port] ?: DEFAULT_PORT,
            onboarded = p[Keys.onboarded] ?: false,
            termuxLinked = p[Keys.termuxLinked] ?: false,
            model = p[Keys.model],
            effort = p[Keys.effort],
            mode = p[Keys.mode] ?: "default",
            lastCwd = p[Keys.lastCwd],
            autoSpeak = p[Keys.autoSpeak] ?: false,
        )
    }

    suspend fun current(): SettingsSnapshot = flow.first()

    /** The pairing token is created once on this device and handed to Termux during setup. */
    suspend fun ensureToken(): String {
        val existing = current().token
        if (existing.isNotEmpty()) return existing
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val token = bytes.joinToString("") { "%02x".format(it) }
        context.dataStore.edit { it[Keys.token] = token }
        return token
    }

    suspend fun setConnection(host: String, port: Int, token: String? = null) = context.dataStore.edit {
        it[Keys.host] = host
        it[Keys.port] = port
        if (token != null) it[Keys.token] = token
    }

    suspend fun setOnboarded(value: Boolean) = context.dataStore.edit { it[Keys.onboarded] = value }

    /** Set once the app has turned on Termux's allow-external-apps setting. */
    suspend fun setTermuxLinked(value: Boolean) = context.dataStore.edit { it[Keys.termuxLinked] = value }

    suspend fun setModel(model: String?) = context.dataStore.edit {
        if (model == null) it.remove(Keys.model) else it[Keys.model] = model
    }

    suspend fun setEffort(effort: String?) = context.dataStore.edit {
        if (effort == null) it.remove(Keys.effort) else it[Keys.effort] = effort
    }

    suspend fun setMode(mode: String) = context.dataStore.edit { it[Keys.mode] = mode }

    suspend fun setLastCwd(cwd: String) = context.dataStore.edit { it[Keys.lastCwd] = cwd }

    suspend fun setAutoSpeak(value: Boolean) = context.dataStore.edit { it[Keys.autoSpeak] = value }

    companion object {
        const val DEFAULT_HOST = "127.0.0.1"
        const val DEFAULT_PORT = 8765
    }
}
