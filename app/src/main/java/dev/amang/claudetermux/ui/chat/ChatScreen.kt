package dev.amang.claudetermux.ui.chat

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.amang.claudetermux.chat.ChatEffect
import dev.amang.claudetermux.chat.ChatItem
import dev.amang.claudetermux.chat.ChatUi
import dev.amang.claudetermux.chat.ChatViewModel
import dev.amang.claudetermux.chat.PendingAttachment
import dev.amang.claudetermux.chat.RunState
import dev.amang.claudetermux.ui.theme.extraColors
import dev.amang.claudetermux.voice.Speaker
import java.io.File

private enum class Sheet { Model, Mode, Context, Mcp, Attach, Rename }

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onBack: () -> Unit,
    onNewSession: (cwd: String) -> Unit,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var input by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    val attachments = remember { mutableStateListOf<PendingAttachment>() }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    val speaker = remember { Speaker(context) }
    DisposableEffect(Unit) { onDispose { speaker.shutdown() } }

    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                ChatEffect.ShowModelSheet -> sheet = Sheet.Model
                ChatEffect.ShowModeSheet -> sheet = Sheet.Mode
                ChatEffect.ShowContextSheet -> sheet = Sheet.Context
                ChatEffect.ShowMcpSheet -> sheet = Sheet.Mcp
                ChatEffect.ShowRename -> sheet = Sheet.Rename
                ChatEffect.GoToSessions -> onBack()
                is ChatEffect.NewSession -> onNewSession(effect.cwd)
                is ChatEffect.Toast -> Toast.makeText(context, effect.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(6)) { uris ->
        attachments += uris.map(viewModel::describe)
    }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        attachments += uris.map(viewModel::describe)
    }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val uri = cameraUri
        if (saved && uri != null) attachments += PendingAttachment(uri, "photo.jpg", "image/jpeg")
    }
    val dictate = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (result.resultCode == Activity.RESULT_OK && !spoken.isNullOrBlank()) {
            input = input.withText(listOf(input.text.trimEnd(), spoken).filter { it.isNotEmpty() }.joinToString(" "))
        }
    }

    fun submit() {
        val text = input.text
        val files = attachments.toList()
        input = TextFieldValue()
        attachments.clear()
        speaker.stop()
        viewModel.send(text, files)
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        TopBar(ui, onBack, onMenu = { sheet = it }, onStop = viewModel::stop)

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                ui.opening -> CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp), strokeWidth = 2.dp)
                ui.error != null && ui.chat.items.isEmpty() -> ErrorState(ui.error!!, viewModel::retry, Modifier.align(Alignment.Center))
                ui.chat.items.isEmpty() && ui.chat.streaming.isEmpty() -> EmptyState(ui, Modifier.align(Alignment.Center))
                else -> MessageList(ui, speaker, viewModel::answerPermission, viewModel::signIn)
            }
        }

        Column(Modifier.navigationBarsPadding().imePadding().padding(horizontal = 12.dp).padding(bottom = 10.dp)) {
            if (!ui.connected) {
                Text(
                    "Reconnecting to Termux…",
                    color = extraColors.muted,
                    fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    textAlign = TextAlign.Center,
                )
            }
            val matches = remember(input.text, ui.commands) { matchCommands(ui.commands, input.text) }
            if (matches.isNotEmpty()) {
                SlashPopup(matches, onPick = { command ->
                    input = input.withText("/${command.name} ")
                    if (command.argumentHint.isBlank()) submit()
                }, modifier = Modifier.padding(bottom = 8.dp))
            }
            Composer(
                value = input,
                onValueChange = { input = it },
                attachments = attachments,
                onRemoveAttachment = { attachments.remove(it) },
                modelLabel = modelLabel(ui.models, ui.chat.model),
                effortLabel = effortLabel(ui.chat.effort),
                running = ui.chat.runState != RunState.Idle,
                enabled = ui.connected && ui.sessionId != null && !ui.opening && !ui.sending,
                onAttach = { sheet = Sheet.Attach },
                onModel = { sheet = Sheet.Model },
                onMic = {
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    try {
                        dictate.launch(intent)
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(context, "No speech recognition service is installed", Toast.LENGTH_LONG).show()
                    }
                },
                onSend = ::submit,
                onStop = viewModel::stop,
            )
        }
    }

    when (sheet) {
        Sheet.Model -> ModelSheet(ui.models, ui.chat.model, ui.chat.effort, viewModel::setModel, viewModel::setEffort) { sheet = null }
        Sheet.Mode -> ModeSheet(ui.chat.mode, viewModel::setMode) { sheet = null }
        Sheet.Context -> ContextSheet(viewModel::contextUsage) { sheet = null }
        Sheet.Mcp -> McpSheet(viewModel::mcpStatus) { sheet = null }
        Sheet.Rename -> TextPromptDialog("Rename session", ui.title, "Rename", viewModel::rename) { sheet = null }
        Sheet.Attach -> AttachSheet(
            onPhotos = {
                sheet = null
                pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onCamera = {
                sheet = null
                val file = File(context.cacheDir, "camera").apply { mkdirs() }.resolve("photo-${System.currentTimeMillis()}.jpg")
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                cameraUri = uri
                try {
                    takePhoto.launch(uri)
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(context, "No camera app is available", Toast.LENGTH_LONG).show()
                }
            },
            onFiles = {
                sheet = null
                pickFiles.launch(arrayOf("*/*"))
            },
            onDismiss = { sheet = null },
        )
        null -> Unit
    }
}

@Composable
private fun TopBar(ui: ChatUi, onBack: () -> Unit, onMenu: (Sheet) -> Unit, onStop: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to sessions") }
        Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
            Text(ui.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${ui.project} · ${modeLabel(ui.chat.mode)}",
                color = extraColors.muted,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (ui.chat.runState != RunState.Idle) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp, color = extraColors.muted)
            Spacer(Modifier.width(4.dp))
        }
        Box {
            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Outlined.MoreVert, "More") }
            DropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }) {
                val entries = buildList<Pair<String, () -> Unit>> {
                    if (ui.chat.runState != RunState.Idle) add("Stop" to onStop)
                    add("Permission mode" to { onMenu(Sheet.Mode) })
                    add("Model" to { onMenu(Sheet.Model) })
                    add("Context usage" to { onMenu(Sheet.Context) })
                    add("MCP servers" to { onMenu(Sheet.Mcp) })
                    add("Rename" to { onMenu(Sheet.Rename) })
                }
                entries.forEach { (label, action) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = {
                        menuOpen = false
                        action()
                    })
                }
            }
        }
    }
}

@Composable
private fun MessageList(ui: ChatUi, speaker: Speaker, onPermission: (String, String) -> Unit, onSignIn: () -> Unit) {
    val items = ui.chat.visibleItems
    val listState = rememberLazyListState()
    var followTail by remember { mutableStateOf(true) }

    // Follow new output unless the reader has scrolled up to look at something.
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }.collect { (dragging, canForward) ->
            if (dragging) followTail = !canForward else if (!canForward) followTail = true
        }
    }
    val tail = items.lastOrNull()
    val tailSize = when (tail) {
        is ChatItem.Text -> tail.markdown.length
        is ChatItem.Thinking -> tail.text.length
        else -> 0
    }
    LaunchedEffect(items.size, tailSize, ui.chat.runState) {
        if (followTail && items.isNotEmpty()) listState.scrollToItem(items.size)
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        itemsIndexed(items, key = { _, item -> item.key }, contentType = { _, item -> item::class }) { index, item ->
            when (item) {
                is ChatItem.User -> UserMessage(item)
                is ChatItem.Text -> {
                    val next = items.getOrNull(index + 1)
                    val endsTurn = !item.streaming && (next is ChatItem.User || (next == null && ui.chat.runState == RunState.Idle))
                    AssistantText(item, endsTurn, speaker.speakingKey == item.key) { speaker.toggle(item.key, item.markdown) }
                }
                is ChatItem.Thinking -> ThinkingBlock(item)
                is ChatItem.Tool -> ToolCard(item)
                is ChatItem.Permission -> PermissionCard(item) { decision -> onPermission(item.requestId, decision) }
                is ChatItem.Notice -> NoticeLine(item)
                is ChatItem.SignIn -> SignInCard(onSignIn)
            }
        }
        item(key = "tail") { Spacer(Modifier.height(1.dp)) }
    }
}

@Composable
private fun EmptyState(ui: ChatUi, modifier: Modifier = Modifier) {
    Column(modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("What should we work on?", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(ui.cwd, color = extraColors.muted, fontSize = 13.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onRetry) { Text("Try again") }
    }
}
