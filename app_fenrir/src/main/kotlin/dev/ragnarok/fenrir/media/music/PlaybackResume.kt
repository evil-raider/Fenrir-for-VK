package dev.ragnarok.fenrir.media.music

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.edit
import dev.ragnarok.fenrir.domain.IAudioInteractor
import dev.ragnarok.fenrir.domain.InteractorFactory
import dev.ragnarok.fenrir.kJson
import dev.ragnarok.fenrir.model.Audio
import dev.ragnarok.fenrir.settings.Settings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.util.concurrent.Executors

/**
 * FENRIR-CI: «возобновление по кнопке гарнитуры».
 *
 * Сохраняет текущую очередь плеера (в порядке media item'ов ExoPlayer) и позицию,
 * чтобы MusicPlaybackService мог поднять её в onPlaybackResumption, когда сервиса уже нет
 * (плеер закрыт в шторке, процесс выгружен, телефон перезагружен), а пользователь нажал
 * Play на Bluetooth-гарнитуре. Очередь — JSON-файл в filesDir (может быть тысячи треков,
 * пишется в фоне с коалесингом), индекс/позиция — в SharedPreferences (дёшево, часто).
 */
internal object PlaybackResumeStore {
    private const val TAG = "PlaybackResumeStore"
    private const val QUEUE_FILE = "player_resume_queue.json"
    private const val PREFS_NAME = "player_resume_state"
    private const val KEY_HAS_QUEUE = "has_queue"
    private const val KEY_QUEUE_ID = "queue_id"
    private const val KEY_INDEX = "index"
    private const val KEY_POSITION = "position"
    private const val KEY_AUDIO_KEY = "audio_key"

    private val io = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val queueSerializer = ListSerializer(Audio.serializer())

    // Коалесинг: если очередь поменялась ещё раз до того, как фон дошёл до записи, —
    // старую запись пропускаем.
    @Volatile
    private var queueGeneration = 0L

    class Snapshot(
        val audios: List<Audio>,
        val index: Int,
        val positionMs: Long,
        val queueId: String?
    )

    fun key(audio: Audio): String =
        if (audio.isLocal || audio.isLocalServer) "l:${audio.url}" else "${audio.ownerId}_${audio.id}"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun queueFile(context: Context) = File(context.filesDir, QUEUE_FILE)

    /** Вызывать с главного потока. Пустую очередь не пишем — снимок не затираем. */
    fun saveQueue(context: Context, audios: List<Audio>, queueId: String?) {
        if (audios.isEmpty()) {
            return
        }
        val app = context.applicationContext
        val copy = ArrayList(audios)
        val generation = ++queueGeneration
        io.execute {
            if (generation != queueGeneration) {
                return@execute
            }
            try {
                val json = kJson.encodeToString(queueSerializer, copy)
                val target = queueFile(app)
                val tmp = File(app.filesDir, "$QUEUE_FILE.tmp")
                tmp.writeText(json)
                if (!tmp.renameTo(target)) {
                    target.delete()
                    tmp.renameTo(target)
                }
                prefs(app).edit {
                    putBoolean(KEY_HAS_QUEUE, true)
                    putString(KEY_QUEUE_ID, queueId)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "saveQueue failed: ${e.message}")
            }
        }
    }

    /** Дёшево, можно звать часто (смена трека, пауза, уничтожение сервиса). */
    fun saveState(context: Context, index: Int, positionMs: Long, audio: Audio?) {
        if (index < 0 || audio == null) {
            return
        }
        prefs(context.applicationContext).edit {
            putInt(KEY_INDEX, index)
            putLong(KEY_POSITION, positionMs.coerceAtLeast(0))
            putString(KEY_AUDIO_KEY, key(audio))
        }
    }

    /** Читает снимок в фоне (после всех ожидающих записей), результат — на главном потоке. */
    fun loadAsync(context: Context, callback: (Snapshot?) -> Unit) {
        val app = context.applicationContext
        io.execute {
            val snapshot = try {
                load(app)
            } catch (e: Throwable) {
                Log.e(TAG, "load failed: ${e.message}")
                null
            }
            mainHandler.post { callback(snapshot) }
        }
    }

    private fun load(context: Context): Snapshot? {
        val p = prefs(context)
        if (!p.getBoolean(KEY_HAS_QUEUE, false)) {
            return null
        }
        val file = queueFile(context)
        if (!file.exists()) {
            return null
        }
        val audios = kJson.decodeFromString(queueSerializer, file.readText())
        if (audios.isEmpty()) {
            return null
        }
        var index = p.getInt(KEY_INDEX, 0)
        var position = p.getLong(KEY_POSITION, 0)
        val audioKey = p.getString(KEY_AUDIO_KEY, null)
        // Индекс пишется синхронно, а очередь — в фоне: сверяемся по ключу трека.
        if (index !in audios.indices || (audioKey != null && key(audios[index]) != audioKey)) {
            val found = if (audioKey != null) audios.indexOfFirst { key(it) == audioKey } else -1
            if (found >= 0) {
                index = found
            } else {
                index = 0
                position = 0
            }
        }
        return Snapshot(audios, index, position.coerceAtLeast(0), p.getString(KEY_QUEUE_ID, null))
    }
}

/**
 * FENRIR-CI: ленивое обновление протухших VK-ссылок в восстановленной очереди.
 *
 * Ссылки на аудио VK живут ограниченное время, а восстановленная с диска очередь могла
 * пролежать сутки. Вместо того чтобы перед стартом перезапрашивать тысячи треков,
 * ссылка обновляется в момент загрузки трека (ResolvingDataSource на потоке загрузчика
 * ExoPlayer), пачкой по [BATCH] следующих по очереди треков — один запрос audio.getById
 * на ~50 треков. Каждый трек обновляется не более одного раза.
 */
internal object RestoredUrlRefresher {
    private const val TAG = "RestoredUrlRefresher"
    private const val BATCH = 50
    private const val TIMEOUT_MS = 10_000L
    private const val PLACEHOLDER_PREFIX = "https://fenrir-restore.invalid/"

    private val stateLock = Any()
    private val networkLock = Any()
    private var order: List<Audio> = emptyList()
    private val stale = HashSet<String>()
    private val rebuildCandidates = HashSet<String>()
    private val audioInteractor: IAudioInteractor by lazy { InteractorFactory.createAudioInteractor() }

    private fun needsRefresh(audio: Audio): Boolean {
        if (audio.isLocal || audio.isLocalServer) {
            return false
        }
        val url = audio.url
        return url.isNullOrEmpty() || url.startsWith("http")
    }

    fun reset(audios: List<Audio>) {
        synchronized(stateLock) {
            order = audios
            stale.clear()
            rebuildCandidates.clear()
            for (a in audios) {
                if (needsRefresh(a)) {
                    val k = PlaybackResumeStore.key(a)
                    stale.add(k)
                    rebuildCandidates.add(k)
                }
            }
        }
    }

    fun clear() {
        synchronized(stateLock) {
            order = emptyList()
            stale.clear()
            rebuildCandidates.clear()
        }
    }

    fun isStale(audio: Audio): Boolean =
        synchronized(stateLock) { stale.contains(PlaybackResumeStore.key(audio)) }

    /** true — один раз на трек: можно пересобрать media item после ошибки воспроизведения. */
    fun takeRebuildCandidate(audio: Audio): Boolean =
        synchronized(stateLock) { rebuildCandidates.remove(PlaybackResumeStore.key(audio)) }

    fun placeholderUri(audio: Audio): String = "$PLACEHOLDER_PREFIX${audio.ownerId}_${audio.id}"

    fun isPlaceholder(uri: String): Boolean = uri.startsWith(PLACEHOLDER_PREFIX)

    /** Блокирующий вызов — только с фонового потока (загрузчик ExoPlayer). */
    fun refreshBlocking(target: Audio) {
        synchronized(networkLock) {
            val batch = ArrayList<Audio>(BATCH)
            synchronized(stateLock) {
                val targetKey = PlaybackResumeStore.key(target)
                if (!stale.contains(targetKey)) {
                    return
                }
                batch.add(target)
                val start = order.indexOfFirst { PlaybackResumeStore.key(it) == targetKey }
                if (start >= 0) {
                    var step = 1
                    while (batch.size < BATCH && step < order.size) {
                        val candidate = order[(start + step) % order.size]
                        val k = PlaybackResumeStore.key(candidate)
                        if (k != targetKey && stale.contains(k)) {
                            batch.add(candidate)
                        }
                        step++
                    }
                }
            }
            try {
                val accountId = Settings.get().accounts().current
                val fresh = runBlocking {
                    withTimeoutOrNull(TIMEOUT_MS) {
                        audioInteractor.getByIdOld(
                            accountId,
                            batch,
                            Settings.get().main().isUse_api_5_90_for_audio
                        ).first()
                    }
                }
                if (fresh != null) {
                    for (r in fresh) {
                        val url = r.url
                        if (url.isNullOrEmpty()) {
                            continue
                        }
                        batch.firstOrNull { it.id == r.id && it.ownerId == r.ownerId }?.setUrl(url)
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "refresh failed: ${e.message}")
            } finally {
                // Даже при ошибке сети больше не пытаемся: иначе офлайн каждый трек ждал бы таймаут.
                synchronized(stateLock) {
                    for (a in batch) {
                        stale.remove(PlaybackResumeStore.key(a))
                    }
                }
            }
        }
    }

    /** Есть ли у трека пригодная свежая ссылка (для подмены в ResolvingDataSource). */
    fun freshUrl(audio: Audio): String? {
        val url = audio.url
        if (url.isNullOrEmpty() || url.contains("audio_api_unavailable") || !url.startsWith("http")) {
            return null
        }
        return url
    }
}
