package dev.soundbeam.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.soundbeam.Engine
import dev.soundbeam.R
import dev.soundbeam.sync.AutoSync
import dev.soundbeam.sync.SyncState
import kotlinx.coroutines.launch

/** "Sync speakers automatically" – shown while playing on two or more kinds of speakers. */
@Composable
fun SyncCard() {
    val ctx = LocalContext.current
    val sync by AutoSync.state.collectAsState()
    val states by Engine.outputStates.collectAsState()
    val groups = remember(states) { AutoSync.groups() }
    var confirm by remember { mutableStateOf(false) }

    if (groups.size < 2 && sync is SyncState.Idle) return

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_sync), null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text("Sync speakers automatically", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(6.dp))
            when (val s = sync) {
                is SyncState.Idle -> {
                    Text(
                        "Different kinds of speakers have different delays, so they can sound out of step. " +
                            "SoundBeam can listen with your phone's microphone and line them all up.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { confirm = true }) { Text("Sync now") }
                }
                is SyncState.Running -> {
                    Text(s.step, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Keep the room quiet for a few seconds.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is SyncState.Done -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Your speakers are in sync", style = MaterialTheme.typography.bodyLarge)
                    }
                    Spacer(Modifier.height(6.dp))
                    s.lines.forEach { Text("•  $it", style = MaterialTheme.typography.bodySmall) }
                    s.warnings.forEach {
                        Text("•  $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Wireless delays can change when a speaker reconnects – sync again if it ever sounds off.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { confirm = true }) { Text("Sync again") }
                        TextButton(onClick = { AutoSync.reset() }) { Text("Hide") }
                    }
                }
                is SyncState.Failed -> {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(s.message, style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { confirm = true }) { Text("Try again") }
                        TextButton(onClick = { AutoSync.reset() }) { Text("Hide") }
                    }
                }
            }
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Sync your speakers") },
            text = {
                Text(
                    "For about ${groups.size * 10} seconds your music pauses and each speaker plays three short " +
                        "chirps in turn while the phone listens.\n\n" +
                        "• Put the phone roughly where you sit.\n" +
                        "• Keep the room quiet.\n" +
                        "• Your volume settings come back afterwards.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    Engine.scope.launch { AutoSync.run(ctx.applicationContext) }
                }) { Text("Start") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}
