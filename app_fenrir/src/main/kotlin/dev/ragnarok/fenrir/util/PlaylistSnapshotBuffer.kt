package dev.ragnarok.fenrir.util

/** Isolated staging area: do not publish its result until an empty final page. */
class PlaylistSnapshotBuffer<T>(private val key: (T) -> String) {
    private val staged = ArrayList<T>()
    private val seen = HashSet<String>()
    var offset: Int = 0
        private set
    var complete = false
        private set

    fun addPage(requestedOffset: Int, page: List<T>) {
        check(!complete && requestedOffset == offset) { "Unexpected VK page offset" }
        if (page.isEmpty()) {
            complete = true
            return
        }
        check(offset == 0 || page.any { key(it) !in seen }) { "VK returned a repeated page" }
        offset += page.size // VK offsets refer to raw rows, not deduplicated rows.
        for (item in page) if (seen.add(key(item))) staged.add(item)
    }

    fun result(): List<T> {
        check(complete) { "Snapshot is not complete" }
        return staged.toList()
    }
}
