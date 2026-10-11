package dev.amang.claudetermux.ui.chat

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.amang.claudetermux.bridge.SlashCommand
import dev.amang.claudetermux.chat.PendingAttachment
import dev.amang.claudetermux.ui.theme.extraColors

/** Commands matching what has been typed after the slash, best matches first. */
fun matchCommands(commands: List<SlashCommand>, input: String): List<SlashCommand> {
    if (!input.startsWith("/") || input.any { it.isWhitespace() }) return emptyList()
    val query = input.drop(1).lowercase()
    val (prefix, rest) = commands
        .filter { c -> c.name.lowercase().contains(query) || c.aliases.any { it.lowercase().startsWith(query) } }
        .partition { it.name.lowercase().startsWith(query) }
    return prefix + rest
}

@Composable
fun SlashPopup(matches: List<SlashCommand>, onPick: (SlashCommand) -> Unit, modifier: Modifier = Modifier) {
    val extra = extraColors
    LazyColumn(
        modifier
            .fillMaxWidth()
            .heightIn(max = 280.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(extra.composer)
            .border(1.dp, extra.composerBorder, RoundedCornerShape(16.dp)),
    ) {
        items(matches, key = { it.name }) { command ->
            Column(Modifier.fillMaxWidth().clickable { onPick(command) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("/${command.name}", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                    if (command.argumentHint.isNotBlank()) {
                        Spacer(Modifier.width(8.dp))
                        Text(command.argumentHint, color = extra.muted, fontFamily = FontFamily.Monospace, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (command.description.isNotBlank()) {
                    Text(command.description, color = extra.muted, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
fun Composer(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    attachments: List<PendingAttachment>,
    onRemoveAttachment: (PendingAttachment) -> Unit,
    modelLabel: String,
    effortLabel: String?,
    running: Boolean,
    enabled: Boolean,
    onAttach: () -> Unit,
    onModel: () -> Unit,
    onMic: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val extra = extraColors
    val canSend = enabled && (value.text.isNotBlank() || attachments.isNotEmpty())
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(extra.composer)
            .border(1.dp, extra.composerBorder, RoundedCornerShape(28.dp))
            .padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 10.dp),
    ) {
        if (attachments.isNotEmpty()) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(start = 6.dp, top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                attachments.forEach { attachment ->
                    Row(
                        Modifier.clip(RoundedCornerShape(10.dp)).background(extra.chip).padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(attachment.name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(120.dp))
                        Icon(
                            Icons.Outlined.Close,
                            "Remove ${attachment.name}",
                            tint = extra.muted,
                            modifier = Modifier.size(28.dp).clip(CircleShape).clickable { onRemoveAttachment(attachment) }.padding(6.dp),
                        )
                    }
                }
            }
        }
        val textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = textStyle,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            maxLines = 8,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 12.dp),
            decorationBox = { inner ->
                Box {
                    if (value.text.isEmpty()) Text("Type / for commands", style = textStyle.copy(color = extra.muted))
                    inner()
                }
            },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            RoundButton(onClick = onAttach, description = "Attach", background = extra.chip) {
                Icon(Icons.Outlined.Add, null, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(extra.chip)
                    .clickable(onClick = onModel)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(modelLabel, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (effortLabel != null) {
                    Spacer(Modifier.width(5.dp))
                    Text(effortLabel, fontSize = 14.sp, color = extra.muted, maxLines = 1)
                }
            }
            }
            Spacer(Modifier.width(8.dp))
            RoundButton(onClick = onMic, description = "Dictate", background = extra.chip) {
                Icon(Icons.Outlined.Mic, null, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(8.dp))
            if (running && !canSend) {
                RoundButton(onClick = onStop, description = "Stop", background = MaterialTheme.colorScheme.onSurface) {
                    Icon(Icons.Outlined.Stop, null, tint = MaterialTheme.colorScheme.surface, modifier = Modifier.size(20.dp))
                }
            } else {
                val primary = MaterialTheme.colorScheme.primary
                RoundButton(
                    onClick = onSend,
                    description = "Send",
                    background = if (canSend) primary else primary.copy(alpha = 0.45f),
                    enabled = canSend,
                ) {
                    Icon(Icons.Outlined.ArrowUpward, null, tint = MaterialTheme.colorScheme.onPrimary.copy(alpha = if (canSend) 1f else 0.6f), modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
private fun RoundButton(
    onClick: () -> Unit,
    description: String,
    background: androidx.compose.ui.graphics.Color,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(background)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { content() }
}

fun TextFieldValue.withText(text: String) = TextFieldValue(text, TextRange(text.length))
