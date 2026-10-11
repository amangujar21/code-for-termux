package dev.amang.claudetermux.ui.sessions

import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.amang.claudetermux.bridge.ConnectionState
import dev.amang.claudetermux.bridge.FolderListing
import dev.amang.claudetermux.bridge.SessionRow
import dev.amang.claudetermux.ui.chat.TextPromptDialog
import dev.amang.claudetermux.ui.theme.extraColors
import kotlinx.coroutines.launch

fun folderName(path: String): String =
    path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\').ifEmpty { path.ifEmpty { "Unknown folder" } }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SessionsScreen(
    viewModel: SessionsViewModel,
    onOpen: (SessionRow) -> Unit,
    onNew: (cwd: String) -> Unit,
    onSettings: () -> Unit,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<SessionRow?>(null) }
    var deleting by remember { mutableStateOf<SessionRow?>(null) }
    val extra = extraColors

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    Box(Modifier.fillMaxSize().statusBarsPadding()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Code", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                IconButton(onClick = viewModel::refresh) { Icon(Icons.Outlined.Refresh, "Refresh") }
                IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, "Connection and setup") }
            }
            Row(
                Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(extra.composer)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Search, null, tint = extra.muted, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                val style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp)
                BasicTextField(
                    query, { query = it }, singleLine = true, textStyle = style,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        if (query.isEmpty()) Text("Search sessions", style = style.copy(color = extra.muted))
                        inner()
                    },
                )
            }

            val groups = remember(ui.sessions, query) { ui.grouped(query) }
            when {
                ui.connection !is ConnectionState.Connected && ui.sessions.isEmpty() ->
                    Disconnected(ui.connection, viewModel::retryConnection, onSettings)
                ui.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                }
                groups.isEmpty() -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        ui.error ?: if (query.isBlank()) "No sessions yet. Start one with the button below." else "No sessions match \"$query\".",
                        color = if (ui.error != null) MaterialTheme.colorScheme.error else extra.muted,
                        textAlign = TextAlign.Center,
                    )
                }
                else -> LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                    groups.forEach { (cwd, rows) ->
                        item(key = "h:$cwd") {
                            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 6.dp)) {
                                Text(folderName(cwd), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                if (cwd.isNotEmpty()) Text(cwd, color = extra.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        items(rows, key = { it.sessionId }) { row ->
                            var menu by remember { mutableStateOf(false) }
                            Box {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .combinedClickable(onClick = { onOpen(row) }, onLongClick = { menu = true })
                                        .padding(horizontal = 20.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(row.title, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            listOfNotNull(
                                                DateUtils.getRelativeTimeSpanString(row.lastModified, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
                                                row.gitBranch?.takeIf { it.isNotBlank() },
                                            ).joinToString(" · "),
                                            color = extra.muted,
                                            fontSize = 13.sp,
                                        )
                                    }
                                    if (row.state == "running" || row.state == "waiting_permission") {
                                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = extra.muted)
                                    }
                                }
                                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(text = { Text("Rename") }, onClick = {
                                        menu = false
                                        renaming = row
                                    })
                                    DropdownMenuItem(text = { Text("Delete") }, onClick = {
                                        menu = false
                                        deleting = row
                                    })
                                }
                            }
                        }
                    }
                }
            }
        }

        if (ui.connection is ConnectionState.Connected) {
            ExtendedFloatingActionButton(
                onClick = { picking = true },
                icon = { Icon(Icons.Outlined.Add, null) },
                text = { Text("New session") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp),
            )
        }
    }

    if (picking) {
        FolderPicker(
            start = ui.lastCwd,
            recent = ui.recentFolders,
            list = viewModel::listFolder,
            create = viewModel::createFolder,
            onPick = {
                picking = false
                onNew(it)
            },
            onDismiss = { picking = false },
        )
    }
    renaming?.let { row ->
        TextPromptDialog("Rename session", row.title, "Rename", { viewModel.rename(row, it) }) { renaming = null }
    }
    deleting?.let { row ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete this session?") },
            text = { Text("\"${row.title}\" and its transcript will be removed from Termux. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(row)
                    deleting = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Disconnected(state: ConnectionState, onRetry: () -> Unit, onSettings: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
        when (state) {
            is ConnectionState.Unauthorized -> {
                Text("Termux rejected this app's pairing token.", textAlign = TextAlign.Center)
                Text("Run the setup again to pair.", color = extraColors.muted, fontSize = 14.sp, textAlign = TextAlign.Center)
            }
            is ConnectionState.Unreachable -> {
                Text("Can't reach Claude Code in Termux.", textAlign = TextAlign.Center)
                Text(state.reason, color = extraColors.muted, fontSize = 13.sp, textAlign = TextAlign.Center)
            }
            else -> {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                Spacer(Modifier.height(12.dp))
                Text("Connecting to Termux…", color = extraColors.muted)
            }
        }
        Spacer(Modifier.height(16.dp))
        Row {
            TextButton(onClick = onRetry) { Text("Retry") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = onSettings) { Text("Setup") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderPicker(
    start: String?,
    recent: List<String>,
    list: suspend (String?) -> Result<FolderListing>,
    create: suspend (String) -> Result<Unit>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var path by remember { mutableStateOf(start) }
    var listing by remember { mutableStateOf<FolderListing?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var naming by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val extra = extraColors

    LaunchedEffect(path, reload) {
        list(path).onSuccess {
            listing = it
            error = null
        }.onFailure {
            error = it.message
            // A remembered folder may have been deleted; fall back to home.
            if (listing == null && path != null) path = null
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.navigationBarsPadding()) {
            Text("Choose a project folder", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 20.dp))
            val current = listing
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(enabled = current?.parent != null, onClick = { path = current?.parent }) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Parent folder")
                }
                Text(current?.path ?: "Loading…", fontSize = 13.sp, color = extra.muted, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                IconButton(enabled = current != null, onClick = { naming = true }) {
                    Icon(Icons.Outlined.CreateNewFolder, "New folder")
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp)) }
            LazyColumn(Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 380.dp)) {
                if (current != null && current.path == current.home && recent.isNotEmpty()) {
                    item { Text("Recent", color = extra.muted, fontSize = 12.sp, modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 2.dp)) }
                    items(recent, key = { "r:$it" }) { folder -> FolderRow(folderName(folder), folder) { onPick(folder) } }
                    item { Text("Folders", color = extra.muted, fontSize = 12.sp, modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 2.dp)) }
                }
                items(current?.dirs.orEmpty().filter { !it.startsWith(".") }, key = { "d:$it" }) { dir ->
                    FolderRow(dir, null) { path = "${current!!.path.trimEnd('/', '\\')}/$dir" }
                }
            }
            Button(
                enabled = current != null,
                onClick = { current?.let { onPick(it.path) } },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            ) { Text("Start here" + (current?.let { " · ${folderName(it.path)}" } ?: "")) }
        }
    }

    if (naming) {
        TextPromptDialog("New folder", "", "Create", { name ->
            val parent = listing?.path ?: return@TextPromptDialog
            scope.launch {
                val target = "${parent.trimEnd('/', '\\')}/$name"
                create(target).onSuccess { path = target }.onFailure { error = it.message }
                reload++
            }
        }) { naming = false }
    }
}

@Composable
private fun FolderRow(name: String, detail: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Folder, null, tint = extraColors.muted, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Column {
            Text(name, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail != null) Text(detail, color = extraColors.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
