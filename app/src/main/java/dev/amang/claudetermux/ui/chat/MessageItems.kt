package dev.amang.claudetermux.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.IndeterminateCheckBox
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.amang.claudetermux.bridge.arr
import dev.amang.claudetermux.bridge.str
import dev.amang.claudetermux.chat.ChatItem
import dev.amang.claudetermux.ui.markdown.MarkdownView
import dev.amang.claudetermux.ui.theme.extraColors
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val MAX_RESULT_CHARS = 6000

@Composable
fun UserMessage(item: ChatItem.User) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Column(
            Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            item.attachments.forEach {
                Text("📎 $it", color = extraColors.muted, fontSize = 13.sp)
            }
            if (item.text.isNotEmpty()) {
                SelectionContainer {
                    Text(item.text, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

@Composable
fun AssistantText(item: ChatItem.Text, showActions: Boolean, speaking: Boolean, onSpeak: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    Column {
        SelectionContainer { MarkdownView(item.markdown) }
        if (showActions) {
            Row(Modifier.padding(top = 6.dp)) {
                IconButton(onClick = { clipboard.setText(AnnotatedString(item.markdown)) }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Outlined.ContentCopy, "Copy", tint = extraColors.muted, modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = onSpeak, modifier = Modifier.size(40.dp)) {
                    Icon(
                        if (speaking) Icons.Outlined.StopCircle else Icons.AutoMirrored.Outlined.VolumeUp,
                        if (speaking) "Stop reading" else "Read aloud",
                        tint = if (speaking) MaterialTheme.colorScheme.primary else extraColors.muted,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun ThinkingBlock(item: ChatItem.Thinking) {
    var expanded by rememberSaveable(item.key) { mutableStateOf(false) }
    Column {
        Row(
            Modifier.clip(RoundedCornerShape(8.dp)).clickable { expanded = !expanded }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (item.streaming) "Thinking…" else "Thought",
                color = extraColors.muted,
                fontSize = 15.sp,
                fontStyle = FontStyle.Italic,
            )
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                null,
                tint = extraColors.muted,
                modifier = Modifier.size(18.dp),
            )
        }
        AnimatedVisibility(expanded) {
            SelectionContainer {
                Text(item.text, color = extraColors.muted, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(start = 2.dp, top = 4.dp))
            }
        }
    }
}

/** One-line description of what a tool call is doing, e.g. the command or the file. */
fun toolSummary(name: String, input: JsonObject): String {
    val path = input.str("file_path") ?: input.str("notebook_path") ?: input.str("path")
    return when (name) {
        "Bash", "PowerShell" -> input.str("description") ?: input.str("command").orEmpty()
        "Read", "Edit", "Write", "NotebookEdit", "MultiEdit" -> path?.substringAfterLast('/')?.substringAfterLast('\\').orEmpty()
        "Glob", "Grep" -> input.str("pattern").orEmpty()
        "WebFetch" -> input.str("url").orEmpty()
        "WebSearch" -> input.str("query").orEmpty()
        "Task", "Agent" -> input.str("description").orEmpty()
        "TodoWrite" -> "${input.arr("todos")?.size ?: 0} items"
        "Skill" -> input.str("skill").orEmpty()
        else -> input.entries.firstOrNull { it.value is JsonPrimitive }?.let { (it.value as JsonPrimitive).content }.orEmpty()
    }.lineSequence().firstOrNull().orEmpty()
}

private fun toolVerb(name: String) = when (name) {
    "Bash", "PowerShell" -> "Run"
    "Task", "Agent" -> "Agent"
    "TodoWrite" -> "Update todos"
    "WebFetch" -> "Fetch"
    "WebSearch" -> "Search web"
    else -> name.removePrefix("mcp__").replace("__", " · ")
}

@Composable
fun ToolCard(item: ChatItem.Tool) {
    val extra = extraColors
    var expanded by rememberSaveable(item.key) { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, extra.divider, RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                !item.done -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = extra.muted)
                item.isError -> Icon(Icons.Outlined.ErrorOutline, "Failed", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                else -> Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(extra.success))
            }
            Spacer(Modifier.width(10.dp))
            Text(toolVerb(item.name), fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Spacer(Modifier.width(8.dp))
            Text(
                toolSummary(item.name, item.input),
                color = extra.muted,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = extra.muted, modifier = Modifier.size(18.dp))
        }
        if (item.name == "TodoWrite") TodoList(item.input)
        AnimatedVisibility(expanded) {
            Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ToolInput(item.name, item.input)
                item.result?.takeIf { it.isNotBlank() }?.let { result ->
                    MonoBox(
                        result.take(MAX_RESULT_CHARS) + if (result.length > MAX_RESULT_CHARS) "\n… (${result.length - MAX_RESULT_CHARS} more characters)" else "",
                        color = if (item.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
fun ToolInput(name: String, input: JsonObject) {
    val extra = extraColors
    when (name) {
        "Bash", "PowerShell" -> MonoBox(input.str("command").orEmpty())
        "Edit" -> {
            PathLine(input.str("file_path"))
            input.str("old_string")?.takeIf { it.isNotEmpty() }?.let { MonoBox(it, background = extra.diffRemove) }
            MonoBox(input.str("new_string").orEmpty(), background = extra.diffAdd)
        }
        "Write" -> {
            PathLine(input.str("file_path"))
            MonoBox(input.str("content").orEmpty().take(MAX_RESULT_CHARS), background = extra.diffAdd)
        }
        "TodoWrite" -> Unit
        else -> {
            val text = input.entries.joinToString("\n") { (k, v) -> "$k: ${if (v is JsonPrimitive) v.content else v.toString()}" }
            if (text.isNotBlank()) MonoBox(text.take(MAX_RESULT_CHARS))
        }
    }
}

@Composable
private fun PathLine(path: String?) {
    if (path != null) Text(path, color = extraColors.muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
}

@Composable
private fun MonoBox(text: String, color: Color = MaterialTheme.colorScheme.onSurface, background: Color = extraColors.codeBackground) {
    SelectionContainer {
        Text(
            text,
            color = color,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.5.sp,
            lineHeight = 18.sp,
            softWrap = false,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(background)
                .horizontalScroll(rememberScrollState())
                .padding(10.dp),
        )
    }
}

@Composable
private fun TodoList(input: JsonObject) {
    val todos = input.arr("todos")?.filterIsInstance<JsonObject>().orEmpty()
    Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        todos.forEach { todo ->
            val status = todo.str("status")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when (status) {
                        "completed" -> Icons.Outlined.CheckBox
                        "in_progress" -> Icons.Outlined.IndeterminateCheckBox
                        else -> Icons.Outlined.CheckBoxOutlineBlank
                    },
                    status,
                    tint = if (status == "in_progress") MaterialTheme.colorScheme.primary else extraColors.muted,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    (if (status == "in_progress") todo.str("activeForm") else null) ?: todo.str("content").orEmpty(),
                    fontSize = 14.sp,
                    color = if (status == "completed") extraColors.muted else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
fun PermissionCard(item: ChatItem.Permission, onDecision: (String) -> Unit) {
    val extra = extraColors
    if (item.outcome != null) {
        Text(
            "${if (item.outcome == "allow") "Allowed" else "Denied"}: ${toolVerb(item.toolName)} ${toolSummary(item.toolName, item.input)}",
            color = extra.muted,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        return
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Allow ${toolVerb(item.toolName)}?", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        item.reason?.takeIf { it.isNotBlank() }?.let { Text(it, color = extra.muted, fontSize = 13.sp) }
        ToolInput(item.toolName, item.input)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onDecision("deny") }) { Text("Deny") }
            if (item.canAlwaysAllow) {
                OutlinedButton(onClick = { onDecision("allow_always") }) { Text("Always allow") }
            }
            Button(onClick = { onDecision("allow") }) { Text("Allow") }
        }
    }
}

@Composable
fun NoticeLine(item: ChatItem.Notice) {
    Text(
        item.text,
        color = if (item.isError) MaterialTheme.colorScheme.error else extraColors.muted,
        fontSize = 13.sp,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    )
}
