package androidx.media3.exoplayer.source
interface ShuffleOrder {
    fun getLength(): Int
    fun getNextIndex(index: Int): Int
    fun getPreviousIndex(index: Int): Int
    fun getFirstIndex(): Int
    fun getLastIndex(): Int
    fun cloneAndInsert(insertionIndex: Int, insertionCount: Int): ShuffleOrder
    fun cloneAndRemove(indexFrom: Int, indexToExclusive: Int): ShuffleOrder
    fun cloneAndMove(indexFrom: Int, indexToExclusive: Int, newIndexFrom: Int): ShuffleOrder
    fun cloneAndClear(): ShuffleOrder
}
