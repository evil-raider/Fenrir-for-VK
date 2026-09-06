package dev.ragnarok.fenrir.util

import android.content.Context
import androidx.core.content.edit
import de.maxr1998.modernpreferences.PreferenceScreen
import dev.ragnarok.fenrir.model.Audio
import kotlin.random.Random

/**
 * FENRIR-CI: персистентный «честный» шафл, ИЗОЛИРОВАННЫЙ по источнику очереди (sourceId).
 *
 * Зачем: обычный Fisher–Yates в MusicShuffleOrder даёт равномерную перестановку в пределах
 * ОДНОЙ сессии, но ExoPlayer пересоздаёт перестановку при каждом новом построении очереди
 * (новый setMediaSources / перезапуск сервиса). Если пользователь часто прерывает
 * прослушивание, охват «съезжает» к началу перестановки: часть треков звучит многократно,
 * часть не звучит вообще. Поэтому помним, что уже прозвучало в текущем «проходе».
 *
 * Изоляция по источнику: «Моя музыка», каждый VK-плейлист/альбом и папка «На устройстве»
 * копят свой независимый проход и не влияют друг на друга. sourceId == null → ЭФЕМЕРНАЯ
 * очередь (поиск, артист, рекомендации, каталог, локальный сервер): обычный равномерный
 * шафл, состояние не читаем и не пишем.
 *
 * Устойчивость к неполному списку (важно): списки грузятся инкрементально (догрузка сотен
 * «Моей музыки», асинхронный офлайн). Поэтому:
 *  - Прогресс НЕ обнуляется при изменении состава.
 *  - Мёртвые ключи (треков уже нет в источнике) вычищаем ТОЛЬКО когда список точно полный
 *    (его размер не меньше максимума, что мы видели для источника) — тогда отсутствие ключа
 *    означает реальное удаление, а не «ещё не догружено».
 *  - Новый проход начинаем ТОЛЬКО на полном списке — иначе полностью прослушанный ПРЕФИКС
 *    ещё грузящегося списка ложно сбрасывал бы прогресс.
 *
 * Расход ресурсов: состояние держим в памяти (авторитетно во время жизни процесса) и пишем
 * в SharedPreferences через apply() — колбэк плеера markPlayed НЕ блокирует диск (в отличие
 * от прежнего синхронного commit на каждый трек) и не перечитывает CSV. Число источников
 * ограничено MRU-списком (MAX_SOURCES); самые старые вычищаются вместе со своими ключами.
 * Единственный критерий завершения прохода — состав (в buildOrder); отдельного счётчика
 * (total) больше нет.
 */
object PersistentShuffle {
    private const val PREF_PLAYED_PREFIX = "ci_shuffle_played_"
    private const val PREF_MAX_PREFIX = "ci_shuffle_max_"

    // MRU-список известных источников (свежий — первым) и его лимит.
    private const val PREF_SOURCES = "ci_shuffle_sources"
    private const val SOURCES_SEP = "\u0001"
    private const val MAX_SOURCES = 32

    // Один замок на всё состояние: buildOrder (поток построения очереди) и markPlayed
    // (onMediaItemTransition) не должны гонять read-modify-write параллельно.
    private val lock = Any()

    // Состояние в памяти — авторитетно, пока жив процесс. В prefs льём через apply().
    private val playedMem = HashMap<String, MutableSet<String>>()
    private val maxMem = HashMap<String, Int>()
    private val sources = ArrayList<String>()
    private var sourcesLoaded = false

    // Ключ трека. Для локальных файлов id = url.hashCode() (32-бит, возможны коллизии),
    // поэтому для них ключом берём стабильный file-url; для VK — стабильную пару id_ownerId.
    private fun keyOf(a: Audio): String =
        if (a.isLocal && !a.url.isNullOrEmpty()) "u:${a.url}" else "${a.id}_${a.ownerId}"

    private fun parseSet(s: String?): MutableSet<String> {
        if (s.isNullOrEmpty()) {
            return HashSet()
        }
        return HashSet(s.split(",").filter { it.isNotEmpty() })
    }

    private fun joinSet(set: Set<String>): String = set.joinToString(",")

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
            parseSet(
                PreferenceScreen.getPreferences(context)
                    .getString(PREF_PLAYED_PREFIX + sourceId, null)
            )
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

    // Асинхронно (apply) сохраняет состояние источника и MRU-список, вычищая старьё сверх
    // лимита вместе с его ключами. Значения берём из памяти (она авторитетна).
    private fun persist(context: Context, sourceId: String) {
        val played = playedFor(context, sourceId)
        val mx = maxFor(context, sourceId)
        val evicted = ArrayList<String>()
        while (sources.size > MAX_SOURCES) {
            val old = sources.removeAt(sources.size - 1)
            if (old != sourceId) {
                evicted.add(old)
            }
        }
        PreferenceScreen.getPreferences(context).edit {
            putString(PREF_PLAYED_PREFIX + sourceId, joinSet(played))
            putInt(PREF_MAX_PREFIX + sourceId, mx)
            putString(PREF_SOURCES, sources.joinToString(SOURCES_SEP))
            for (e in evicted) {
                remove(PREF_PLAYED_PREFIX + e)
                remove(PREF_MAX_PREFIX + e)
            }
        }
        for (e in evicted) {
            playedMem.remove(e)
            maxMem.remove(e)
        }
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
     * не прозвучавшие в текущем проходе треки, затем — уже прозвучавшие. Прун мёртвых ключей
     * и старт нового прохода происходят ТОЛЬКО когда список полный (n >= maxSeen), чтобы
     * неполная догрузка не сбрасывала прогресс.
     */
    fun buildOrder(context: Context, sourceId: String?, audios: List<Audio>): IntArray {
        val n = audios.size
        if (n <= 0) {
            return IntArray(0)
        }
        if (sourceId.isNullOrEmpty()) {
            return plainOrder(n)
        }
        synchronized(lock) {
            ensureSourcesLoaded(context)
            val played = playedFor(context, sourceId)
            val prevMax = maxFor(context, sourceId)
            val curMax = if (n > prevMax) n else prevMax
            // Список считаем полным, если он не меньше максимума, что мы видели для источника.
            val full = n >= curMax

            val unplayedIdx = ArrayList<Int>(n)
            val playedIdx = ArrayList<Int>()
            val presentKeys = if (full) HashSet<String>(n) else null
            for (i in 0 until n) {
                val k = keyOf(audios[i])
                presentKeys?.add(k)
                if (played.contains(k)) {
                    playedIdx.add(i)
                } else {
                    unplayedIdx.add(i)
                }
            }

            var changed = false
            // Безопасный прун: только на полном списке — отсутствующие ключи это реально
            // удалённые треки, а не «ещё не догруженные».
            if (presentKeys != null && played.retainAll(presentKeys)) {
                changed = true
            }
            // Новый проход — только на полном списке (иначе прослушанный префикс ложно
            // обнулял бы прогресс ещё грузящегося источника).
            if (full && unplayedIdx.isEmpty()) {
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
            shuffleInPlace(playedIdx, random)

            val order = IntArray(n)
            var p = 0
            for (i in unplayedIdx) {
                order[p++] = i
            }
            for (i in playedIdx) {
                order[p++] = i
            }
            return order
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
            if (played.add(keyOf(audio))) {
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
