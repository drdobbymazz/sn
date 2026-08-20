package dev.sn.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onOpenSettings: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(state.turns.size, state.turns.lastOrNull()) {
        if (state.turns.isNotEmpty()) listState.animateScrollToItem(state.turns.lastIndex)
    }

    state.pending?.let { pending ->
        ConfirmationDialog(pending)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("sn") },
                actions = {
                    IconButton(onClick = viewModel::newConversation) {
                        Icon(Icons.Default.Add, contentDescription = "New conversation")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            if (state.turns.isEmpty()) {
                EmptyState(Modifier.weight(1f))
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.turns, key = { it.id }) { turn -> TurnRow(turn) }
                }
            }

            Composer(
                draft = draft,
                busy = state.busy,
                onDraftChange = { draft = it },
                onSend = {
                    viewModel.send(draft)
                    draft = ""
                },
                onStop = viewModel::cancel,
            )
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("sn", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Ask about your messages, calendar, notifications or files.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, start = 32.dp, end = 32.dp),
            )
        }
    }
}

@Composable
private fun TurnRow(turn: Turn) {
    when (turn) {
        is Turn.User -> Bubble(
            text = turn.text,
            alignment = Alignment.CenterEnd,
            container = MaterialTheme.colorScheme.primary,
            content = MaterialTheme.colorScheme.onPrimary,
        )

        is Turn.Assistant -> Bubble(
            text = turn.text.ifBlank { "…" },
            alignment = Alignment.CenterStart,
            container = MaterialTheme.colorScheme.surfaceVariant,
            content = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        is Turn.ToolRun -> ToolRow(turn)

        is Turn.Problem -> Bubble(
            text = turn.text,
            alignment = Alignment.CenterStart,
            container = MaterialTheme.colorScheme.errorContainer,
            content = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
private fun Bubble(
    text: String,
    alignment: Alignment,
    container: androidx.compose.ui.graphics.Color,
    content: androidx.compose.ui.graphics.Color,
) {
    Box(Modifier.fillMaxWidth(), contentAlignment = alignment) {
        Surface(
            color = container,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Text(
                text = text,
                color = content,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

/** Tool activity is shown inline, dimmed — visible but never the main event. */
@Composable
private fun ToolRow(turn: Turn.ToolRun) {
    val colors = MaterialTheme.colorScheme
    val tint = when {
        turn.denied -> colors.tertiary
        turn.failed -> colors.error
        else -> colors.onSurfaceVariant
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(start = 6.dp),
    ) {
        if (turn.running) {
            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(12.dp))
        } else {
            Box(
                Modifier
                    .size(6.dp)
                    .background(tint, RoundedCornerShape(3.dp)),
            )
        }
        Text(
            text = "${turn.tool}  ${turn.detail}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = tint,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Composer(
    draft: String,
    busy: Boolean,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Ask sn…") },
            shape = RoundedCornerShape(24.dp),
            maxLines = 5,
        )
        FilledIconButton(
            onClick = if (busy) onStop else onSend,
            enabled = busy || draft.isNotBlank(),
        ) {
            Icon(
                imageVector = if (busy) Icons.Default.Stop else Icons.Default.Send,
                contentDescription = if (busy) "Stop" else "Send",
            )
        }
    }
}

/**
 * The confirmation gate.
 *
 * Shows the arguments in full, unabbreviated: the entire point is that the user
 * sees the exact text about to be sent to a real person before it goes.
 */
@Composable
private fun ConfirmationDialog(pending: PendingConfirmation) {
    AlertDialog(
        onDismissRequest = pending::deny,
        title = { Text(pending.tool.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    pending.tool.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                pending.details.forEach { (label, value) ->
                    Column {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(value, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = pending::allow) { Text("Run it") } },
        dismissButton = { TextButton(onClick = pending::deny) { Text("No") } },
    )
}
