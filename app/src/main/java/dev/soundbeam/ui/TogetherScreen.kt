package dev.soundbeam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.soundbeam.Engine
import dev.soundbeam.R
import dev.soundbeam.core.OutputState
import dev.soundbeam.core.Protocol
import dev.soundbeam.live.DualAudio
import dev.soundbeam.live.LiveReceiverState
import kotlinx.coroutines.delay

@Composable
fun TogetherScreen(actions: AppActions, onPlay: () -> Unit) {
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Watch together", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Two people, one movie – each with their own headphones. Made for trips, flights and late nights.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TwoHeadphonesCard()
        FriendPhoneCard(actions, onPlay)
    }
}

@Composable
private fun Badge(text: String, good: Boolean) {
    Box(
        Modifier.clip(RoundedCornerShape(50))
            .background(if (good) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = if (good) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NumberedStep(n: Int, text: String, button: String? = null, onClick: () -> Unit = {}) {
    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(26.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) { Text("$n", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelLarge) }
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (button != null) {
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(onClick = onClick) { Text(button) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TwoHeadphonesCard() {
    val ctx = LocalContext.current
    val kind = remember { DualAudio.kind(ctx) }
    var headphones by remember { mutableStateOf(DualAudio.connectedHeadphones(ctx)) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2000)
            headphones = DualAudio.connectedHeadphones(ctx)
        }
    }

    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_headphones), null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text("Two headphones on this phone", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            if (kind == DualAudio.Kind.NONE) {
                Badge("Not available on this phone", good = false)
                Spacer(Modifier.height(8.dp))
                Text(
                    "This phone can only play to one Bluetooth headphone at a time. Use your friend's phone instead – see below.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            Badge("Best choice · no extra delay", good = true)
            Spacer(Modifier.height(8.dp))
            Text(
                if (kind == DualAudio.Kind.SAMSUNG) {
                    "Your Samsung phone can play to two Bluetooth headphones at once (Dual Audio). " +
                        "The phone keeps both perfectly in sync with the video."
                } else {
                    "Your phone can share audio to two LE Audio headphones at once (Audio sharing). " +
                        "Works with newer headphones such as Pixel Buds Pro 2, Galaxy Buds 2 Pro and later, and recent Sony models."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            NumberedStep(1, "Connect both headphones to this phone.", "Bluetooth") { DualAudio.openBluetoothSettings(ctx) }
            if (kind == DualAudio.Kind.SAMSUNG) {
                NumberedStep(2, "Tick both headphones in the list that opens.", "Choose") { DualAudio.openOutputPicker(ctx) }
            } else {
                NumberedStep(2, "Turn on Audio sharing and add the second headphones.", "Open") { DualAudio.openAudioSharing(ctx) }
            }
            NumberedStep(3, "Start your movie in any app. No need to press Play in SoundBeam.")
            Spacer(Modifier.height(4.dp))
            Text(
                if (headphones.isEmpty()) "No Bluetooth headphones connected right now." else "Connected now:",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (headphones.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    headphones.forEach { h ->
                        AssistChip(
                            onClick = {},
                            label = { Text(h) },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_headphones), null, Modifier.size(18.dp)) },
                        )
                    }
                }
            }
        }
    }
}

private enum class Role { SHARE, LISTEN }

@Composable
private fun FriendPhoneCard(actions: AppActions, onPlay: () -> Unit) {
    val listening by LiveReceiverState.listening.collectAsState()
    var role by rememberSaveable { mutableStateOf(if (listening) Role.LISTEN else Role.SHARE) }

    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_phone), null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text("Use your friend's phone", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(8.dp))
            Badge("Any phone, any headphones · tiny delay", good = true)
            Spacer(Modifier.height(8.dp))
            Text(
                "Your phone sends the movie's sound straight to your friend's phone, which plays it on their headphones. " +
                    "Both phones need SoundBeam.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(14.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = role == Role.SHARE, onClick = { role = Role.SHARE },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                ) { Text("I have the movie") }
                SegmentedButton(
                    selected = role == Role.LISTEN, onClick = { role = Role.LISTEN },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) { Text("I'm listening") }
            }
            Spacer(Modifier.height(12.dp))
            if (role == Role.SHARE) ShareSide(actions, onPlay) else ListenSide(actions)
        }
    }
}

@Composable
private fun ShareSide(actions: AppActions, onPlay: () -> Unit) {
    val ctx = LocalContext.current
    val devices by Engine.devices.collectAsState()
    val selected by Engine.selected.collectAsState()
    val states by Engine.outputStates.collectAsState()
    val running by Engine.running.collectAsState()
    val scanning by Engine.scanning.collectAsState()
    val friends = devices.filter { it.protocol == Protocol.LIVE }
    val anyChosen = friends.any { it.id in selected }

    NumberedStep(1, "On your friend's phone: open SoundBeam → Together → \"I'm listening\".")
    NumberedStep(2, "Pick their phone below, then press Start.")
    Text(
        "No Wi-Fi (plane, train, car)? Turn on this phone's hotspot and let your friend's phone join it.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 6.dp),
    )

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Nearby phones", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        if (scanning) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
        } else {
            IconButton(onClick = { Engine.scan(ctx) }) { Icon(Icons.Default.Refresh, "Look for phones again") }
        }
    }
    if (friends.isEmpty()) {
        Text(
            if (scanning) "Looking…" else "None found yet. Make sure your friend is in \"I'm listening\" and on the same Wi-Fi or your hotspot, then tap ⟳.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    friends.forEach { d ->
        val on = d.id in selected
        val state = states[d.id]
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp))
                .background(if (on) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_headphones), null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(d.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when {
                        !on -> "Ready to listen"
                        !running -> "Will hear your sound when you press Start"
                        state is OutputState.Failed -> "Couldn't connect: ${state.reason}"
                        else -> "Hearing your sound"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state is OutputState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = on, onCheckedChange = { Engine.toggleSelected(d) })
        }
    }
    Spacer(Modifier.height(12.dp))
    if (running) {
        Button(
            onClick = actions.stopStreaming,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
        ) { Text("Stop sharing", style = MaterialTheme.typography.titleMedium) }
    } else {
        Button(onClick = onPlay, enabled = anyChosen, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Icon(Icons.Default.PlayArrow, null)
            Spacer(Modifier.width(6.dp))
            Text("Start", style = MaterialTheme.typography.titleMedium)
        }
    }
    Text(
        "Keep your own headphones connected to this phone as usual. Then start the movie in any app.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun ListenSide(actions: AppActions) {
    val listening by LiveReceiverState.listening.collectAsState()
    val sender by LiveReceiverState.sender.collectAsState()
    val delayMs by LiveReceiverState.delayMs.collectAsState()
    val error by LiveReceiverState.error.collectAsState()

    NumberedStep(1, "Connect your headphones to this phone.")
    NumberedStep(2, "Tap Start listening, then ask your friend to pick \"${Engine.phoneName}\".")
    Spacer(Modifier.height(8.dp))

    if (listening) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(16.dp)) {
            Row(Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (sender == null) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
                } else {
                    Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(
                        if (sender == null) "Waiting for your friend…" else "Listening to $sender",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        if (sender == null) "This phone shows up as \"${Engine.phoneName}\"."
                        else "Delay about ${maxOf(10, delayMs)} ms · use this phone's volume buttons",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = actions.stopListening,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
        ) { Text("Stop listening", style = MaterialTheme.typography.titleMedium) }
    } else {
        Button(onClick = actions.startListening, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Icon(painterResource(R.drawable.ic_headphones), null)
            Spacer(Modifier.width(8.dp))
            Text("Start listening", style = MaterialTheme.typography.titleMedium)
        }
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
}
