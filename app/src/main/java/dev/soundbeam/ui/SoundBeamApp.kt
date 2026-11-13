package dev.soundbeam.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.soundbeam.Engine
import dev.soundbeam.R
import dev.soundbeam.core.Speakers

/** Things the UI asks the hosting activity to do. */
class AppActions(
    val startStreaming: () -> Unit,
    val stopStreaming: () -> Unit,
    val openNotificationAccess: () -> Unit,
    val openUrl: (String) -> Unit,
    val startListening: () -> Unit = {},
    val stopListening: () -> Unit = {},
)

enum class Tab(val label: String, val icon: Int) {
    SPEAKERS("Speakers", R.drawable.ic_speaker),
    TOGETHER("Together", R.drawable.ic_headphones),
    SOUND("Sound", R.drawable.ic_tune),
    MORE("More", R.drawable.ic_more),
}

const val PROJECT_URL = "https://github.com/ganpatig/SoundBeam"
const val RELEASES_URL = "https://github.com/ganpatig/SoundBeam/releases/latest"

@Composable
fun SoundBeamApp(actions: AppActions, initialTab: Tab = Tab.SPEAKERS) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    val running by Engine.running.collectAsState()
    val captureExplained by Engine.captureExplained.collectAsState()
    var explainCapture by remember { mutableStateOf(false) }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        // Surface problems the user needs to know about; routine progress messages stay quiet.
        Engine.status.collect { msg ->
            val quiet = msg.isBlank() || listOf("Scanning", "Found", "No receivers", "Stopped", "Capturing")
                .any { msg.startsWith(it) }
            if (!quiet) snackbar.showSnackbar(msg)
        }
    }

    val play = {
        if (captureExplained) actions.startStreaming() else explainCapture = true
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Column {
                if (tab == Tab.SPEAKERS) PlayBar(running, onPlay = play, onStop = actions.stopStreaming)
                NavigationBar {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { Icon(painterResource(t.icon), contentDescription = null) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                Tab.SPEAKERS -> SpeakersScreen(actions)
                Tab.TOGETHER -> TogetherScreen(actions, onPlay = play)
                Tab.SOUND -> SoundScreen()
                Tab.MORE -> MoreScreen(actions)
            }
        }
    }

    if (explainCapture) {
        AlertDialog(
            onDismissRequest = { explainCapture = false },
            title = { Text("Before you start") },
            text = {
                Text(
                    "Next, Android will ask whether SoundBeam may record or cast your screen. " +
                        "That is simply how Android lets an app pick up the sound of other apps.\n\n" +
                        "SoundBeam only uses the sound, only while you are streaming, and saves nothing " +
                        "unless you turn on Record to a file.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    explainCapture = false
                    Engine.markCaptureExplained()
                    actions.startStreaming()
                }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { explainCapture = false }) { Text("Not now") } },
        )
    }
    PinDialog()
}

@Composable
private fun PlayBar(running: Boolean, onPlay: () -> Unit, onStop: () -> Unit) {
    val selected by Engine.selected.collectAsState()
    val devices by Engine.devices.collectAsState()
    val via by Engine.via.collectAsState()
    val http by Engine.httpEnabled.collectAsState()
    val save by Engine.saveEnabled.collectAsState()
    val names = remember(devices, via, selected) {
        Speakers.group(devices, via).filter { g -> g.options.any { it.id in selected } }.map { it.name }
    }
    val extras = http || save
    val summary = when (names.size) {
        0 -> if (extras) "Browser link / recording only" else "Tap a speaker above"
        1 -> names[0]
        2 -> "${names[0]} & ${names[1]}"
        else -> "${names[0]} + ${names.size - 1} more"
    }

    Surface(tonalElevation = 3.dp, shadowElevation = 6.dp) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Row(
                Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            running -> "Streaming to"
                            names.isEmpty() && !extras -> "Nothing chosen yet"
                            else -> "Ready to play on"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(summary, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(12.dp))
                if (running) {
                    Button(
                        onClick = onStop,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                        modifier = Modifier.height(52.dp),
                    ) {
                        Icon(painterResource(R.drawable.ic_stop), null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Stop", style = MaterialTheme.typography.titleMedium)
                    }
                } else {
                    Button(
                        onClick = onPlay,
                        enabled = names.isNotEmpty() || extras,
                        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                        modifier = Modifier.height(52.dp),
                    ) {
                        Icon(Icons.Default.PlayArrow, null, Modifier.size(24.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Play", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun PinDialog() {
    val device by Engine.pinRequest.collectAsState()
    val d = device ?: return
    var pin by remember(d.id) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { Engine.submitPin(null) },
        title = { Text("Pair with ${d.name}") },
        text = {
            Column {
                Text("Type the code shown on your TV. You only need to do this once.")
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter(Char::isDigit).take(8) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    label = { Text("Code") },
                )
            }
        },
        confirmButton = { TextButton(onClick = { Engine.submitPin(pin) }, enabled = pin.length >= 4) { Text("Pair") } },
        dismissButton = { TextButton(onClick = { Engine.submitPin(null) }) { Text("Cancel") } },
    )
}
