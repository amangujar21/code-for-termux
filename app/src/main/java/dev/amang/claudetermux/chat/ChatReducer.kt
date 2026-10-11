package dev.amang.claudetermux.chat

import dev.amang.claudetermux.bridge.arr
import dev.amang.claudetermux.bridge.bool
import dev.amang.claudetermux.bridge.obj
import dev.amang.claudetermux.bridge.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull

sealed interface ChatItem {
    val key: String

    data class User(override val key: String, val text: String, val attachments: List<String> = emptyList()) : ChatItem
    data class Text(override val key: String, val markdown: String, val streaming: Boolean = false) : ChatItem
    data class Thinking(override val key: String, val text: String, val streaming: Boolean = false) : ChatItem
    data class Tool(
        override val key: String,
        val toolUseId: String,
        val name: String,
        val input: JsonObject,
        val result: String? = null,
        val isError: Boolean = false,
        val done: Boolean = false,
    ) : ChatItem
    data class Permission(
        override val key: String,
        val requestId: String,
        val toolName: String,
        val input: JsonObject,
        val canAlwaysAllow: Boolean,
        val reason: String?,
        /** null while waiting; otherwise "allow" or "deny". */
        val outcome: String? = null,
    ) : ChatItem
    data class Notice(override val key: String, val text: String, val isError: Boolean = false) : ChatItem
    /** Claude Code refused the turn because nobody is signed in; the app offers to sign in instead. */
    data class SignIn(override val key: String) : ChatItem
}

enum class RunState { Idle, Running, WaitingPermission }

data class ChatState(
    val items: List<ChatItem> = emptyList(),
    /** Blocks of the assistant message currently being streamed, by content-block index. */
    val streaming: Map<Int, ChatItem> = emptyMap(),
    val runState: RunState = RunState.Idle,
    val model: String? = null,
    val mode: String = "default",
    val effort: String? = null,
    val lastSeq: Long = 0,
    /** Bumped whenever a turn finishes, so the UI can refresh titles/usage. */
    val turnsCompleted: Int = 0,
) {
    val visibleItems: List<ChatItem> get() = if (streaming.isEmpty()) items else items + streaming.toSortedMap().values
}

/** Folds transcript history and live bridge events into what the chat screen shows. Pure, so it is unit tested. */
object ChatReducer {
    private val hiddenUserPrefixes = listOf(
        "<command-name>", "<command-message>", "<local-command-stdout>", "<local-command-stderr>",
        "<local-command-caveat>", "<system-reminder>", "<task-notification>", "Caveat:",
    )

    fun fromHistory(history: List<JsonObject>, base: ChatState = ChatState()): ChatState {
        var state = base.copy(items = emptyList(), streaming = emptyMap())
        history.forEachIndexed { i, entry ->
            if (entry.str("parent_tool_use_id") != null) return@forEachIndexed
            val key = entry.str("uuid") ?: "h$i"
            val message = entry.obj("message") ?: return@forEachIndexed
            state = when (entry.str("type")) {
                "user" -> state.withUserMessage(key, message)
                "assistant" -> state.withAssistantMessage(key, entry, message)
                else -> state
            }
        }
        // Tools left without a result belong to turns that ended; don't show them spinning forever.
        return state.copy(items = state.items.map { if (it is ChatItem.Tool && !it.done) it.copy(done = true) else it })
    }

    fun reduce(state: ChatState, seq: Long, event: JsonObject): ChatState {
        if (seq <= state.lastSeq) return state
        val next = state.copy(lastSeq = seq)
        val key = "e$seq"
        return when (event.str("type")) {
            "sdk" -> event.obj("message")?.let { next.withSdkMessage(key, it) } ?: next
            "user.echo" -> next.copy(
                items = next.items + ChatItem.User(
                    key,
                    event.str("text").orEmpty(),
                    event.arr("attachments").orEmpty().mapNotNull { a ->
                        (a as? JsonObject)?.let { it.str("name") ?: it.str("kind") }
                    },
                ),
            )
            "state" -> next.copy(
                runState = when (event.str("state")) {
                    "running" -> RunState.Running
                    "waiting_permission" -> RunState.WaitingPermission
                    else -> RunState.Idle
                },
                model = event.str("model") ?: next.model,
                mode = event.str("mode") ?: next.mode,
                effort = event.str("effort"),
                streaming = if (event.str("state") == "idle") emptyMap() else next.streaming,
            )
            "permission.request" -> next.copy(
                items = next.items + ChatItem.Permission(
                    key = key,
                    requestId = event.str("requestId").orEmpty(),
                    toolName = event.str("toolName").orEmpty(),
                    input = event.obj("input") ?: JsonObject(emptyMap()),
                    canAlwaysAllow = !event.arr("suggestions").isNullOrEmpty(),
                    reason = event.str("decisionReason"),
                ),
            )
            "permission.resolved" -> next.copy(
                items = next.items.map {
                    if (it is ChatItem.Permission && it.requestId == event.str("requestId")) {
                        it.copy(outcome = event.str("behavior") ?: "deny")
                    } else it
                },
            )
            "error" -> next.copy(
                items = next.items + ChatItem.Notice(key, event.str("message") ?: "Something went wrong", isError = true),
                streaming = emptyMap(),
            )
            "closed" -> next.copy(runState = RunState.Idle, streaming = emptyMap())
            else -> next
        }
    }

    private fun ChatState.withSdkMessage(key: String, message: JsonObject): ChatState {
        val nested = message.str("parent_tool_use_id") != null
        return when (message.str("type")) {
            "stream_event" -> if (nested) this else withStreamEvent(key, message.obj("event") ?: return this)
            "assistant" -> if (nested) this else {
                withAssistantMessage(message.str("uuid") ?: key, message, message.obj("message") ?: return this)
                    .copy(streaming = emptyMap())
            }
            "user" -> if (nested) this else withToolResults(message.obj("message") ?: return this)
            "result" -> {
                val failed = message.str("subtype") != "success" || message.bool("is_error")
                val finished = copy(
                    streaming = emptyMap(),
                    turnsCompleted = turnsCompleted + 1,
                    items = items.map { if (it is ChatItem.Tool && !it.done) it.copy(done = true) else it },
                )
                // The sign-in card already says why the turn ended.
                if (!failed || finished.items.lastOrNull() is ChatItem.SignIn) finished
                else finished.copy(
                    items = finished.items + ChatItem.Notice(
                        key,
                        message.str("result")
                            ?: message.arr("errors")?.joinToString("\n") { it.text() }?.takeIf { it.isNotBlank() }
                            ?: "The turn ended with an error (${message.str("subtype")}).",
                        isError = true,
                    ),
                )
            }
            "system" -> when (message.str("subtype")) {
                "init" -> copy(model = message.str("model") ?: model, mode = message.str("permissionMode") ?: mode)
                "compact_boundary" -> copy(items = items + ChatItem.Notice(key, "Conversation compacted"))
                "local_command_output" -> {
                    val content = message.str("content")?.trim().orEmpty()
                    if (content.isEmpty()) this else copy(items = items + ChatItem.Text(key, content))
                }
                "api_retry" -> this
                else -> this
            }
            else -> this
        }
    }

    private fun ChatState.withStreamEvent(key: String, event: JsonObject): ChatState {
        val index = (event["index"] as? JsonPrimitive)?.intOrNull
        return when (event.str("type")) {
            "message_start" -> copy(streaming = emptyMap())
            "content_block_start" -> {
                index ?: return this
                val block = event.obj("content_block") ?: return this
                val item: ChatItem = when (block.str("type")) {
                    "text" -> ChatItem.Text("s$key", block.str("text").orEmpty(), streaming = true)
                    "thinking" -> ChatItem.Thinking("s$key", block.str("thinking").orEmpty(), streaming = true)
                    else -> return this
                }
                copy(streaming = streaming + (index to item))
            }
            "content_block_delta" -> {
                index ?: return this
                val delta = event.obj("delta") ?: return this
                val updated = when (val current = streaming[index]) {
                    is ChatItem.Text -> current.copy(markdown = current.markdown + delta.str("text").orEmpty())
                    is ChatItem.Thinking -> current.copy(text = current.text + delta.str("thinking").orEmpty())
                    else -> return this
                }
                copy(streaming = streaming + (index to updated))
            }
            else -> this
        }
    }

    private fun ChatState.withUserMessage(key: String, message: JsonObject): ChatState {
        val content = message["content"]
        if (content is JsonPrimitive) return withUserText(key, content.contentOrNull.orEmpty(), emptyList())
        if (content !is JsonArray) return this
        var state = withToolResults(message)
        val text = content.filterIsInstance<JsonObject>().filter { it.str("type") == "text" }
            .joinToString("\n") { it.str("text").orEmpty() }
        val images = content.filterIsInstance<JsonObject>().count { it.str("type") == "image" }
        if (text.isNotBlank() || images > 0) {
            state = state.withUserText(key, text, List(images) { "image" })
        }
        return state
    }

    private fun ChatState.withUserText(key: String, raw: String, attachments: List<String>): ChatState {
        val text = raw.trim()
        if (text.startsWith("[Request interrupted")) return copy(items = items + ChatItem.Notice(key, "Interrupted"))
        if (hiddenUserPrefixes.any { text.startsWith(it) }) return this
        if (text.isEmpty() && attachments.isEmpty()) return this
        return copy(items = items + ChatItem.User(key, text, attachments))
    }

    /** [envelope] is the SDK message or transcript entry that carries [message]. */
    private fun ChatState.withAssistantMessage(key: String, envelope: JsonObject, message: JsonObject): ChatState {
        // Claude Code answers "Please run /login" here, which is a terminal command the app replaces.
        if (envelope.str("error") != "authentication_failed") return withAssistantBlocks(key, message)
        return if (items.any { it.key == key }) this else copy(items = items + ChatItem.SignIn(key))
    }

    private fun ChatState.withAssistantBlocks(key: String, message: JsonObject): ChatState {
        val blocks = message.arr("content") ?: return this
        val added = blocks.filterIsInstance<JsonObject>().mapIndexedNotNull { i, block ->
            when (block.str("type")) {
                "text" -> block.str("text")?.takeIf { it.isNotBlank() }?.let { ChatItem.Text("$key:$i", it) }
                "thinking" -> block.str("thinking")?.takeIf { it.isNotBlank() }?.let { ChatItem.Thinking("$key:$i", it) }
                "tool_use" -> {
                    val id = block.str("id") ?: "$key:$i"
                    ChatItem.Tool("t$id", id, block.str("name").orEmpty(), block.obj("input") ?: JsonObject(emptyMap()))
                }
                else -> null
            }
        }
        // A replayed or duplicated message must not show twice.
        val existing = items.mapTo(HashSet()) { it.key }
        return copy(items = items + added.filter { it.key !in existing })
    }

    private fun ChatState.withToolResults(message: JsonObject): ChatState {
        val results = (message["content"] as? JsonArray)?.filterIsInstance<JsonObject>()
            ?.filter { it.str("type") == "tool_result" }.orEmpty()
        if (results.isEmpty()) return this
        val byId = results.associateBy { it.str("tool_use_id") }
        return copy(
            items = items.map { item ->
                val result = (item as? ChatItem.Tool)?.let { byId[it.toolUseId] } ?: return@map item
                item.copy(result = result["content"]?.text().orEmpty(), isError = result.bool("is_error"), done = true)
            },
        )
    }

    private fun JsonElement.text(): String = when (this) {
        is JsonPrimitive -> contentOrNull.orEmpty()
        is JsonArray -> filterIsInstance<JsonObject>().joinToString("\n") {
            when (it.str("type")) {
                "text" -> it.str("text").orEmpty()
                "image" -> "[image]"
                else -> ""
            }
        }
        else -> ""
    }
}
