package dev.sn.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sn.app.SnApplication
import dev.sn.app.data.SettingsStore
import dev.sn.app.service.SnNotificationListener
import dev.sn.app.service.TriageScheduler
import dev.sn.app.tools.Permissions
import dev.sn.app.tools.hasAllFilesAccess
import dev.sn.app.tools.hasPermission
import dev.sn.core.OllamaClient
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: ChatViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val container = SnApplication.from(context)
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var host by remember(settings.ollamaHost) { mutableStateOf(settings.ollamaHost) }
    var model by remember(settings.model) { mutableStateOf(settings.model) }
    var connectionResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Section("Model") {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("Ollama host") },
                    placeholder = { Text("laptop.tail1234.ts.net") },
                    supportingText = {
                        Text("Your laptop's Tailscale name. Port 11434 is added automatically.")
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("Model") },
                    supportingText = { Text("Must support tool calling — qwen3, llama3.1, mistral-nemo.") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            scope.launch {
                                container.settings.setHost(host)
                                container.settings.setModel(model)
                                host = SettingsStore.normalizeHost(host)
                                connectionResult = "Saved."
                            }
                        },
                    ) { Text("Save") }

                    OutlinedButton(
                        enabled = !testing && host.isNotBlank(),
                        onClick = {
                            scope.launch {
                                testing = true
                                connectionResult = testConnection(host, model)
                                testing = false
                            }
                        },
                    ) { Text(if (testing) "Checking…" else "Test connection") }
                }

                connectionResult?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }

            HorizontalDivider()

            Section("Behaviour") {
                LabelledSlider(
                    label = "Creativity",
                    value = settings.temperature.toFloat(),
                    range = 0f..100f,
                    display = "${settings.temperature}%",
                ) { scope.launch { container.settings.setTemperature(it.toInt()) } }

                LabelledSlider(
                    label = "Tool steps per question",
                    value = settings.maxSteps.toFloat(),
                    range = 1f..20f,
                    display = settings.maxSteps.toString(),
                ) { scope.launch { container.settings.setMaxSteps(it.toInt()) } }

                LabelledSlider(
                    label = "Context window",
                    value = (settings.contextTokens / 1024).toFloat(),
                    range = 2f..64f,
                    display = "${settings.contextTokens / 1024}k tokens",
                ) { scope.launch { container.settings.setContextTokens(it.toInt() * 1024) } }
            }

            HorizontalDivider()

            Section("Proactive alerts") {
                Text(
                    "Periodically reads notifications that arrived while you were away and pings " +
                        "you only if something looks like it needs attention. It never replies to " +
                        "anyone, and no tools run during this pass.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SwitchRow("Watch notifications", settings.proactiveEnabled) { enabled ->
                    scope.launch {
                        container.settings.setProactive(enabled)
                        TriageScheduler.apply(context, container.currentSettings())
                    }
                }
                if (settings.proactiveEnabled) {
                    LabelledSlider(
                        label = "Check every",
                        value = settings.proactiveIntervalMinutes.toFloat(),
                        range = 15f..240f,
                        display = "${settings.proactiveIntervalMinutes} min",
                    ) {
                        scope.launch {
                            container.settings.setProactiveInterval(it.toInt())
                            TriageScheduler.apply(context, container.currentSettings())
                        }
                    }
                    Text(
                        "Quiet from ${settings.quietHoursStart}:00 to ${settings.quietHoursEnd}:00",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            HorizontalDivider()

            PermissionsSection()

            HorizontalDivider()

            Section("Tools") {
                Text(
                    "Tools that ask before running. Turning one off means it fires without " +
                        "asking — think about that before doing it for sending messages.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                container.tools.all.forEach { tool ->
                    val confirms = tool.name in settings.confirmTools || tool.consequential
                    val disabled = tool.name in settings.disabledTools
                    ToolRow(
                        name = tool.name,
                        category = tool.category,
                        confirms = confirms,
                        alwaysConfirms = tool.consequential,
                        enabled = !disabled,
                        onConfirmChange = { scope.launch { container.settings.toggleConfirm(tool.name, it) } },
                        onEnabledChange = { scope.launch { container.settings.toggleDisabled(tool.name, !it) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun LabelledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: String,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(display, style = MaterialTheme.typography.bodyMedium)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

@Composable
private fun ToolRow(
    name: String,
    category: String,
    confirms: Boolean,
    alwaysConfirms: Boolean,
    enabled: Boolean,
    onConfirmChange: (Boolean) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        category,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = enabled, onCheckedChange = onEnabledChange)
            }
            if (enabled) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (alwaysConfirms) "Always asks first" else "Ask before running",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Switch(
                        checked = confirms,
                        enabled = !alwaysConfirms,
                        onCheckedChange = onConfirmChange,
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionsSection() {
    val context = LocalContext.current
    var refresh by remember { mutableStateOf(0) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { refresh++ }

    // Re-read grants whenever this screen is recomposed after returning from
    // Android's own settings pages, which give no result callback.
    LaunchedEffect(refresh) { }

    val missing = Permissions.ALL.filterNot { context.hasPermission(it) }
    val notificationsGranted = SnNotificationListener.isEnabled(context)

    Section("Permissions") {
        StatusLine(
            label = "App permissions",
            ok = missing.isEmpty(),
            detail = if (missing.isEmpty()) "All granted" else "${missing.size} missing",
        )
        if (missing.isNotEmpty()) {
            Button(onClick = { launcher.launch(missing.toTypedArray()) }) {
                Text("Grant ${missing.size} permissions")
            }
        }

        StatusLine(
            label = "Notification access",
            ok = notificationsGranted,
            detail = if (notificationsGranted) {
                "Reading the status bar"
            } else {
                "Needed for 'what did I miss'"
            },
        )
        if (!notificationsGranted) {
            OutlinedButton(onClick = { context.startActivity(SnNotificationListener.settingsIntent()) }) {
                Text("Open notification access settings")
            }
        }

        StatusLine(
            label = "All files access",
            ok = hasAllFilesAccess(),
            detail = if (hasAllFilesAccess()) {
                "Can read documents"
            } else {
                "Optional — without it only media is searchable"
            },
        )
        if (!hasAllFilesAccess() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            OutlinedButton(onClick = { context.openAllFilesAccess() }) {
                Text("Open all files access")
            }
        }
    }
}

@Composable
private fun StatusLine(label: String, ok: Boolean, detail: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(
            (if (ok) "✓ " else "✗ ") + detail,
            style = MaterialTheme.typography.bodySmall,
            color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
    }
}

private fun Context.openAllFilesAccess() {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
    }
    runCatching { startActivity(intent) }
}

/** Checks the host and model, and says which part is wrong when it fails. */
private suspend fun testConnection(host: String, model: String): String {
    val client = OllamaClient(
        dev.sn.core.OllamaConfig(host = SettingsStore.normalizeHost(host), model = model),
    )
    return try {
        val models = client.listModels()
        val present = models.any { it == model || it == "$model:latest" }
        if (!present) {
            "Reached Ollama, but '$model' is not installed there. " +
                "Run: ollama pull $model\nAvailable: ${models.take(6).joinToString(", ")}"
        } else {
            val capabilities = runCatching { client.capabilities(model) }.getOrDefault(emptyList())
            when {
                capabilities.isEmpty() -> "Connected. Model found. (Older Ollama — tool support unknown.)"
                "tools" in capabilities -> "Connected. '$model' is installed and supports tools."
                else -> "Connected, but '$model' cannot call tools. Try qwen3:8b or llama3.1:8b."
            }
        }
    } catch (e: Exception) {
        e.message ?: "Could not reach Ollama."
    }
}
