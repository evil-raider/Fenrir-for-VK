package dev.ragnarok.fenrir.util

import android.content.Context
import androidx.core.content.edit
import de.maxr1998.modernpreferences.PreferenceScreen
import dev.ragnarok.fenrir.model.Audio
import kotlin.random.Random

/**
 * Per-source persistent listening history. A track is marked by the player only
 * after reaching two thirds of its duration, including a manual seek. A new pass
 * may start only when the queue matches a confirmed complete snapshot. A failed
 * track remains unplayed and may be temporarily deferred behind eligible tracks.
 * Old visit-only history is not migrated because it cannot prove the threshold.
 */
object PersistentShuffle {
    // Legacy v2 tracked visits, including failed/short tracks. It cannot safely be
    // migrated into a completed-listening pass. Start a new v3 pass after upgrade.
    private const val PREF_PLAYED_V2_PREFIX = "ci_shuffle_heard_v3_"
    private const val PREF_COMPLETE_PREFIX = "ci_shuffle_complete_v3_"
    private const val PREF_MAX_PREFIX = "ci_shuffle_max_"

    // Known sources; never evict history just because other sources were played.
    private const val PREF_SOURCES = "ci_shuffle_sources"
    private const val SOURCES_SEP = "\u0001"

    // Один замок на всё состояние: buildOrder (поток построения очереди) и markPlayed
    // must not race read-modify-write operations.
    private val lock = Any()

    // Состояние в памяти — авторитетно, пока жив процесс. В prefs льём через apply().
    private val playedMem = HashMap<String, MutableSet<String>>()
    private val maxMem = HashMap<String, Int>()
    private val sources = ArrayList<String>()
    private var sourcesLoaded = false

    // Identity follows the unified playlist's artist/title deduplication policy.
    fun keyOf(a: Audio): String {
        // Match the same named recording across VK and local copies. Length prefixes
        // disambiguate separators in artist/title. Fall back to stable source identity.
        val artist = a.artist?.trim()?.lowercase(java.util.Locale.ROOT).orEmpty()
        val title = a.title?.trim()?.lowercase(java.util.Locale.ROOT).orEmpty()
        if (artist.isNotEmpty() && title.isNotEmpty()) return "r:${artist.length}:$artist:${title.length}:$title"
        return if (a.isLocal && !a.url.isNullOrEmpty()) "u:${a.url}" else "${a.id}_${a.ownerId}"
    }

    private fun parseSources(s: String?): MutableList<String> {
        if (s.isNullOrEmpty()) {
            return ArrayList()
        }
        return ArrayList(s.split(SOURCES_SEP).filter { it.isNotEmpty() })
    }

    private fun ensureSourcesLoaded(context: Context) {
        if (sourcesLoaded) {
            return
        }
        sourcesLoaded = true
        sources.clear()
        sources.addAll(
            parseSources(PreferenceScreen.getPreferences(context).getString(PREF_SOURCES, null))
        )
    }

    // Ленивая гидратация набора прослушанного по источнику (грузим только реально нужные).
    private fun playedFor(context: Context, sourceId: String): MutableSet<String> =
        playedMem.getOrPut(sourceId) {
            val prefs = PreferenceScreen.getPreferences(context)
            // Copy: SharedPreferences owns the returned set. Legacy CSV could split file URLs
            // containing commas; StringSet preserves each key verbatim from now on.
            prefs.getStringSet(PREF_PLAYED_V2_PREFIX + sourceId, null)?.toMutableSet() ?: HashSet()
        }

    private fun maxFor(context: Context, sourceId: String): Int =
        maxMem.getOrPut(sourceId) {
            PreferenceScreen.getPreferences(context).getInt(PREF_MAX_PREFIX + sourceId, 0)
        }

    private fun touchSource(sourceId: String) {
        if (sources.firstOrNull() == sourceId) {
            return
        }
        sources.remove(sourceId)
        sources.add(0, sourceId)
    }

    // Persist source state asynchronously; retain all source histories.
    private fun persist(context: Context, sourceId: String) {
        val played = playedFor(context, sourceId)
        val mx = maxFor(context, sourceId)
        PreferenceScreen.getPreferences(context).edit {
            putStringSet(PREF_PLAYED_V2_PREFIX + sourceId, HashSet(played))
            putInt(PREF_MAX_PREFIX + sourceId, mx)
            putString(PREF_SOURCES, sources.joinToString(SOURCES_SEP))
        }
    }

    private fun snapshotToken(audios: List<Audio>): String {
        val keys = audios.map(::keyOf).distinct().sorted()
        return PlaybackTrace.token(keys.joinToString("") { "${it.length}:$it" })
    }

    /** Called only after the complete remote list and its local merge are available. */
    fun confirmSnapshot(context: Context, sourceId: String?, audios: List<Audio>) {
        if (sourceId.isNullOrEmpty() || audios.isEmpty()) return
        synchronized(lock) {
            ensureSourcesLoaded(context)
            val played = playedFor(context, sourceId)
            played.retainAll(audios.map(::keyOf).toSet())
            maxMem[sourceId] = audios.size
            touchSource(sourceId)
            PreferenceScreen.getPreferences(context).edit {
                putString(PREF_COMPLETE_PREFIX + sourceId, snapshotToken(audios))
            }
            persist(context, sourceId)
        }
    }

    fun wasPlayed(context: Context, sourceId: String?, audio: Audio): Boolean {
        if (sourceId.isNullOrEmpty()) return false
        synchronized(lock) { return keyOf(audio) in playedFor(context, sourceId) }
    }

    private fun plainOrder(n: Int): IntArray {
        val idx = ArrayList<Int>(n)
        for (i in 0 until n) {
            idx.add(i)
        }
        shuffleInPlace(idx, Random(System.nanoTime()))
        val order = IntArray(n)
        for (i in 0 until n) {
            order[i] = idx[i]
        }
        return order
    }

    /**
     * Строит перестановку индексов [0, audios.size).
     *
     * sourceId == null → эфемерная очередь: обычный равномерный шафл, состояние не трогаем.
     * sourceId != null → персистентный проход источника: сначала (в случайном порядке) ещё
     * не прозвучавшие в текущем проходе треки, затем — уже прозвучавшие без anchorIndex.
     * С anchorIndex история ставится перед текущим, оставшиеся — после. Прун мёртвых ключей
     * и старт нового прохода происходят ТОЛЬКО для подтверждённого полного снимка, чтобы
     * неполная догрузка не сбрасывала прогресс.
     */
    fun buildOrder(context: Context, sourceId: String?, audios: List<Audio>, anchorIndex: Int = -1,
                   deferred: Set<String> = emptySet()): IntArray {
        val n = audios.size
        if (n <= 0) {
            return IntArray(0)
        }
        if (sourceId.isNullOrEmpty()) {
            return anchor(plainOrder(n), emptySet(), anchorIndex)
        }
        synchronized(lock) {
            ensureSourcesLoaded(context)
            val played = playedFor(context, sourceId)
            val prevMax = maxFor(context, sourceId)
            val curMax = if (n > prevMax) n else prevMax
            // A size is not evidence of completeness. Match the authoritative full snapshot.
            val full = PreferenceScreen.getPreferences(context)
                .getString(PREF_COMPLETE_PREFIX + sourceId, null) == snapshotToken(audios)

            val unplayedIdx = ArrayList<Int>(n)
            val playedIdx = ArrayList<Int>()
            val deferredIdx = ArrayList<Int>()
            val presentKeys = if (full) HashSet<String>(n) else null
            for (i in 0 until n) {
                val k = keyOf(audios[i])
                presentKeys?.add(k)
                if (played.contains(k)) {
                    playedIdx.add(i)
                } else if (k in deferred) {
                    deferredIdx.add(i)
                } else {
                    unplayedIdx.add(i)
                }
            }

            val playedBefore = played.size
            var reset = false
            var changed = false
            // Безопасный прун: только на подтверждённом полном списке — отсутствующие ключи это реально
            // удалённые треки, а не «ещё не догруженные».
            if (presentKeys != null && played.retainAll(presentKeys)) {
                changed = true
            }
            // Новый проход — только на полном списке (иначе прослушанный префикс ложно
            // обнулял бы прогресс ещё грузящегося источника).
            if (full && unplayedIdx.isEmpty() && deferredIdx.isEmpty()) {
                reset = true
                if (played.isNotEmpty()) {
                    played.clear()
                    changed = true
                }
                playedIdx.clear()
                for (i in 0 until n) {
                    unplayedIdx.add(i)
                }
            }
            if (curMax != prevMax) {
                maxMem[sourceId] = curMax
                changed = true
            }
            touchSource(sourceId)
            // Персистим только при содержательном изменении; порядок MRU долетит со следующим
            // значимым persist — это лишь мягкая подсказка для вычистки.
            if (changed) {
                persist(context, sourceId)
            }

            val random = Random(System.nanoTime())
            shuffleInPlace(unplayedIdx, random)
            shuffleInPlace(deferredIdx, random)
            shuffleInPlace(playedIdx, random)

            val order = IntArray(n)
            var p = 0
            for (i in unplayedIdx) {
                order[p++] = i
            }
            for (i in deferredIdx) {
                order[p++] = i
            }
            for (i in playedIdx) {
                order[p++] = i
            }
            val result = anchor(order, playedIdx.toSet(), anchorIndex)
            PlaybackTrace.event(context, "shuffle_build", "source" to PlaybackTrace.token(sourceId),
                "size" to n, "unique" to audios.map(::keyOf).toSet().size,
                "playedBefore" to playedBefore, "playedNow" to played.size,
                "unplayed" to (unplayedIdx.size + deferredIdx.size), "deferred" to deferredIdx.size, "maxSeen" to curMax, "assumedFull" to full,
                "reset" to reset, "anchor" to anchorIndex)
            return result
        }
    }

    // Already played BEFORE current, all remaining AFTER current. Merely swapping current
    // with position zero puts one already-played item among the unplayed suffix.
    internal fun anchor(order: IntArray, played: Set<Int>, current: Int): IntArray {
        if (current !in order.indices) return order
        return (order.filter { it != current && it in played } + current +
            order.filter { it != current && it !in played }).toIntArray()
    }

    fun isPassComplete(context: Context, sourceId: String?, audios: List<Audio>): Boolean {
        if (sourceId.isNullOrEmpty() || audios.isEmpty()) return false
        synchronized(lock) {
            val played = playedFor(context, sourceId)
            val full = PreferenceScreen.getPreferences(context)
                .getString(PREF_COMPLETE_PREFIX + sourceId, null) == snapshotToken(audios)
            return full && audios.all { keyOf(it) in played }
        }
    }

    /**
     * Отмечает трек как прозвучавший в текущем проходе источника sourceId. Для эфемерных
     * очередей (sourceId == null) — no-op. Завершение прохода здесь НЕ решается (единый
     * критерий — состав в buildOrder), тут только копим ключи.
     */
    fun markPlayed(context: Context, sourceId: String?, audio: Audio?) {
        if (sourceId.isNullOrEmpty() || audio == null) {
            return
        }
        synchronized(lock) {
            ensureSourcesLoaded(context)
            val played = playedFor(context, sourceId)
            val added = played.add(keyOf(audio))
            PlaybackTrace.event(context, "shuffle_mark", "source" to PlaybackTrace.token(sourceId),
                "track" to PlaybackTrace.audio(audio), "added" to added, "played" to played.size)
            if (added) {
                touchSource(sourceId)
                persist(context, sourceId)
            }
        }
    }

    private fun shuffleInPlace(list: MutableList<Int>, random: Random) {
        for (i in list.size - 1 downTo 1) {
            val j = random.nextInt(i + 1)
            val tmp = list[i]
            list[i] = list[j]
            list[j] = tmp
        }
    }
}
