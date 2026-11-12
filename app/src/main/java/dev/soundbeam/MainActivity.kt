package dev.soundbeam

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.soundbeam.audio.Equalizer
import dev.soundbeam.net.Renderer

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Engine.init(applicationContext)
        setContent {
            val dark = androidx.compose.foundation.isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxWidth()) { App() }
            }
        }
    }
}

@Composable
private fun App() {
    val ctx = LocalContext.current
    val running by Engine.running.collectAsState()
    val status by Engine.status.collectAsState()
    val streamUrl by Engine.streamUrl.collectAsState()

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

    Column(
        Modifier
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("SoundBeam", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Streams your phone's audio to DLNA receivers, an HTTP URL, or a file. " +
                "Play music in any app as usual.",
            style = MaterialTheme.typography.bodyMedium,
        )

        if (running) {
            Button(
                onClick = {
                    ctx.startService(Intent(ctx, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
                },
                Modifier.fillMaxWidth(),
            ) { Text("Stop streaming") }
        } else {
            Button(
                onClick = {
                    permissionLauncher.launch(
                        arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS),
                    )
                },
                Modifier.fillMaxWidth(),
            ) { Text("Start streaming") }
        }
        if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)

        Receivers(running)
        Outputs(streamUrl)
        EqualizerPanel()
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Receivers(running: Boolean) {
    val ctx = LocalContext.current
    val renderers by Engine.renderers.collectAsState()
    val selected by Engine.selected.collectAsState()
    val scanning by Engine.scanning.collectAsState()

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Receivers (DLNA / UPnP)", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = { Engine.scan(ctx) }, enabled = !scanning) {
            Text(if (scanning) "Scanning…" else "Scan")
        }
    }
    if (renderers.isEmpty()) {
        Text("Tap Scan to look for receivers on your Wi-Fi.", style = MaterialTheme.typography.bodySmall)
    }
    renderers.forEach { r ->
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = r.id in selected, onCheckedChange = { Engine.toggleSelected(r.id) }, enabled = !running)
                Text(r.name, style = MaterialTheme.typography.bodyLarge)
            }
            if (running && r.id in selected && r.renderingControlUrl != null) VolumeSlider(r)
        }
    }
}

@Composable
private fun VolumeSlider(r: Renderer) {
    var v by remember(r.id) { mutableFloatStateOf(50f) }
    Row(Modifier.padding(start = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Vol", style = MaterialTheme.typography.bodySmall)
        Slider(
            value = v,
            onValueChange = { v = it },
            onValueChangeFinished = { Engine.setVolume(r, v.toInt()) },
            valueRange = 0f..100f,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}

@Composable
private fun Outputs(streamUrl: String?) {
    val http by Engine.httpEnabled.collectAsState()
    val save by Engine.saveEnabled.collectAsState()
    val running by Engine.running.collectAsState()

    Text("Other outputs", style = MaterialTheme.typography.titleMedium)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("HTTP stream")
            Text(
                streamUrl ?: "Open the URL in VLC or any player (shown while streaming)",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(checked = http, onCheckedChange = { Engine.setHttp(it) }, enabled = !running)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Save to file")
            Text("WAV in the app's Music folder", style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = save, onCheckedChange = { Engine.setSave(it) }, enabled = !running)
    }
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
