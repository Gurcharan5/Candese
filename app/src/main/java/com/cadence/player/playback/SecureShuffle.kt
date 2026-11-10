package com.cadence.player.playback

import java.security.SecureRandom

/**
 * Unbiased shuffle: Fisher-Yates driven by a cryptographically strong RNG.
 * Every permutation is equally likely (SecureRandom.nextInt(bound) uses rejection sampling, so there is no modulo bias),
 * and there is no seed to predict or repeat.
 */
object SecureShuffle {
    private val rng = SecureRandom()

    fun <T> shuffled(list: List<T>): List<T> {
        val a = ArrayList(list)
        for (i in a.lastIndex downTo 1) {
            val j = rng.nextInt(i + 1)
            val tmp = a[i]; a[i] = a[j]; a[j] = tmp
        }
        return a
    }

    fun randomIndex(size: Int): Int = if (size <= 1) 0 else rng.nextInt(size)
}
