package dev.amang.claudetermux.ui.sessions

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.amang.claudetermux.App
import dev.amang.claudetermux.bridge.ConnectionState
import dev.amang.claudetermux.bridge.FolderListing
import dev.amang.claudetermux.bridge.SessionList
import dev.amang.claudetermux.bridge.SessionRow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.put

data class SessionsUi(
    val connection: ConnectionState = ConnectionState.Idle,
    val sessions: List<SessionRow> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val home: String = "",
    val lastCwd: String? = null,
) {
    /** Sessions grouped by project folder, most recently used project first. */
    fun grouped(query: String): List<Pair<String, List<SessionRow>>> {
        val q = query.trim().lowercase()
        return sessions
            .filter { q.isEmpty() || it.title.lowercase().contains(q) || it.cwd.orEmpty().lowercase().contains(q) }
            .groupBy { it.cwd.orEmpty() }
            .map { (cwd, rows) -> cwd to rows.sortedByDescending { it.lastModified } }
            .sortedByDescending { it.second.first().lastModified }
    }

    val recentFolders: List<String>
        get() = sessions.sortedByDescending { it.lastModified }.mapNotNull { it.cwd?.takeIf(String::isNotEmpty) }.distinct().take(6)
}

class SessionsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as App
    private val bridge = app.bridge

    private val _ui = MutableStateFlow(SessionsUi())
    val ui: StateFlow<SessionsUi> = _ui

    init {
        viewModelScope.launch {
            bridge.state.collect { state ->
                _ui.update { it.copy(connection = state, home = (state as? ConnectionState.Connected)?.hello?.home ?: it.home) }
                if (state is ConnectionState.Connected) refresh()
            }
        }
        viewModelScope.launch { app.settings.flow.collect { s -> _ui.update { it.copy(lastCwd = s.lastCwd) } } }
    }

    fun refresh() {
        viewModelScope.launch {
            if (bridge.state.value !is ConnectionState.Connected) return@launch
            _ui.update { it.copy(loading = it.sessions.isEmpty(), error = null) }
            try {
                val list = bridge.request("sessions.list", SessionList.serializer())
                _ui.update { it.copy(sessions = list.sessions, loading = false) }
            } catch (e: Exception) {
                _ui.update { it.copy(loading = false, error = e.message) }
            }
        }
    }

    fun rename(session: SessionRow, title: String) = mutate("session.rename", session) { put("title", title) }

    fun delete(session: SessionRow) = mutate("session.delete", session)

    private fun mutate(type: String, session: SessionRow, params: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}) {
        viewModelScope.launch {
            try {
                bridge.send(type) {
                    put("sessionId", session.sessionId)
                    params()
                }
            } catch (e: Exception) {
                _ui.update { it.copy(error = e.message) }
            }
            refresh()
        }
    }

    suspend fun listFolder(path: String?): Result<FolderListing> = runCatching {
        bridge.request("fs.list", FolderListing.serializer()) { path?.let { put("path", it) } }
    }

    suspend fun createFolder(path: String): Result<Unit> = runCatching {
        bridge.send("fs.mkdir") { put("path", path) }
    }

    fun retryConnection() = bridge.retryNow()
}
