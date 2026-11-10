package com.cadence.player.data

/** Reading and writing .m3u / .m3u8 playlists. */
object M3u {
    class Imported(val matched: List<Track>, val totalEntries: Int)

    fun export(name: String, tracks: List<Track>): String = buildString {
        appendLine("#EXTM3U")
        appendLine("#PLAYLIST:$name")
        for (t in tracks) {
            appendLine("#EXTINF:${t.durationMs / 1000},${t.artist} - ${t.title}")
            appendLine(pathOf(t))
        }
    }

    /** Absolute path on the primary storage volume, which is what most players expect. */
    private fun pathOf(t: Track): String =
        if (t.fileName.isEmpty()) t.title else "/storage/emulated/0/${t.folder}${t.fileName}"

    /**
     * Matches playlist lines to library songs by the longest shared path ending, so absolute paths,
     * paths from another phone, and paths relative to the playlist file all work.
     * Lines that don't match a path fall back to the "Artist - Title" from the #EXTINF line.
     */
    fun parse(text: String, library: List<Track>): Imported {
        val bySuffix = HashMap<String, Track>()
        val byLabel = HashMap<String, Track>()
        for (t in library) {
            if (t.fileName.isNotEmpty()) {
                val parts = (t.folder + t.fileName).lowercase().split('/').filter { it.isNotEmpty() }
                for (k in parts.indices) bySuffix.putIfAbsent(parts.drop(k).joinToString("/"), t)
            }
            byLabel.putIfAbsent("${t.artist} - ${t.title}".lowercase(), t)
        }

        val matched = ArrayList<Track>()
        var total = 0
        var label: String? = null
        for (raw in text.removePrefix("\uFEFF").lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#")) {
                if (line.startsWith("#EXTINF:", ignoreCase = true)) {
                    label = line.substringAfter(',', "").trim().lowercase().ifEmpty { null }
                }
                continue
            }
            total++
            val parts = line.replace('\\', '/').removePrefix("file://").lowercase()
                .split('/').filter { it.isNotEmpty() && it != "." && it != ".." }
            var hit: Track? = null
            for (k in parts.indices) {
                hit = bySuffix[parts.drop(k).joinToString("/")]
                if (hit != null) break
            }
            if (hit == null && label != null) hit = byLabel[label]
            if (hit != null) matched += hit
            label = null
        }
        return Imported(matched, total)
    }
}
