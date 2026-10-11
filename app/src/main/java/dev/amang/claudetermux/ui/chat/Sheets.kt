package dev.amang.claudetermux.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.amang.claudetermux.bridge.ContextUsage
import dev.amang.claudetermux.bridge.McpStatus
import dev.amang.claudetermux.bridge.ModelInfo
import dev.amang.claudetermux.ui.theme.extraColors

val PermissionModes = listOf(
    Triple("default", "Default", "Ask before edits and commands"),
    Triple("acceptEdits", "Accept edits", "Edit files without asking; still ask for commands"),
    Triple("plan", "Plan", "Research and propose a plan without changing anything"),
    Triple("bypassPermissions", "Bypass permissions", "Never ask. Claude can run any command in Termux"),
)

fun modeLabel(mode: String) = PermissionModes.firstOrNull { it.first == mode }?.second ?: mode

fun effortLabel(effort: String?) = when (effort) {
    null -> null
    "xhigh" -> "Extra high"
    else -> effort.replaceFirstChar { it.uppercase() }
}

/** The row describing [model], which may be an alias ("opus") or a resolved id ("claude-opus-5-5"). */
fun findModel(models: List<ModelInfo>, model: String?): ModelInfo? {
    if (model == null) return models.firstOrNull { it.value == "default" } ?: models.firstOrNull()
    val bare = model.substringBefore('[')
    return models.firstOrNull { it.value == model }
        ?: models.firstOrNull { it.value != "default" && it.resolvedModel == bare }
        ?: models.firstOrNull { it.resolvedModel == bare }
}

fun modelLabel(models: List<ModelInfo>, model: String?): String {
    val info = findModel(models, model) ?: return model ?: "Model"
    // The default row is named "Default (recommended)"; its description leads with the real name.
    return if (info.value == "default") info.description.substringBefore(" · ").ifBlank { "Default" } else info.displayName
}

@Composable
private fun SheetTitle(text: String) {
    Text(text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
}

@Composable
private fun ChoiceRow(title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp)
            if (!subtitle.isNullOrBlank()) Text(subtitle, color = extraColors.muted, fontSize = 13.sp, lineHeight = 18.sp)
        }
        if (selected) Icon(Icons.Outlined.Check, "Selected", tint = MaterialTheme.colorScheme.primary)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSheet(
    models: List<ModelInfo>,
    currentModel: String?,
    currentEffort: String?,
    onModel: (String) -> Unit,
    onEffort: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val current = findModel(models, currentModel)
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            val efforts = current?.supportedEffortLevels.orEmpty()
            if (efforts.isNotEmpty()) {
                SheetTitle("Effort")
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    (listOf<String?>(null) + efforts).forEach { effort ->
                        val selected = effort == currentEffort
                        Text(
                            effortLabel(effort) ?: "Auto",
                            fontSize = 14.sp,
                            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .clip(RoundedCornerShape(18.dp))
                                .background(if (selected) MaterialTheme.colorScheme.primary else extraColors.chip)
                                .clickable { onEffort(effort) }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            SheetTitle("Model")
            models.forEach { model ->
                ChoiceRow(model.displayName, model.description, selected = model.value == current?.value) {
                    onModel(model.value)
                    onDismiss()
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModeSheet(current: String, onMode: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.navigationBarsPadding()) {
            SheetTitle("Permission mode")
            PermissionModes.forEach { (value, title, subtitle) ->
                ChoiceRow(title, subtitle, selected = value == current) {
                    onMode(value)
                    onDismiss()
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachSheet(onPhotos: () -> Unit, onCamera: () -> Unit, onFiles: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            AttachRow(Icons.Outlined.Image, "Photos", onPhotos)
            AttachRow(Icons.Outlined.PhotoCamera, "Camera", onCamera)
            AttachRow(Icons.Outlined.AttachFile, "Files", onFiles)
        }
    }
}

@Composable
private fun AttachRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = extraColors.muted)
        Spacer(Modifier.width(16.dp))
        Text(label, fontSize = 16.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContextSheet(load: suspend () -> Result<ContextUsage>, onDismiss: () -> Unit) {
    var result by remember { mutableStateOf<Result<ContextUsage>?>(null) }
    LaunchedEffect(Unit) { result = load() }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
            SheetTitle("Context window")
            val usage = result?.getOrNull()
            when {
                result == null -> Loading()
                usage == null -> ErrorText(result?.exceptionOrNull()?.message)
                else -> {
                    Column(Modifier.padding(horizontal = 20.dp)) {
                        Text("${formatTokens(usage.totalTokens)} of ${formatTokens(usage.maxTokens)} tokens · ${usage.percentage.toInt()}%", fontSize = 15.sp)
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { (usage.percentage / 100.0).toFloat().coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                        )
                        Spacer(Modifier.height(14.dp))
                        usage.categories.filter { it.tokens > 0 }.forEach { category ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Text(category.name, color = if (category.kind == "used") MaterialTheme.colorScheme.onSurface else extraColors.muted, fontSize = 14.sp, modifier = Modifier.weight(1f))
                                Text(formatTokens(category.tokens), color = extraColors.muted, fontSize = 14.sp)
                            }
                        }
                        if (usage.model.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(usage.model, color = extraColors.muted, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpSheet(load: suspend () -> Result<McpStatus>, onDismiss: () -> Unit) {
    var result by remember { mutableStateOf<Result<McpStatus>?>(null) }
    LaunchedEffect(Unit) { result = load() }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
            SheetTitle("MCP servers")
            val status = result?.getOrNull()
            when {
                result == null -> Loading()
                status == null -> ErrorText(result?.exceptionOrNull()?.message)
                status.servers.isEmpty() -> Text(
                    "No MCP servers are configured. Add them with `claude mcp add` in Termux.",
                    color = extraColors.muted,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                else -> status.servers.forEach { server ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        val color = when (server.status) {
                            "connected" -> extraColors.success
                            "failed" -> MaterialTheme.colorScheme.error
                            else -> extraColors.muted
                        }
                        Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(color))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(server.name, fontSize = 15.sp)
                            Text(listOfNotNull(server.status, server.scope, server.error).joinToString(" · "), color = extraColors.muted, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun ErrorText(message: String?) {
    Text(message ?: "Could not load", color = MaterialTheme.colorScheme.error, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp))
}

fun formatTokens(tokens: Long): String = when {
    tokens >= 1_000_000 -> "%.1fM".format(tokens / 1_000_000.0).replace(".0M", "M")
    tokens >= 1_000 -> "%.1fk".format(tokens / 1_000.0).replace(".0k", "k")
    else -> tokens.toString()
}

@Composable
fun TextPromptDialog(title: String, initial: String, confirm: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = {
                onConfirm(text.trim())
                onDismiss()
            }) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
