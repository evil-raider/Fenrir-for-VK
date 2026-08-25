package dev.ragnarok.fenrir.util

import android.content.Context
import androidx.core.content.edit
import de.maxr1998.modernpreferences.PreferenceScreen
import dev.ragnarok.fenrir.model.Audio
import kotlin.random.Random

/**
 * FENRIR-CI: персистентный «честный» шафл по ВСЕЙ библиотеке.
 *
 * Зачем: обычный Fisher–Yates в MusicShuffleOrder даёт равномерную перестановку в пределах
 * ОДНОЙ сессии, но ExoPlayer пересоздаёт перестановку при каждом новом построении очереди
 * (новый setMediaSources / перезапуск сервиса). Если пользователь часто прерывает
 * прослушивание, очередь каждый раз тасуется с нуля — и охват «съезжает» к началу
 * перестановки: часть треков звучит многократно, часть не звучит вообще.
 *
 * Решение: помним множество уже прозвучавших треков в рамках текущего «прохода» по
 * библиотеке (signature = размер набора + хэш отсортированных ключей id_ownerId). При
 * построении шафл-порядка ещё НЕ прозвучавшие треки ставим в начало (в случайном порядке),
 * уже прозвучавшие — в хвост. Когда пройдены все — цикл сбрасывается и начинается новый
 * проход. Так «абсолютно случайное» воспроизведение примерно равномерно охватывает все
 * треки независимо от того, как часто прерывается прослушивание.
 *
 * Состояние храним в SharedPreferences как CSV-строку (без StringSet — надёжнее на любой
 * реализации SharedPreferences).
 */
object PersistentShuffle {
    private const val PREF_SIGNATURE = "ci_shuffle_signature"
    private const val PREF_PLAYED = "ci_shuffle_played"
    private const val PREF_TOTAL = "ci_shuffle_total"

    private fun keyOf(a: Audio): String = "${a.id}_${a.ownerId}"

    // Стабильная подпись набора треков: размер + хэш отсортированных ключей.
    fun signatureOf(audios: List<Audio>): String {
        val keys = ArrayList<String>(audios.size)
        for (a in audios) {
            keys.add(keyOf(a))
        }
        keys.sort()
        return audios.size.toString() + ":" + keys.joinToString(",").hashCode().toString()
    }

    private fun parseSet(s: String?): MutableSet<String> {
        if (s.isNullOrEmpty()) {
            return HashSet()
        }
        return HashSet(s.split(",").filter { it.isNotEmpty() })
    }

    private fun joinSet(set: Set<String>): String = set.joinToString(",")

    private fun loadPlayed(context: Context, signature: String): MutableSet<String> {
        val prefs = PreferenceScreen.getPreferences(context)
        val savedSig = prefs.getString(PREF_SIGNATURE, null)
        if (savedSig != signature) {
            return HashSet()
        }
        return parseSet(prefs.getString(PREF_PLAYED, null))
    }

    private fun save(context: Context, signature: String, played: Set<String>, total: Int) {
        PreferenceScreen.getPreferences(context).edit(true) {
            putString(PREF_SIGNATURE, signature)
            putString(PREF_PLAYED, joinSet(played))
            putInt(PREF_TOTAL, total)
        }
    }

    /**
     * Строит перестановку индексов [0, audios.size): сначала (в случайном порядке) индексы
     * треков, которых нет в множестве уже прозвучавших для текущей signature, затем —
     * уже прозвучавшие. Если прозвучали все, сбрасывает цикл и тасует всё заново.
     */
    fun buildOrder(context: Context, audios: List<Audio>): IntArray {
        val n = audios.size
        if (n <= 0) {
            return IntArray(0)
        }
        val signature = signatureOf(audios)
        var played = loadPlayed(context, signature)

        val unplayedIdx = ArrayList<Int>(n)
        val playedIdx = ArrayList<Int>()
        for (i in 0 until n) {
            if (played.contains(keyOf(audios[i]))) {
                playedIdx.add(i)
            } else {
                unplayedIdx.add(i)
            }
        }
        if (unplayedIdx.isEmpty()) {
            // Проход завершён — начинаем новый.
            played = HashSet()
            for (i in 0 until n) {
                unplayedIdx.add(i)
            }
            playedIdx.clear()
        }
        save(context, signature, played, n)

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
     * Отмечает трек как прозвучавший в текущем проходе. Когда прозвучали все треки прохода,
     * очищает множество — следующий buildOrder() начнёт новый цикл по всей библиотеке.
     */
    fun markPlayed(context: Context, audio: Audio?) {
        audio ?: return
        val prefs = PreferenceScreen.getPreferences(context)
        val signature = prefs.getString(PREF_SIGNATURE, null) ?: return
        val total = prefs.getInt(PREF_TOTAL, 0)
        val played = parseSet(prefs.getString(PREF_PLAYED, null))
        if (played.add(keyOf(audio))) {
            if (total in 1..played.size) {
                prefs.edit(true) { putString(PREF_PLAYED, "") }
            } else {
                prefs.edit(true) { putString(PREF_PLAYED, joinSet(played)) }
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
