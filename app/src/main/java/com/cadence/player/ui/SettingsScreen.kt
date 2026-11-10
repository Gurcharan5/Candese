package com.cadence.player.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cadence.player.data.RgMode
import com.cadence.player.data.ScanState
import com.cadence.player.data.Settings
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    settings: Settings,
    scan: ScanState,
    padding: PaddingValues,
    onRescan: () -> Unit,
    onRereadTags: () -> Unit,
    onRebuild: () -> Unit,
) {
    val crossfade by settings.crossfadeSeconds.collectAsStateWithLifecycle()
    val mode by settings.rgMode.collectAsStateWithLifecycle()
    val preamp by settings.preampDb.collectAsStateWithLifecycle()
    val noClip by settings.preventClipping.collectAsStateWithLifecycle()

    // Sliders edit local state and commit on release, so dragging through 0 doesn't rebuild the queue repeatedly.
    var cfDraft by remember(crossfade) { mutableFloatStateOf(crossfade.toFloat()) }
    var preampDraft by remember(preamp) { mutableFloatStateOf(preamp) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp)
            .padding(horizontal = 20.dp)
    ) {
        Text("Settings", fontSize = 34.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp, bottom = 12.dp))

        SectionTitle("Transitions")
        val cfLabel = if (cfDraft.roundToInt() == 0) "Off" else "${cfDraft.roundToInt()} seconds"
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Crossfade")
            Text(cfLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = cfDraft,
            onValueChange = { cfDraft = it },
            onValueChangeFinished = { settings.setCrossfade(cfDraft.roundToInt()) },
            valueRange = 0f..12f,
            steps = 11,
        )
        Hint("With crossfade off, tracks play gaplessly. Turning it on blends the end of one track into the next.")

        Spacer(Modifier.height(20.dp)); HorizontalDivider(); Spacer(Modifier.height(20.dp))

        SectionTitle("ReplayGain")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            val modes = listOf(RgMode.OFF to "Off", RgMode.TRACK to "Track", RgMode.ALBUM to "Album")
            modes.forEachIndexed { i, (m, label) ->
                SegmentedButton(
                    selected = mode == m,
                    onClick = { settings.setRgMode(m) },
                    shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                ) { Text(label) }
            }
        }
        Hint("Album mode keeps the loudness differences between tracks on the same album. Untagged files play unchanged.")
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Pre-amp")
            Text("%+.0f dB".format(preampDraft), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = preampDraft,
            onValueChange = { preampDraft = it.roundToInt().toFloat() },
            onValueChangeFinished = { settings.setPreamp(preampDraft) },
            valueRange = -12f..15f,
            steps = 26,
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("Prevent clipping")
                Hint("Caps any boost at the track's peak so it can't clip. Turn off to boost past that; a soft limiter then rounds off the peaks.")
            }
            Switch(checked = noClip, onCheckedChange = { settings.setPreventClipping(it) })
        }

        Spacer(Modifier.height(20.dp)); HorizontalDivider(); Spacer(Modifier.height(20.dp))

        SectionTitle("Library")
        if (scan.running) {
            Text(
                if (scan.total > 0) "Reading tags: ${scan.done} of ${scan.total}" else "Scanning library…",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onRescan, enabled = !scan.running) { Text("Scan for new music") }
        TextButton(onClick = onRereadTags, enabled = !scan.running) { Text("Re-read ReplayGain tags") }
        TextButton(onClick = onRebuild, enabled = !scan.running) { Text("Rebuild library from scratch") }
        Hint("Playlists and listening history are kept when the library is rebuilt.")
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
private fun Hint(text: String) {
    Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
