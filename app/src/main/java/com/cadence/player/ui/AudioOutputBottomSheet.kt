package com.cadence.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioOutputBottomSheet(
    onDismissRequest: () -> Unit,
    activePipelineFormat: String = "16-bit PCM · 48 kHz"
) {
    var volumeLevel by remember { mutableStateOf(0.7f) }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        containerColor = Color(0xFF1C1D21),
        scrimColor = Color.Black.copy(alpha = 0.6f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Text(
                text = "Audio output",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // Current Device Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF2B2C30))
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.PhoneAndroid, contentDescription = null, tint = Color.White)
                Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text("This phone", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                    Text("Playing here", color = Color(0xFF98989F), style = MaterialTheme.typography.bodySmall)
                }
                Icon(Icons.Default.Check, contentDescription = null, tint = Color.White)
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Cast Option
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF222327))
                    .clickable { /* Cast action */ }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Cast, contentDescription = null, tint = Color(0xFF98989F))
                Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text("Cast", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                    Text("Chromecast and Google Cast speakers", color = Color(0xFF98989F), style = MaterialTheme.typography.bodySmall)
                }
                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color(0xFF98989F))
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Volume Control Slider
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.VolumeDown, contentDescription = null, tint = Color(0xFF98989F))
                Slider(
                    value = volumeLevel,
                    onValueChange = { volumeLevel = it },
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White
                    )
                )
                Icon(Icons.Default.VolumeUp, contentDescription = null, tint = Color(0xFF98989F))
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Audio Pipeline Spec Info
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF222327))
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.GraphicEq, contentDescription = null, tint = Color(0xFF98989F))
                Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text("Audio Pipeline", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                    Text(activePipelineFormat, color = Color(0xFF98989F), style = MaterialTheme.typography.bodySmall)
                }
                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color(0xFF98989F))
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
