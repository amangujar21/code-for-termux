package dev.amang.claudetermux.bridge

import dev.amang.claudetermux.BuildConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Wire format shared with bridge/server.js. See bridge/protocol.md. */
val BridgeJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

@Serializable
data class Hello(
    val bridgeVersion: String = "",
    val protocolVersion: Int = 0,
    val home: String = "",
    val platform: String = "",
) {
    /** True after an app update, until the bridge in Termux has been replaced with the one this app ships. */
    val outdated: Boolean get() = bridgeVersion.isNotEmpty() && bridgeVersion != BuildConfig.VERSION_NAME
}

@Serializable
data class SlashCommand(
    val name: String,
    val description: String = "",
    val argumentHint: String = "",
    val aliases: List<String> = emptyList(),
    val builtin: Boolean = false,
)

@Serializable
data class ModelInfo(
    val value: String,
    val resolvedModel: String? = null,
    val displayName: String = "",
    val description: String = "",
    val supportedEffortLevels: List<String> = emptyList(),
)

@Serializable
data class AccountInfo(
    val email: String? = null,
    val organization: String? = null,
    val subscriptionType: String? = null,
    val tokenSource: String? = null,
    val apiKeySource: String? = null,
    val apiProvider: String? = null,
) {
    /** Claude Code reports the sources as "none", rather than leaving them out, when nobody is signed in. */
    val signedIn: Boolean
        get() = email != null || tokenSource.isSource() || apiKeySource.isSource() ||
            (apiProvider != null && apiProvider != "firstParty")

    private fun String?.isSource() = !isNullOrEmpty() && this != "none"
}

@Serializable
data class BridgeStatus(
    val account: AccountInfo = AccountInfo(),
    val models: List<ModelInfo> = emptyList(),
    val commands: List<SlashCommand> = emptyList(),
    val claudeCodeVersion: String? = null,
)

@Serializable
data class SessionRow(
    val sessionId: String,
    val summary: String = "",
    val lastModified: Long = 0,
    val customTitle: String? = null,
    val firstPrompt: String? = null,
    val gitBranch: String? = null,
    val cwd: String? = null,
    /** Non-null while the bridge is keeping this session's process alive. */
    val state: String? = null,
) {
    val title: String get() = customTitle?.takeIf { it.isNotBlank() } ?: summary.ifBlank { "Untitled" }
}

@Serializable
data class SessionList(val sessions: List<SessionRow> = emptyList())

@Serializable
data class SequencedEvent(val seq: Long, val event: JsonObject)

@Serializable
data class SessionOpened(
    val sessionId: String,
    val cwd: String = "",
    val state: String = "idle",
    val model: String? = null,
    val mode: String = "default",
    val effort: String? = null,
    val seq: Long = 0,
    val commands: List<SlashCommand> = emptyList(),
    val models: List<ModelInfo> = emptyList(),
    val account: AccountInfo = AccountInfo(),
    val claudeCodeVersion: String? = null,
    /** Null when the client's copy is still valid. */
    val history: List<JsonObject>? = null,
    val events: List<SequencedEvent> = emptyList(),
    val gap: Boolean = false,
)

/** The phone's own storage, which Termux can only open after the user grants it the storage permission. */
@Serializable
data class SharedStorage(val path: String, val accessible: Boolean = false)

@Serializable
data class FolderListing(
    val path: String,
    val parent: String? = null,
    val home: String = "",
    val storage: SharedStorage? = null,
    val dirs: List<String> = emptyList(),
)

@Serializable
data class ContextCategory(val name: String, val tokens: Long = 0, val kind: String = "used")

@Serializable
data class ContextUsage(
    val categories: List<ContextCategory> = emptyList(),
    val totalTokens: Long = 0,
    val maxTokens: Long = 0,
    val percentage: Double = 0.0,
    val model: String = "",
)

@Serializable
data class McpServer(
    val name: String,
    val status: String = "",
    val error: String? = null,
    val scope: String? = null,
)

@Serializable
data class McpStatus(val servers: List<McpServer> = emptyList())

@Serializable
data class OutgoingAttachment(
    val kind: String,
    val name: String? = null,
    val mediaType: String? = null,
    val data: String,
)

/** A push from the bridge about one session. */
data class SessionEvent(val sessionId: String, val seq: Long, val event: JsonObject)

fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray
fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.contentOrNull == "true"
fun JsonElement.asTextOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.jsonPrimitive?.content
