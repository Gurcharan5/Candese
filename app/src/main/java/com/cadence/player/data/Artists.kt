package com.cadence.player.data

/** "A, B" becomes two artists when splitting is on. Never returns an empty list. */
fun splitArtists(raw: String, enabled: Boolean): List<String> {
    if (!enabled) return listOf(raw.trim().ifEmpty { "Unknown Artist" })
    val parts = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    return parts.ifEmpty { listOf("Unknown Artist") }
}
