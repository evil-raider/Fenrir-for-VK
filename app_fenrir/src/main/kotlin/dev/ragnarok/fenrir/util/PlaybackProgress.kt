package dev.ragnarok.fenrir.util

/** Playback position, including user seeks, is the definition of progress for a pass. */
object PlaybackProgress {
    fun reachedTwoThirds(positionMs: Long, durationMs: Long): Boolean {
        if (durationMs <= 0 || positionMs < 0) return false
        // ceil(2 * duration / 3), without overflow.
        val threshold = (durationMs / 3) * 2 + ((durationMs % 3) * 2 + 2) / 3
        return positionMs >= threshold
    }
}
