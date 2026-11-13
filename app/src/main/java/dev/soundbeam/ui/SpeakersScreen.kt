package dev.soundbeam.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.soundbeam.Engine
import dev.soundbeam.R
import dev.soundbeam.core.OutputState
import dev.soundbeam.core.Protocol
import dev.soundbeam.core.SpeakerGroup
import dev.soundbeam.core.SpeakerKind
import dev.soundbeam.core.Speakers

@Composable
fun SpeakersScreen(actions: AppActions) {
    val ctx = LocalContext.current
    val devices by Engine.devices.collectAsState()
    val scanning by Engine.scanning.collectAsState()
    val via by Engine.via.collectAsState()
    val welcomeSeen by Engine.welcomeSeen.collectAsState()
    val running by Engine.running.collectAsState()
    val groups = remember(devices, via) { Speakers.group(devices, via) }
    var sheetFor by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { if (Engine.devices.value.isEmpty()) Engine.scan(ctx) }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("SoundBeam", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "Play your phone's sound on your speakers",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (scanning) {
                    CircularProgressIndicator(Modifier.size(24.dp).padding(2.dp), strokeWidth = 2.5.dp)
                    Spacer(Modifier.width(12.dp))
                } else {
                    IconButton(onClick = { Engine.scan(ctx) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Search for speakers again")
                    }
                }
            }
        }
        if (!welcomeSeen) item { WelcomeCard() }
        if (running) item { NowPlayingCard(actions) }
        if (groups.isEmpty()) {
            item { EmptyState(scanning) }
        } else {
            item {
                Text(
                    "Your speakers",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, start = 4.dp),
                )
            }
            items(groups, key = { it.key }) { g -> SpeakerCard(g, onSettings = { sheetFor = g.key }) }
            item {
                Text(
                    "Missing a speaker? Check it's switched on and on the same Wi-Fi as this phone, then tap ⟳ at the top.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }

    sheetFor?.let { key ->
        groups.firstOrNull { it.key == key }?.let { SpeakerSheet(it, onDismiss = { sheetFor = null }) }
    }
}

@Composable
private fun WelcomeCard() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("Welcome! Here's how it works", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            Step(1, "Tap the speakers you want to hear.")
            Step(2, "Press Play at the bottom.")
            Step(3, "Play music, podcasts or videos in any app – the sound comes out of your speakers.")
            Spacer(Modifier.height(8.dp))
            Text(
                "Works with AirPlay, Chromecast, Sonos, HEOS, smart TVs and Fire TV.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = { Engine.markWelcomeSeen() }, modifier = Modifier.align(Alignment.End)) { Text("Got it") }
        }
    }
}

@Composable
private fun Step(n: Int, text: String) {
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(26.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text("$n", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun NowPlayingCard(actions: AppActions) {
    val np by Engine.nowPlaying.collectAsState()
    val access by Engine.notificationAccess.collectAsState()
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            val art = np?.artworkJpeg
            val bmp = remember(art?.size, np?.title) { art?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
            Box(
                Modifier.size(64.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                if (bmp != null) {
                    Image(bmp, "Cover art", Modifier.size(64.dp), contentScale = ContentScale.Crop)
                } else {
                    Icon(painterResource(R.drawable.ic_speaker), null, tint = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                if (!np?.title.isNullOrBlank()) {
                    Text("Now playing", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(np?.title.orEmpty(), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(np?.artist, np?.album).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Text("You're live", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Start music or a video in any app and it will play on your speakers.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (!access) {
                    TextButton(onClick = actions.openNotificationAccess, contentPadding = PaddingValues(0.dp)) {
                        Text("Show song titles & cover art on speakers")
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(scanning: Boolean) {
    val ctx = LocalContext.current
    Card(shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(20.dp).fillMaxWidth()) {
            if (scanning) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                    Spacer(Modifier.width(16.dp))
                    Text("Looking for speakers on your Wi-Fi…", style = MaterialTheme.typography.titleMedium)
                }
            } else {
                Text("No speakers found yet", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                listOf(
                    "This phone and your speakers are on the same Wi-Fi.",
                    "The speaker is switched on and not in Bluetooth mode.",
                    "Fire TV or Android TV? Install the free SoundBeam Receiver app on it first (see More → Help).",
                ).forEach { Text("•  $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 3.dp)) }
                Spacer(Modifier.height(12.dp))
                FilledTonalButton(onClick = { Engine.scan(ctx) }) { Text("Search again") }
            }
        }
    }
}

@Composable
private fun SpeakerCard(g: SpeakerGroup, onSettings: () -> Unit) {
    val selected by Engine.selected.collectAsState()
    val states by Engine.outputStates.collectAsState()
    val running by Engine.running.collectAsState()
    val active = g.options.firstOrNull { it.id in selected }
    val isOn = active != null
    val state = active?.let { states[it.id] }

    Card(
        onClick = { Engine.toggleSpeaker(g) },
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isOn) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLowest,
            contentColor = if (isOn) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isOn) 0.dp else 1.dp),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = if (isOn) 6.dp else 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(48.dp).clip(CircleShape).background(
                        if (isOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(if (g.kind == SpeakerKind.TV) R.drawable.ic_tv else R.drawable.ic_speaker),
                        contentDescription = null,
                        tint = if (isOn) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(g.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    StatusLine(g, isOn, running, state)
                }
                IconButton(onClick = onSettings) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Settings for ${g.name}")
                }
                Switch(checked = isOn, onCheckedChange = { Engine.toggleSpeaker(g) })
                Spacer(Modifier.width(8.dp))
            }
            if (active != null) {
                VolumeSlider(active.id, Modifier.padding(start = 2.dp, end = 8.dp))
            }
        }
    }
}

@Composable
private fun StatusLine(g: SpeakerGroup, isOn: Boolean, running: Boolean, state: OutputState?) {
    val (text, isError) = when {
        !isOn -> Speakers.connectionTitle(g.chosen.protocol) to false
        !running -> "Will play when you press Play" to false
        state is OutputState.Failed -> "Couldn't connect – tap ⋮ for details" to true
        state is OutputState.WaitingForPin -> "Enter the code shown on the TV" to false
        state is OutputState.Playing -> "Playing" to false
        else -> "Connecting…" to false
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (isError) {
            Icon(Icons.Default.Warning, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun VolumeSlider(id: String, modifier: Modifier = Modifier) {
    var v by remember(id) { mutableFloatStateOf(Engine.volumeFor(id).toFloat()) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painterResource(R.drawable.ic_speaker), "Volume",
            Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Slider(
            value = v, onValueChange = { v = it },
            onValueChangeFinished = { Engine.setVolume(id, v.toInt()) },
            valueRange = 0f..100f, modifier = Modifier.weight(1f),
        )
        Text(
            if (v.toInt() == 0) "Muted" else "${v.toInt()}%",
            Modifier.width(52.dp).padding(start = 8.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpeakerSheet(g: SpeakerGroup, onDismiss: () -> Unit) {
    val selected by Engine.selected.collectAsState()
    val states by Engine.outputStates.collectAsState()
    val active = g.options.firstOrNull { it.id in selected }
    val current = active ?: g.chosen
    val failed = active?.let { states[it.id] } as? OutputState.Failed
    var delay by remember(current.id) { mutableFloatStateOf(Engine.delayFor(current.id).toFloat()) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 32.dp)) {
            Text(g.name, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))

            if (failed != null) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Couldn't connect", style = MaterialTheme.typography.titleSmall)
                        Text(failed.reason, style = MaterialTheme.typography.bodySmall)
                        if (g.options.size > 1) {
                            Text(
                                "Try another connection type below.",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            if (active != null) {
                SectionTitle("Volume")
                VolumeSlider(active.id)
                Spacer(Modifier.height(12.dp))
            }

            SectionTitle(if (g.options.size > 1) "Connect using" else "Connection")
            if (g.options.size > 1) {
                g.options.forEach { d ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .selectable(selected = d.id == current.id, role = Role.RadioButton) { Engine.chooseConnection(g, d) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        RadioButton(selected = d.id == current.id, onClick = null, modifier = Modifier.padding(top = 2.dp, end = 12.dp))
                        Column {
                            Text(Speakers.connectionTitle(d.protocol), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                Speakers.connectionHelp(d.protocol),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else {
                Text(Speakers.connectionTitle(current.protocol), style = MaterialTheme.typography.bodyLarge)
                Text(
                    Speakers.connectionHelp(current.protocol),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            SectionTitle("Line up with other speakers")
            Text(
                "Playing on several kinds of speakers at once? If this one is ahead of the others, " +
                    "hold it back a little until they sound together.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = delay,
                    onValueChange = { delay = (it / 50).toInt() * 50f },
                    onValueChangeFinished = { Engine.setDelay(current.id, delay.toInt()) },
                    valueRange = 0f..5000f,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (delay < 1f) "No delay" else "%.2f s".format(delay / 1000f),
                    Modifier.width(76.dp).padding(start = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                )
            }

            if (current.protocol == Protocol.AIRPLAY2 && Engine.airplayPairing(current.id) != null) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { Engine.forgetAirplayPairing(current.id) }) { Text("Forget pairing code") }
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Done") }
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}
