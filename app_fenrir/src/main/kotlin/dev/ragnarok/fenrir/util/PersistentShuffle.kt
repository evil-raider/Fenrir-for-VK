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
 * прослушивание, очередь каждый раз тасуется с нуля — и охват «съезжает» к началу
 * перестановки: часть треков звучит многократно, часть не звучит вообще.
 *
 * Решение: помним множество уже прозвучавших треков в рамках текущего «прохода» ОТДЕЛЬНО
 * для каждого источника (sourceId): «Моя музыка», конкретный VK-плейлист/альбом, папка «На
 * устройстве» и т.д. При построении шафл-порядка ещё НЕ прозвучавшие в этом проходе треки
 * ставим в начало (в случайном порядке), уже прозвучавшие — в хвост. Когда пройден весь
 * ТЕКУЩИЙ состав источника — цикл сбрасывается и начинается новый проход.
 *
 * Важные отличия от прежней версии:
 *  - Нет единого глобального состояния и нет «signature». Раньше любое изменение состава
 *    (ночная докачка, догрузка следующей сотни «Моей музыки», пропавший трек) меняло
 *    signature и обнуляло весь прогресс; теперь состав можно менять — проход считается по
 *    актуальному списку, прогресс не сбрасывается «на ровном месте».
 *  - Разные источники не влияют друг на друга: послушать отдельный плейлист больше не
 *    портит прогресс «Моей музыки».
 *  - sourceId == null → ЭФЕМЕРНАЯ очередь (поиск, артист, рекомендации, каталог, локальный
 *    сервер, одиночные ссылки): обычный равномерный шафл, состояние не читаем и не пишем.
 *
 * Состояние храним в SharedPreferences как CSV-строки (без StringSet — надёжнее на любой
 * реализации SharedPreferences), ключи — с суффиксом sourceId. Чтобы префы не пухли от
 * множества разовых плейлистов, ведём MRU-список источников и вычищаем самые старые сверх
 * лимита (MAX_SOURCES).
 */
object PersistentShuffle {
    private const val PREF_PLAYED_PREFIX = "ci_shuffle_played_"
    private const val PREF_TOTAL_PREFIX = "ci_shuffle_total_"

    // MRU-список известных источников (свежий — первым) и его лимит.
    private const val PREF_SOURCES = "ci_shuffle_sources"
    private const val SOURCES_SEP = "\u0001"
    private const val MAX_SOURCES = 32

    private fun keyOf(a: Audio): String = "${a.id}_${a.ownerId}"

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

    private fun loadPlayed(context: Context, sourceId: String): MutableSet<String> {
        return parseSet(
            PreferenceScreen.getPreferences(context).getString(PREF_PLAYED_PREFIX + sourceId, null)
        )
    }

    // Сохраняет прогресс источника и подтягивает его в начало MRU-списка (с вычисткой старых).
    private fun save(context: Context, sourceId: String, played: Set<String>, total: Int?) {
        val prefs = PreferenceScreen.getPreferences(context)
        val sources = parseSources(prefs.getString(PREF_SOURCES, null))
        sources.remove(sourceId)
        sources.add(0, sourceId)
        val evicted = ArrayList<String>()
        while (sources.size > MAX_SOURCES) {
            evicted.add(sources.removeAt(sources.size - 1))
        }
        prefs.edit(true) {
            putString(PREF_PLAYED_PREFIX + sourceId, joinSet(played))
            if (total != null) {
                putInt(PREF_TOTAL_PREFIX + sourceId, total)
            }
            putString(PREF_SOURCES, sources.joinToString(SOURCES_SEP))
            for (e in evicted) {
                remove(PREF_PLAYED_PREFIX + e)
                remove(PREF_TOTAL_PREFIX + e)
            }
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
     * sourceId != null → персистентный проход именно этого источника: сначала (в случайном
     * порядке) ещё не прозвучавшие в текущем проходе треки, затем — уже прозвучавшие. Если
     * прозвучал весь ТЕКУЩИЙ состав — сброс и новый проход. Изменение состава само по себе
     * проход НЕ сбрасывает.
     */
    fun buildOrder(context: Context, sourceId: String?, audios: List<Audio>): IntArray {
        val n = audios.size
        if (n <= 0) {
            return IntArray(0)
        }
        if (sourceId.isNullOrEmpty()) {
            return plainOrder(n)
        }
        val stored = loadPlayed(context, sourceId)

        // Прогресс считаем по актуальному составу: в множестве «прозвучавших» оставляем только
        // те ключи, что реально есть в текущем списке (иначе оно копило бы удалённые треки и
        // «проход» никогда бы не завершался).
        val played = HashSet<String>()
        val unplayedIdx = ArrayList<Int>(n)
        val playedIdx = ArrayList<Int>()
        for (i in 0 until n) {
            val k = keyOf(audios[i])
            if (stored.contains(k)) {
                played.add(k)
                playedIdx.add(i)
            } else {
                unplayedIdx.add(i)
            }
        }
        if (unplayedIdx.isEmpty()) {
            // Проход завершён — начинаем новый по всему текущему составу.
            played.clear()
            playedIdx.clear()
            for (i in 0 until n) {
                unplayedIdx.add(i)
            }
        }
        save(context, sourceId, played, n)

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

    /**
     * Отмечает трек как прозвучавший в текущем проходе источника sourceId. Для эфемерных
     * очередей (sourceId == null) — no-op. Когда прозвучали все треки прохода (по последнему
     * известному размеру состава), множество очищается — следующий buildOrder() начнёт новый
     * цикл.
     */
    fun markPlayed(context: Context, sourceId: String?, audio: Audio?) {
        if (sourceId.isNullOrEmpty() || audio == null) {
            return
        }
        val prefs = PreferenceScreen.getPreferences(context)
        val total = prefs.getInt(PREF_TOTAL_PREFIX + sourceId, 0)
        val played = parseSet(prefs.getString(PREF_PLAYED_PREFIX + sourceId, null))
        if (played.add(keyOf(audio))) {
            if (total in 1..played.size) {
                save(context, sourceId, emptySet(), total)
            } else {
                save(context, sourceId, played, null)
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
