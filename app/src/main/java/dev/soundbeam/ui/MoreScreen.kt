package dev.soundbeam.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import dev.soundbeam.Engine

@Composable
fun MoreScreen(actions: AppActions) {
    val ctx = LocalContext.current
    val access by Engine.notificationAccess.collectAsState()
    val running by Engine.running.collectAsState()
    val http by Engine.httpEnabled.collectAsState()
    val save by Engine.saveEnabled.collectAsState()
    val urls by Engine.streamUrls.collectAsState()
    val latency by Engine.airplayLatencyMs.collectAsState()
    var lat by remember { mutableFloatStateOf(latency.toFloat()) }
    val clipboard = LocalClipboardManager.current
    val version = remember {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: ""
    }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("More", style = MaterialTheme.typography.headlineMedium)

        Section {
            SettingRow(
                title = "Song titles & cover art",
                body = "Shows what's playing on speakers and TVs that can display it (AirPlay, Chromecast, Sonos, Fire TV). " +
                    "Android needs your OK to let SoundBeam read this from the playing app.",
                trailing = {
                    if (access) {
                        Text("On", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    } else {
                        FilledTonalButton(onClick = actions.openNotificationAccess) { Text("Allow") }
                    }
                },
            )
        }

        Section {
            Text("Smooth playback vs. quick response", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "AirPlay speakers keep a little audio in reserve. Shorter reacts faster when you pause or skip; " +
                    "longer avoids dropouts on weak Wi-Fi. Takes effect the next time you press Play.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = lat,
                onValueChange = { lat = (it / 100).toInt() * 100f },
                onValueChangeFinished = { Engine.setAirplayLatency(lat.toInt()) },
                valueRange = 500f..4000f,
            )
            Row {
                Text("Quicker", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                Text("%.1f s".format(lat / 1000f), style = MaterialTheme.typography.labelLarge)
                Text(
                    "Smoother", style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
            }
        }

        Section {
            Text("Extras", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            SettingRow(
                title = "Listen in a browser or VLC",
                body = "Creates a link that computers and other devices on your Wi-Fi can open to hear what's playing.",
                trailing = { Switch(checked = http, onCheckedChange = { Engine.setHttp(it) }, enabled = !running) },
            )
            if (urls.isNotEmpty()) {
                urls.forEach { u ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(u, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { clipboard.setText(AnnotatedString(u)) }) { Text("Copy") }
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            SettingRow(
                title = "Record to a file",
                body = "Saves everything you stream as a high-quality WAV file on this phone, in Android/data/dev.soundbeam/files/Music.",
                trailing = { Switch(checked = save, onCheckedChange = { Engine.setSave(it) }, enabled = !running) },
            )
            if (running) {
                Text(
                    "Stop streaming to change these.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        Section {
            Text("Is SoundBeam right for you?", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text("Great if you…", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Bullets(
                "have AirPlay, Chromecast, Sonos, HEOS or DLNA speakers (or a smart TV) and an Android phone",
                "want to play from apps that can't send to those speakers on their own",
                "want the same music in several rooms at once",
                "want a free app with no account, no ads and no subscription",
            )
            Spacer(Modifier.height(8.dp))
            Text("Probably not needed if you…", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Bullets(
                "only use Bluetooth speakers – Android already does that",
                "need perfect lip-sync for videos – network speakers add a short delay",
                "only use one app that already casts to your speaker (like Spotify Connect)",
            )
        }

        Section {
            Text("Help", style = MaterialTheme.typography.titleMedium)
            Faq(
                "Which speakers work?",
                "AirPlay and AirPlay 2 (HomePod, Apple TV, Sonos, many speakers and receivers), Chromecast and " +
                    "Google Nest, Sonos, Denon/Marantz HEOS, smart TVs and receivers with DLNA, Music Assistant " +
                    "(AriaCast) and Fire TV / Android TV with the SoundBeam Receiver app.",
            )
            Faq(
                "Why is there a delay?",
                "Speakers on Wi-Fi keep a little audio in reserve so the music doesn't stutter. That's fine for " +
                    "music but means videos can be slightly out of sync. You can shorten it under " +
                    "\"Smooth playback vs. quick response\" above.",
            )
            Faq(
                "My speakers are out of sync",
                "AirPlay speakers stay in sync with each other automatically. When you mix types (say AirPlay " +
                    "and Chromecast), open the speaker that plays first (⋮) and move \"Line up with other speakers\" " +
                    "until they sound together.",
            )
            Faq(
                "One app stays silent",
                "A few apps tell Android not to let their sound be shared (often to protect paid content). " +
                    "SoundBeam can't capture those apps – nothing can, without root.",
            )
            Faq(
                "A speaker won't connect",
                "Tap ⋮ on the speaker to see what went wrong. If it offers more than one connection type, " +
                    "try another one. Restarting the speaker also helps more often than you'd think.",
            )
            Faq(
                "Use a Fire TV or Android TV as a speaker",
                "Install the free SoundBeam Receiver app on the TV (download SoundBeam-Receiver from the " +
                    "releases page, e.g. with the Downloader app). Open it once – after that it starts by itself " +
                    "and appears in your speaker list.",
                action = "Open download page" to { actions.openUrl(RELEASES_URL) },
            )
            Faq(
                "Why does Android say SoundBeam is recording my screen?",
                "Android uses the same permission for capturing screens and capturing sound. SoundBeam only " +
                    "takes the sound, only while streaming, and never sees or saves your screen.",
            )
        }

        Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "SoundBeam $version · free & open source",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { actions.openUrl(PROJECT_URL) }) { Text("Updates, source code & feedback") }
        }
    }
}

@Composable
private fun Section(content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
    ) {
        Column(Modifier.padding(20.dp)) { content() }
    }
}

@Composable
private fun SettingRow(title: String, body: String, trailing: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(2.dp))
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        trailing()
    }
}

@Composable
private fun Bullets(vararg items: String) {
    items.forEach {
        Row(Modifier.padding(vertical = 2.dp)) {
            Text("•", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(16.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun Faq(question: String, answer: String, action: Pair<String, () -> Unit>? = null) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.animateContentSize()) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(question, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = null)
        }
        if (open) {
            Text(answer, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            action?.let { (label, onClick) -> TextButton(onClick = onClick) { Text(label) } }
            Spacer(Modifier.height(8.dp))
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    }
}
