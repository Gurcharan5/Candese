package com.cadence.player.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cadence.player.data.Track
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun HomeScreen(
    stats: ListeningStats,
    bars: List<DayBar>,
    mostPlayed: List<PlayedTrack>,
    recentlyPlayed: List<Track>,
    recentlyAdded: List<Track>,
    allTracks: List<Track>,
    padding: PaddingValues,
    actions: TrackActions,
) {
    LazyColumn(
        contentPadding = PaddingValues(
            top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                "Home", fontSize = 34.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
            )
        }
        item {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                shape = RoundedCornerShape(30.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Column(Modifier.padding(horizontal = 22.dp, vertical = 20.dp)) {
                    Text("YOUR SOUNDTRACK", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer, letterSpacing = 1.5.sp)
                    Spacer(Modifier.height(5.dp))
                    Text("A little more you.", fontSize = 27.sp, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("Pick up where the music takes you.",
                        fontSize = 14.sp, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f))
                }
            }
        }
        item { StatsCard(stats, bars, Modifier.padding(horizontal = 20.dp)) }
        item {
            PlayShuffleButtons(
                onPlay = { actions.play(allTracks, 0, false) },
                onShuffle = { actions.play(allTracks, 0, true) },
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
        if (recentlyPlayed.isNotEmpty()) {
            item { SectionHeader("Recently played") }
            item { TrackCarousel(recentlyPlayed) { i -> actions.play(recentlyPlayed, i, false) } }
        }
        if (mostPlayed.isNotEmpty()) {
            item { SectionHeader("Most played") }
            itemsIndexed(mostPlayed, key = { _, p -> "mp${p.track.id}" }) { i, p ->
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { actions.play(mostPlayed.map { it.track }, i, false) }
                        .padding(horizontal = 20.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${i + 1}", Modifier.width(24.dp), fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Artwork(p.track.uri, Modifier.size(48.dp), px = 128, corner = 6.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 16.sp)
                        Text(
                            p.track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(if (p.plays == 1) "1 play" else "${p.plays} plays", fontSize = 13.sp)
                        Text(
                            formatDuration(p.listenedMs), fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (recentlyAdded.isNotEmpty()) {
            item { SectionHeader("Recently added") }
            item { TrackCarousel(recentlyAdded) { i -> actions.play(recentlyAdded, i, false) } }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text, fontSize = 20.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 2.dp),
    )
}

@Composable
private fun TrackCarousel(tracks: List<Track>, onClick: (Int) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        itemsIndexed(tracks, key = { _, t -> t.id }) { i, t ->
            Column(Modifier.width(140.dp).clickable { onClick(i) }) {
                Artwork(t.uri, Modifier.fillMaxWidth().aspectRatio(1f), px = 320, corner = 10.dp)
                Spacer(Modifier.height(6.dp))
                Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(
                    t.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatsCard(stats: ListeningStats, bars: List<DayBar>, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.padding(20.dp)) {
            Text("Listening time", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSecondaryContainer)
            Text(
                formatDuration(stats.week), fontSize = 36.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text("in the last 7 days", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f))

            if (bars.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                val peak = (bars.maxOf { it.ms }).coerceAtLeast(1L)
                Row(Modifier.fillMaxWidth().height(96.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    bars.forEach { bar ->
                        Column(
                            Modifier.weight(1f).fillMaxHeight(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Bottom,
                        ) {
                            val fraction = bar.ms.toFloat() / peak
                            Box(
                                Modifier.fillMaxWidth(0.6f)
                                    .height((6 + 62 * fraction).dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(
                                        if (bar.ms > 0) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
                                    )
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                bar.day.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StatTile("Today", stats.today)
                StatTile("30 days", stats.month)
                StatTile("Lifetime", stats.lifetime)
            }
            if (stats.lifetime == 0L) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Play some music and your listening stats will build up here.",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatTile(label: String, ms: Long) {
    Column {
        Text(formatDuration(ms), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
