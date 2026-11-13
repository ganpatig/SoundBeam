package dev.soundbeam.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.soundbeam.Engine
import dev.soundbeam.audio.Equalizer

private val BAND_NAMES = listOf(
    "Deep bass", "Bass", "Upper bass", "Warmth", "Body",
    "Mids", "Vocals", "Presence", "Treble", "Sparkle",
)

private val PRESET_NAMES = mapOf("Flat" to "Off")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SoundScreen() {
    val gains by Engine.eqGains.collectAsState()
    val activePreset = Equalizer.PRESETS.entries.firstOrNull { it.value == gains }?.key

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Sound", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Shape how everything sounds on all your speakers – for example more bass for a small speaker, " +
                "or clearer voices for podcasts.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(20.dp)) {
                SectionTitle("Quick settings")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Equalizer.PRESETS.forEach { (name, values) ->
                        FilterChip(
                            selected = activePreset == name,
                            onClick = { Engine.setEq(values) },
                            label = { Text(PRESET_NAMES[name] ?: name) },
                        )
                    }
                    if (activePreset == null) FilterChip(selected = true, onClick = {}, label = { Text("Custom") })
                }
            }
        }

        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
            Column(Modifier.padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        SectionTitle("Fine-tune")
                    }
                    TextButton(onClick = { Engine.setEq(List(10) { 0f }) }, enabled = activePreset != "Flat") { Text("Reset") }
                }
                Equalizer.FREQS.forEachIndexed { i, hz ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.width(92.dp)) {
                            Text(BAND_NAMES[i], style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (hz >= 1000) "${hz / 1000} kHz" else "$hz Hz",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Slider(
                            value = gains[i],
                            onValueChange = { v -> Engine.setEq(gains.toMutableList().also { it[i] = Math.round(v).toFloat() }) },
                            valueRange = -12f..12f,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            if (gains[i] == 0f) "0" else "%+.0f".format(gains[i]),
                            Modifier.width(36.dp).padding(start = 6.dp),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Values are in decibels. Small changes go a long way – big boosts can make loud music distort.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        }
    }
}
