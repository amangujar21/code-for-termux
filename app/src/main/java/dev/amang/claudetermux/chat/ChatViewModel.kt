package dev.amang.claudetermux.chat

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dev.amang.claudetermux.App
import dev.amang.claudetermux.MainActivity
import dev.amang.claudetermux.bridge.AccountInfo
import dev.amang.claudetermux.bridge.BridgeJson
import dev.amang.claudetermux.bridge.ConnectionState
import dev.amang.claudetermux.bridge.ContextUsage
import dev.amang.claudetermux.bridge.McpStatus
import dev.amang.claudetermux.bridge.ModelInfo
import dev.amang.claudetermux.bridge.OutgoingAttachment
import dev.amang.claudetermux.bridge.SessionEvent
import dev.amang.claudetermux.bridge.SessionList
import dev.amang.claudetermux.bridge.SessionOpened
import dev.amang.claudetermux.bridge.SlashCommand
import dev.amang.claudetermux.bridge.TermuxLauncher
import dev.amang.claudetermux.bridge.arr
import dev.amang.claudetermux.bridge.asTextOrNull
import dev.amang.claudetermux.bridge.obj
import dev.amang.claudetermux.bridge.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream

data class PendingAttachment(val uri: Uri, val name: String, val mimeType: String) {
    val isImage: Boolean get() = mimeType.startsWith("image/")
}

data class ChatUi(
    val sessionId: String? = null,
    val title: String = "New session",
    val cwd: String = "",
    val chat: ChatState = ChatState(),
    val models: List<ModelInfo> = emptyList(),
    val commands: List<SlashCommand> = emptyList(),
    val account: AccountInfo = AccountInfo(),
    val opening: Boolean = true,
    val error: String? = null,
    val connected: Boolean = false,
    val sending: Boolean = false,
) {
    val project: String get() = cwd.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\').ifEmpty { "home" }
}

/** One-shot requests from the view-model to the screen. */
sealed interface ChatEffect {
    data object ShowModelSheet : ChatEffect
    data object ShowModeSheet : ChatEffect
    data object ShowContextSheet : ChatEffect
    data object ShowMcpSheet : ChatEffect
    data object ShowRename : ChatEffect
    data object GoToSessions : ChatEffect
    data class NewSession(val cwd: String) : ChatEffect
    data class Toast(val message: String) : ChatEffect
}

class ChatViewModel(application: Application, saved: SavedStateHandle) : AndroidViewModel(application) {
    private val app = application as App
    private val bridge = app.bridge
    private val requestedSessionId: String? = saved.get<String>("sessionId")?.takeIf { it != "new" }
    private val requestedCwd: String? = saved.get<String>("cwd")?.takeIf { it.isNotEmpty() }

    private val _ui = MutableStateFlow(ChatUi(sessionId = requestedSessionId, title = saved.get<String>("title").orEmpty().ifEmpty { "New session" }))
    val ui: StateFlow<ChatUi> = _ui

    private val _effects = Channel<ChatEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    /** Commands whose behaviour only makes sense in a terminal; reported by Claude Code itself. */
    private var terminalOnly: Set<String> = emptySet()
    private var allCommands: List<SlashCommand> = emptyList()
    private val queuedWhileOpening = ArrayList<SessionEvent>()
    private var opening = false

    init {
        viewModelScope.launch {
            bridge.state.collect { state ->
                val connected = state is ConnectionState.Connected
                _ui.update { it.copy(connected = connected) }
                if (connected) open()
            }
        }
        viewModelScope.launch {
            bridge.events.collect { event ->
                if (event.sessionId != _ui.value.sessionId) return@collect
                if (opening) queuedWhileOpening += event else apply(event.seq, event.event)
            }
        }
    }

    private suspend fun open() {
        opening = true
        _ui.update { it.copy(opening = it.chat.items.isEmpty(), error = null) }
        try {
            val settings = app.settings.current()
            val current = _ui.value
            val opened = bridge.request("session.open", SessionOpened.serializer(), timeoutMs = 90_000) {
                current.sessionId?.let { put("sessionId", it) }
                (requestedCwd ?: settings.lastCwd)?.let { if (current.sessionId == null) put("cwd", it) }
                if (current.sessionId == null || current.chat.lastSeq == 0L) {
                    settings.model?.let { put("model", it) }
                    settings.effort?.let { put("effort", it) }
                    put("mode", settings.mode)
                }
                put("afterSeq", current.chat.lastSeq)
            }
            var chat = if (opened.history != null) {
                ChatReducer.fromHistory(opened.history, current.chat).copy(lastSeq = 0)
            } else current.chat
            if (opened.history != null && opened.events.isNotEmpty()) {
                chat = chat.copy(lastSeq = opened.events.first().seq - 1)
            }
            for (e in opened.events) {
                noteInit(e.event)
                chat = ChatReducer.reduce(chat, e.seq, e.event)
            }
            chat = chat.copy(
                lastSeq = maxOf(chat.lastSeq, opened.seq),
                runState = when (opened.state) {
                    "running" -> RunState.Running
                    "waiting_permission" -> RunState.WaitingPermission
                    else -> RunState.Idle
                },
                model = opened.model ?: chat.model ?: settings.model,
                mode = opened.mode,
                effort = opened.effort ?: settings.effort,
            )
            allCommands = opened.commands
            _ui.update {
                it.copy(
                    sessionId = opened.sessionId,
                    cwd = opened.cwd,
                    chat = chat,
                    models = opened.models,
                    commands = visibleCommands(),
                    account = opened.account,
                    opening = false,
                )
            }
            app.settings.setLastCwd(opened.cwd)
            if (requestedSessionId != null && _ui.value.title == "New session") refreshTitle()
        } catch (e: Exception) {
            _ui.update { it.copy(opening = false, error = e.message ?: "Could not open the session") }
        } finally {
            opening = false
            val queued = queuedWhileOpening.toList()
            queuedWhileOpening.clear()
            queued.forEach { apply(it.seq, it.event) }
        }
    }

    fun retry() {
        viewModelScope.launch { if (bridge.state.value is ConnectionState.Connected) open() else bridge.retryNow() }
    }

    private fun apply(seq: Long, event: kotlinx.serialization.json.JsonObject) {
        noteInit(event)
        val before = _ui.value.chat
        val after = ChatReducer.reduce(before, seq, event)
        _ui.update { it.copy(chat = after, commands = visibleCommands()) }
        val id = _ui.value.sessionId ?: return
        if (after.runState != before.runState) app.turns.onRunState(id, _ui.value.title, after.runState)
        if (after.turnsCompleted != before.turnsCompleted) viewModelScope.launch { refreshTitle() }
    }

    private fun noteInit(event: kotlinx.serialization.json.JsonObject) {
        val message = event.obj("message") ?: return
        if (event.str("type") != "sdk" || message.str("subtype") != "init") return
        message.arr("terminal_slash_commands")?.let { list -> terminalOnly = list.mapNotNull { it.asTextOrNull() }.toSet() }
    }

    private fun visibleCommands(): List<SlashCommand> =
        (NativeCommands.all + allCommands.filter { it.name !in NativeCommands.names })
            .filter { it.name !in terminalOnly && !it.name.startsWith("__") }
            .distinctBy { it.name }

    private suspend fun refreshTitle() {
        val id = _ui.value.sessionId ?: return
        runCatching {
            val list = bridge.request("sessions.list", SessionList.serializer()) { put("dir", _ui.value.cwd) }
            list.sessions.firstOrNull { it.sessionId == id }?.let { row -> _ui.update { it.copy(title = row.title) } }
        }
    }

    fun send(text: String, attachments: List<PendingAttachment>) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() && attachments.isEmpty()) return
        if (attachments.isEmpty() && handleNativeCommand(trimmed)) return
        val id = _ui.value.sessionId ?: return
        viewModelScope.launch {
            _ui.update { it.copy(sending = true) }
            try {
                val encoded = withContext(Dispatchers.IO) { attachments.map(::encode) }
                bridge.send("user.message", timeoutMs = 120_000) {
                    put("sessionId", id)
                    put("text", trimmed)
                    put("attachments", BridgeJson.encodeToJsonElement(ListSerializer(OutgoingAttachment.serializer()), encoded))
                }
            } catch (e: Exception) {
                _effects.send(ChatEffect.Toast(e.message ?: "Could not send the message"))
            } finally {
                _ui.update { it.copy(sending = false) }
            }
        }
    }

    /** Commands that only exist as terminal UI in Claude Code get a native equivalent here. */
    private fun handleNativeCommand(text: String): Boolean {
        if (!text.startsWith("/")) return false
        val name = text.drop(1).substringBefore(' ').lowercase()
        val argument = text.substringAfter(' ', "").trim()
        val effect: ChatEffect = when (name) {
            "model", "effort" -> ChatEffect.ShowModelSheet
            "permissions", "mode" -> ChatEffect.ShowModeSheet
            "context" -> ChatEffect.ShowContextSheet
            "mcp" -> ChatEffect.ShowMcpSheet
            "resume", "sessions", "exit", "quit" -> ChatEffect.GoToSessions
            "clear", "new" -> ChatEffect.NewSession(_ui.value.cwd)
            "rename" -> if (argument.isEmpty()) ChatEffect.ShowRename else {
                rename(argument)
                return true
            }
            "plan" -> {
                setMode("plan")
                return true
            }
            "login" -> {
                signIn()
                return true
            }
            else -> return false
        }
        _effects.trySend(effect)
        return true
    }

    private fun encode(attachment: PendingAttachment): OutgoingAttachment {
        val resolver = getApplication<Application>().contentResolver
        if (attachment.isImage) {
            // Phone photos are far larger than the model can use; shrink before sending.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(attachment.uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 3136) sample *= 2
            val bitmap = resolver.openInputStream(attachment.uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: error("Could not read ${attachment.name}")
            val longest = maxOf(bitmap.width, bitmap.height)
            val scaled = if (longest > 1568) {
                val ratio = 1568f / longest
                Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt(), (bitmap.height * ratio).toInt(), true)
            } else bitmap
            val bytes = ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray()
            return OutgoingAttachment("image", attachment.name, "image/jpeg", Base64.encodeToString(bytes, Base64.NO_WRAP))
        }
        val bytes = resolver.openInputStream(attachment.uri)?.use { it.readBytes() } ?: error("Could not read ${attachment.name}")
        require(bytes.size <= 25 * 1024 * 1024) { "${attachment.name} is larger than 25 MB" }
        return OutgoingAttachment("file", attachment.name, attachment.mimeType, Base64.encodeToString(bytes, Base64.NO_WRAP))
    }

    fun describe(uri: Uri): PendingAttachment {
        val resolver = getApplication<Application>().contentResolver
        var name = uri.lastPathSegment ?: "file"
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { name = it }
            }
        }
        return PendingAttachment(uri, name, resolver.getType(uri) ?: "application/octet-stream")
    }

    private fun sessionCall(type: String, onError: String, params: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}) {
        val id = _ui.value.sessionId ?: return
        viewModelScope.launch {
            try {
                bridge.send(type) {
                    put("sessionId", id)
                    params()
                }
            } catch (e: Exception) {
                _effects.send(ChatEffect.Toast(e.message ?: onError))
            }
        }
    }

    /**
     * Opens Termux on Claude's sign-in prompt, which brings the user back here afterwards. Claude Code
     * rereads its credentials for every turn, so the open session works again without a restart.
     */
    fun signIn() {
        val problem = TermuxLauncher.openSignIn(app, "${app.packageName}/${MainActivity::class.java.name}")
        if (problem != null) _effects.trySend(ChatEffect.Toast("$problem. You can also open Termux and run: claude auth login"))
    }

    fun stop() = sessionCall("interrupt", "Could not stop")

    fun answerPermission(requestId: String, decision: String) =
        sessionCall("permission.response", "Could not send your decision") {
            put("requestId", requestId)
            put("decision", decision)
        }

    fun setModel(model: String) {
        sessionCall("set.model", "Could not switch model") { put("model", model) }
        viewModelScope.launch { app.settings.setModel(model) }
    }

    fun setEffort(effort: String?) {
        sessionCall("set.effort", "Could not change effort") { put("effort", effort) }
        viewModelScope.launch { app.settings.setEffort(effort) }
    }

    fun setMode(mode: String) {
        sessionCall("set.mode", "Could not change permission mode") { put("mode", mode) }
        // Bypass is deliberately not remembered: new sessions should never start with it.
        if (mode != "bypassPermissions") viewModelScope.launch { app.settings.setMode(mode) }
    }

    fun rename(title: String) {
        val clean = title.trim()
        if (clean.isEmpty()) return
        sessionCall("session.rename", "Could not rename") { put("title", clean) }
        _ui.update { it.copy(title = clean) }
    }

    suspend fun contextUsage(): Result<ContextUsage> = runCatching {
        bridge.request("context.usage", ContextUsage.serializer()) { put("sessionId", _ui.value.sessionId) }
    }

    suspend fun mcpStatus(): Result<McpStatus> = runCatching {
        bridge.request("mcp.status", McpStatus.serializer()) { put("sessionId", _ui.value.sessionId) }
    }

    override fun onCleared() {
        val id = _ui.value.sessionId ?: return
        // The session keeps running in the bridge; we only stop listening.
        app.turns.forget(id)
        app.scope.launch { runCatching { bridge.send("session.leave") { put("sessionId", id) } } }
    }
}

/** Slash commands the app implements itself, shown first in the command popup. */
object NativeCommands {
    val all = listOf(
        SlashCommand("model", "Switch model and effort", builtin = true),
        SlashCommand("clear", "Start a new session in this project", builtin = true),
        SlashCommand("resume", "Go back to the session list", builtin = true),
        SlashCommand("permissions", "Change the permission mode", builtin = true),
        SlashCommand("context", "Show context window usage", builtin = true),
        SlashCommand("mcp", "Show MCP server status", builtin = true),
        SlashCommand("rename", "Rename this session", "[title]", builtin = true),
        SlashCommand("login", "Sign in to Claude", builtin = true),
    )
    val names = setOf("model", "effort", "clear", "new", "resume", "permissions", "context", "mcp", "rename", "login", "exit", "quit")
}
