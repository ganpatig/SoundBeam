package dev.soundbeam

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.soundbeam.audio.Equalizer
import dev.soundbeam.core.Device
import dev.soundbeam.core.OutputState
import dev.soundbeam.core.Protocol

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Engine.init(applicationContext)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { App() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Engine.notificationAccess.value = NowPlayingListener.hasAccess(this)
    }
}

@Composable
private fun App() {
    val ctx = LocalContext.current
    val running by Engine.running.collectAsState()
    val status by Engine.status.collectAsState()

    val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode == Activity.RESULT_OK && data != null) {
            ctx.startForegroundService(
                Intent(ctx, CaptureService::class.java)
                    .setAction(CaptureService.ACTION_START)
                    .putExtra(CaptureService.EXTRA_CODE, r.resultCode)
                    .putExtra(CaptureService.EXTRA_DATA, data),
            )
        } else {
            Engine.status.value = "Audio capture was not allowed"
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants[Manifest.permission.RECORD_AUDIO] == true) {
            val mpm = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projectionLauncher.launch(mpm.createScreenCaptureIntent())
        } else {
            Engine.status.value = "Microphone permission is required to capture audio"
        }
    }

    LaunchedEffect(Unit) {
        if (Engine.devices.value.isEmpty()) Engine.scan(ctx)
    }

    Column(
        Modifier
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("SoundBeam", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Play audio in any app - SoundBeam sends it to your speakers. Tick receivers any time, even while streaming.",
            style = MaterialTheme.typography.bodyMedium,
        )

        if (running) {
            Button(
                onClick = { ctx.startService(Intent(ctx, CaptureService::class.java).setAction(CaptureService.ACTION_STOP)) },
                Modifier.fillMaxWidth(),
            ) { Text("Stop streaming") }
        } else {
            Button(
                onClick = {
                    permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
                },
                Modifier.fillMaxWidth(),
            ) { Text("Start streaming") }
        }
        if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)

        NowPlayingCard()
        Receivers()
        HorizontalDivider()
        Outputs()
        HorizontalDivider()
        LatencySettings()
        HorizontalDivider()
        EqualizerPanel()
        Spacer(Modifier.height(24.dp))
    }

    PinDialog()
}

@Composable
private fun NowPlayingCard() {
    val ctx = LocalContext.current
    val np by Engine.nowPlaying.collectAsState()
    val access by Engine.notificationAccess.collectAsState()

    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            val art = np?.artworkJpeg
            val bmp = remember(art?.size, np?.title) { art?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
            if (bmp != null) {
                Image(bmp, contentDescription = "Cover art", modifier = Modifier.size(56.dp))
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                when {
                    !access -> {
                        Text("Show track info on your speakers", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Allow notification access so SoundBeam can read titles and cover art from the playing app.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = {
                            ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        }) { Text("Allow") }
                    }
                    np?.title.isNullOrBlank() -> Text("Nothing playing", style = MaterialTheme.typography.bodyMedium)
                    else -> {
                        Text(np?.title.orEmpty(), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(np?.artist, np?.album).filter { it.isNotBlank() }.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Receivers() {
    val ctx = LocalContext.current
    val devices by Engine.devices.collectAsState()
    val scanning by Engine.scanning.collectAsState()

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Receivers", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = { Engine.scan(ctx) }, enabled = !scanning) {
            Text(if (scanning) "Scanning…" else "Scan")
        }
    }
    if (devices.isEmpty() && !scanning) {
        Text(
            "No receivers yet. Make sure your phone is on the same Wi-Fi as your speakers, then tap Scan.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    devices.groupBy { it.protocol }.forEach { (protocol, list) ->
        Text(protocol.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        list.forEach { DeviceRow(it) }
    }
}

@Composable
private fun DeviceRow(d: Device) {
    val selected by Engine.selected.collectAsState()
    val states by Engine.outputStates.collectAsState()
    var expanded by remember(d.id) { mutableStateOf(false) }
    val isOn = d.id in selected
    val state = states[d.id]

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = isOn, onCheckedChange = { Engine.toggleSelected(d) })
            Column(Modifier.weight(1f)) {
                Text(d.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = listOfNotNull(d.model?.takeIf { it.isNotBlank() }, state?.label).joinToString(" · ")
                if (sub.isNotEmpty()) {
                    Text(
                        sub,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state is OutputState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
            }
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Done" else "Adjust") }
        }
        if (expanded) DeviceSettings(d)
    }
}

@Composable
private fun DeviceSettings(d: Device) {
    var vol by remember(d.id) { mutableFloatStateOf(Engine.volumeFor(d.id).toFloat()) }
    var delay by remember(d.id) { mutableFloatStateOf(Engine.delayFor(d.id).toFloat()) }
    Column(Modifier.padding(start = 48.dp, end = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Volume", Modifier.width(72.dp), style = MaterialTheme.typography.bodySmall)
            Slider(
                value = vol, onValueChange = { vol = it },
                onValueChangeFinished = { Engine.setVolume(d.id, vol.toInt()) },
                valueRange = 0f..100f, modifier = Modifier.weight(1f),
            )
            Text(if (vol.toInt() == 0) "Muted" else "${vol.toInt()}", Modifier.width(48.dp), style = MaterialTheme.typography.bodySmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Delay", Modifier.width(72.dp), style = MaterialTheme.typography.bodySmall)
            Slider(
                value = delay, onValueChange = { delay = (it / 50).toInt() * 50f },
                onValueChangeFinished = { Engine.setDelay(d.id, delay.toInt()) },
                valueRange = 0f..5000f, modifier = Modifier.weight(1f),
            )
            Text("${delay.toInt()} ms", Modifier.width(64.dp), style = MaterialTheme.typography.bodySmall)
        }
        Text(
            "Delay holds this receiver back so it lines up with slower ones (e.g. AirPlay next to Chromecast).",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (d.protocol == Protocol.AIRPLAY2 && Engine.airplayPairing(d.id) != null) {
            TextButton(onClick = { Engine.forgetAirplayPairing(d.id) }) { Text("Forget pairing") }
        }
    }
}

@Composable
private fun Outputs() {
    val http by Engine.httpEnabled.collectAsState()
    val save by Engine.saveEnabled.collectAsState()
    val running by Engine.running.collectAsState()
    val urls by Engine.streamUrls.collectAsState()

    Text("Other outputs", style = MaterialTheme.typography.titleMedium)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("HTTP stream")
            Text(
                if (urls.isNotEmpty()) urls.joinToString("\n") else "Play a URL in VLC or any player (shown while streaming)",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(checked = http, onCheckedChange = { Engine.setHttp(it) }, enabled = !running)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Save to file")
            Text("Lossless WAV in Android/data/dev.soundbeam/files/Music", style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = save, onCheckedChange = { Engine.setSave(it) }, enabled = !running)
    }
}

@Composable
private fun LatencySettings() {
    val latency by Engine.airplayLatencyMs.collectAsState()
    var v by remember { mutableFloatStateOf(latency.toFloat()) }
    Text("Latency", style = MaterialTheme.typography.titleMedium)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("AirPlay buffer", Modifier.width(110.dp), style = MaterialTheme.typography.bodySmall)
        Slider(
            value = v, onValueChange = { v = (it / 100).toInt() * 100f },
            onValueChangeFinished = { Engine.setAirplayLatency(v.toInt()) },
            valueRange = 500f..4000f, modifier = Modifier.weight(1f),
        )
        Text("${v.toInt()} ms", Modifier.width(64.dp), style = MaterialTheme.typography.bodySmall)
    }
    Text(
        "Lower is snappier, higher survives weak Wi-Fi. Applies to AirPlay receivers you add next. " +
            "All AirPlay receivers play in sync with each other; mixing protocols may need the per-receiver delay.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun EqualizerPanel() {
    val gains by Engine.eqGains.collectAsState()

    Text("Equalizer (10-band)", style = MaterialTheme.typography.titleMedium)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Equalizer.PRESETS.forEach { (name, values) ->
            FilterChip(selected = gains == values, onClick = { Engine.setEq(values) }, label = { Text(name) })
        }
    }
    Equalizer.FREQS.forEachIndexed { i, hz ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (hz >= 1000) "${hz / 1000} kHz" else "$hz Hz",
                Modifier.width(64.dp),
                style = MaterialTheme.typography.bodySmall,
            )
            Slider(
                value = gains[i],
                onValueChange = { v -> Engine.setEq(gains.toMutableList().also { it[i] = v }) },
                valueRange = -12f..12f,
                modifier = Modifier.weight(1f),
            )
            Text("%+.0f dB".format(gains[i]), Modifier.width(56.dp), style = MaterialTheme.typography.bodySmall)
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
                Text("Enter the PIN shown on the TV or receiver.")
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter(Char::isDigit).take(8) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    label = { Text("PIN") },
                )
            }
        },
        confirmButton = { TextButton(onClick = { Engine.submitPin(pin) }, enabled = pin.length >= 4) { Text("Pair") } },
        dismissButton = { TextButton(onClick = { Engine.submitPin(null) }) { Text("Cancel") } },
    )
}
