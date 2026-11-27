package dev.ragnarok.fenrir.fragment.audio.audios

import android.content.Context
import android.os.Bundle
import android.view.View
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import dev.ragnarok.fenrir.Includes
import dev.ragnarok.fenrir.R
import dev.ragnarok.fenrir.domain.IAudioInteractor
import dev.ragnarok.fenrir.domain.InteractorFactory
import dev.ragnarok.fenrir.fragment.base.AccountDependencyPresenter
import dev.ragnarok.fenrir.media.music.MusicPlaybackController
import dev.ragnarok.fenrir.media.music.MusicPlaybackService.Companion.appendToPlayListIfCurrent
import dev.ragnarok.fenrir.media.music.MusicPlaybackService.Companion.startForPlayList
import dev.ragnarok.fenrir.media.music.MusicPlaybackService.Companion.syncPlayListIfCurrent
import dev.ragnarok.fenrir.media.music.PlaybackResumeStore
import dev.ragnarok.fenrir.model.Audio
import dev.ragnarok.fenrir.model.AudioPlaylist
import dev.ragnarok.fenrir.nonNullNoEmpty
import dev.ragnarok.fenrir.place.PlaceFactory.getPlayerPlace
import dev.ragnarok.fenrir.settings.Settings
import dev.ragnarok.fenrir.swap
import dev.ragnarok.fenrir.util.DownloadWorkUtils.TrackIsDownloaded
import dev.ragnarok.fenrir.util.FindAtWithContent
import dev.ragnarok.fenrir.util.HelperSimple
import dev.ragnarok.fenrir.util.HelperSimple.hasHelp
import dev.ragnarok.fenrir.util.MyMusicPolicy
import dev.ragnarok.fenrir.util.PersistentShuffle
import dev.ragnarok.fenrir.util.PlaybackTrace
import dev.ragnarok.fenrir.util.PlaylistSnapshotBuffer
import dev.ragnarok.fenrir.util.UnifiedPlaylist
import dev.ragnarok.fenrir.util.Utils.getCauseIfRuntime
import dev.ragnarok.fenrir.util.Utils.safeCheck
import dev.ragnarok.fenrir.util.coroutines.CancelableJob
import dev.ragnarok.fenrir.util.coroutines.CompositeJob
import dev.ragnarok.fenrir.util.coroutines.CoroutinesUtils.delayTaskFlow
import dev.ragnarok.fenrir.util.coroutines.CoroutinesUtils.fromIOToMain
import dev.ragnarok.fenrir.util.coroutines.CoroutinesUtils.hiddenIO
import dev.ragnarok.fenrir.util.coroutines.CoroutinesUtils.toMain
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.util.Locale

class AudiosPresenter(
    accountId: Long,
    private val ownerId: Long,
    albumId: Int?,
    private val accessKey: String?,
    private val iSSelectMode: Boolean,
    savedInstanceState: Bundle?
) : AccountDependencyPresenter<IAudiosView>(accountId, savedInstanceState) {
    private val audioInteractor: IAudioInteractor = InteractorFactory.createAudioInteractor()
    private val audios: ArrayList<Audio> = ArrayList()
    val playlistId: Int? = albumId
    private val audioListDisposable = CompositeJob()
    private val searcher: FindAudio = FindAudio(compositeJob)
    private var sleepDataDisposable = CancelableJob()
    private var swapDisposable = CancelableJob()
    private var actualReceived = false
    private var Curr: MutableList<AudioPlaylist>? = null
    private var loadingNow = false
    private var endOfContent = false

    // FENRIR-CI: количество полученных от VK треков (без подмешанных локальных) —
    // корректный offset для запроса следующей страницы VK.
    private var receivedVkCount = 0
    // Refresh is staged separately; neither the displayed list nor disk cache changes
    // until every page has succeeded. Generation rejects late callbacks from old refreshes.
    @Volatile private var vkRefreshGeneration = 0L
    private val snapshotWriteLock = kotlinx.coroutines.sync.Mutex()
    private var vkRefresh: PlaylistSnapshotBuffer<Audio>? = null
    private var localMergeGeneration = 0L
    private var completeVkSnapshot = false

    // FENRIR-CI: сигнатура последнего применённого набора локальных «довесков» — чтобы не
    // пересобирать хвост и не дёргать notifyListChanged на каждой из ~N страниц VK, если
    // набор офлайн-файлов не изменился (см. mergeLocalUnified).
    private var lastLocalExtrasSig: String? = null
    private var doAudioLoadTabs = false
    private var needDeadHelper: Boolean
    private fun loadedPlaylist(t: AudioPlaylist) {
        val ret: MutableList<AudioPlaylist> = ArrayList(1)
        ret.add(t)
        view?.updatePlaylists(ret)
        Curr = ret
    }

    val isMyAudio: Boolean
        get() = playlistId == null && ownerId == accountId
    val isNotSearch: Boolean
        get() = !searcher.isSearchMode

    fun setLoadingNow(loadingNow: Boolean) {
        this.loadingNow = loadingNow
        resolveRefreshingView()
    }

    override fun onGuiResumed() {
        super.onGuiResumed()
        resolveRefreshingView()
        if (audios.isEmpty()) {
            if (doAudioLoadTabs) {
                return
            }
            doAudioLoadTabs = true
            if (!iSSelectMode && playlistId == null) {
                appendJob(
                    Includes.stores.tempStore().getAudiosAll(ownerId)
                        .fromIOToMain({
                            if (it.isEmpty()) {
                                fireRefresh()
                            } else {
                                audios.addAll(it)
                                receivedVkCount = it.size
                                actualReceived = true
                                setLoadingNow(false)
                                view?.notifyListChanged()
                                mergeLocalUnified()
                                // FENRIR-CI: кэш мог содержать треки без url — резолвим их.
                                resolveMissingUrls(it)
                                // Обновляем целый снимок в фоне, не удаляя список из кэша.
                                if (isMyAudio || ownerId != accountId) {
                                    fireRefresh()
                                }
                            }
                        }, {
                            fireRefresh()
                        })
                )
            } else fireRefresh()
        } else if (isMyAudio && !iSSelectMode && !loadingNow) {
            // FENRIR-CI: возврат на экран — подмешиваем свежие локальные файлы и продолжаем
            // фоновую автодогрузку, если она не была завершена.
            mergeLocalUnified()
            if (!endOfContent) {
                requestNext()
            }
        }
    }

    private fun resolveRefreshingView() {
        resumedView?.displayRefreshing(loadingNow)
    }

    private fun requestNext() {
        if (!searcher.isSearchMode) {
            if (!loadingNow) fireRefresh()
            return
        }
        setLoadingNow(true)
        val offset = receivedVkCount
        requestList(offset, playlistId)
    }

    private fun requestList(offset: Int, album_id: Int?) {
        setLoadingNow(true)
        audioListDisposable.add(
            audioInteractor[accountId, album_id, ownerId, offset, GET_COUNT, accessKey]
                .fromIOToMain({
                    onListReceived(
                        offset,
                        it
                    )
                }) { t -> onListGetError(t) })
    }

    private fun onListReceived(offset: Int, data: List<Audio>) {
        endOfContent = data.isEmpty()
        actualReceived = true
        if (playlistId == null && !iSSelectMode) {
            appendJob(
                Includes.stores.tempStore().addAudios(ownerId, data, offset == 0)
                    .hiddenIO()
            )
        }
        if (offset == 0) {
            audios.clear()
            audios.addAll(data)
            receivedVkCount = data.size
            view?.notifyListChanged()
        } else {
            // FENRIR-CI: новые страницы VK вставляются перед локальными треками,
            // чтобы локальные всегда оставались в конце списка.
            val insertPos = audios.indexOfFirst {
                it.url?.startsWith("file://") == true || it.url?.startsWith("content://") == true
            }.takeIf { it >= 0 } ?: audios.size
            audios.addAll(insertPos, data)
            receivedVkCount += data.size
            view?.notifyDataAdded(insertPos, data.size)
        }
        // FENRIR-CI (фикс дублирования): пересчитываем локальные "довески" после
        // КАЖДОЙ страницы VK, а не только после первой — иначе трек, который на
        // момент первого вызова ещё не пришёл с сервера, но уже был скачан ранее,
        // остаётся в списке отдельной "локальной" записью и задваивается, когда
        // его "настоящая" VK-версия подгружается на одной из следующих страниц.
        mergeLocalUnified()
        if (!isMyAudio && data.isEmpty() && offset > 0 && !iSSelectMode && !searcher.isSearchMode) {
            PersistentShuffle.confirmSnapshot(Includes.provideApplicationContext(), playbackQueueId, audios)
        }
        // FENRIR-CI: догружаем реальные ссылки для треков этой страницы с пустым url —
        // чтобы отличать "ссылка ещё не получена" от "реально удалён правообладателем".
        resolveMissingUrls(data)
        setLoadingNow(false)
        if (needDeadHelper) {
            for (i in audios) {
                if (i.url.isNullOrEmpty() || i.url?.contains("audio_api_unavailable") == true) {
                    needDeadHelper = false
                    view?.showAudioDeadHelper()
                    break
                }
            }
        }
        // FENRIR-CI: автодогрузка всей «Моей музыки» в фоне — чтобы шафл и поиск
        // работали по всей библиотеке, а не по первой сотне треков.
        if (isMyAudio && !endOfContent && !iSSelectMode) {
            requestNext()
        }
    }

    // FENRIR-CI: повторный резолв треков с пустой потоковой ссылкой.
    // Массовый audio.get часто возвращает треки без url — раньше они показывались как
    // «удалённые правообладателем» (значок audio_died), хотя на VK они на месте. Догружаем
    // реальные ссылки через audio.getById (порциями). Ключевой сигнал: audio.getById НЕ
    // возвращает реально удалённые/заблокированные треки — если запрошенного трека нет в
    // ответе, помечаем его действительно недоступным (isConfirmedUnavailable).
    private fun resolveMissingUrls(data: List<Audio>) {
        if (iSSelectMode || searcher.isSearchMode) {
            return
        }
        val need = data.filter {
            it.url.isNullOrEmpty() && !it.isLocal && !it.isLocalServer && !it.isConfirmedUnavailable
        }
        if (need.isEmpty()) {
            return
        }
        for (chunk in need.chunked(RESOLVE_CHUNK)) {
            audioListDisposable.add(
                audioInteractor.getById(accountId, chunk)
                    .fromIOToMain({ resolved -> applyResolved(chunk, resolved) }) { }
            )
        }
    }

    private fun applyResolved(requested: List<Audio>, resolved: List<Audio>) {
        val byKey = HashMap<String, Audio>(resolved.size)
        for (r in resolved) {
            byKey["${r.id}_${r.ownerId}"] = r
        }
        for (i in audios.indices) {
            val a = audios[i]
            if (!a.url.isNullOrEmpty()) {
                continue
            }
            val wasRequested = requested.any { it.id == a.id && it.ownerId == a.ownerId }
            if (!wasRequested) {
                continue
            }
            val r = byKey["${a.id}_${a.ownerId}"]
            if (r == null) {
                // FENRIR-CI: audio.getById не вернул трек → он удалён/заблокирован → подтверждаем.
                a.isConfirmedUnavailable = true
                view?.notifyItemChanged(i)
            } else if (!r.url.isNullOrEmpty()) {
                a.setUrl(r.url)
                a.isConfirmedUnavailable = false
                view?.notifyItemChanged(i)
            }
            // FENRIR-CI: трек есть в ответе, но url пуст → оставляем как есть (без ложной пометки).
        }
    }

    // FENRIR-CI: единый плейлист — для «Моей музыки» подмешивает в конец списка локальные
    // треки из папок (musicDir + «Папка с локальной музыкой»), которых нет среди VK-треков;
    // для остальных плейлистов только прогревает кэш соответствий (поиск локального аналога).
    //
    // FENRIR-CI (фикс дублирования): теперь пересчитывается с нуля при каждом вызове —
    // сначала убирает предыдущий хвост "локальных" записей (url начинается с file:// /
    // content://), затем строит его заново по ПОЛНОМУ текущему списку VK-треков, а не
    // только по той странице, что была загружена на момент первого вызова. Раньше довесок
    // вычислялся один раз сразу после первой сотни VK-треков и только дописывался в конец;
    // когда фоновая автодогрузка (см. onListReceived) позже подгружала треки 101+, их
    // VK-версии вставлялись ДО уже добавленного локального довеска, и трек оставался в
    // списке дважды.
    private fun mergeLocalUnified() {
        if (iSSelectMode || searcher.isSearchMode) {
            return
        }
        if (!isMyAudio) {
            audioListDisposable.add(
                UnifiedPlaylist.warmCache(accountId).fromIOToMain({ }) { }
            )
            return
        }
        val vkOnly = audios.filterNot { it.isLocal }
        val generation = ++localMergeGeneration
        audioListDisposable.add(
            UnifiedPlaylist.appendLocalOnly(accountId, vkOnly)
                .fromIOToMain({ extras ->
                    if (generation != localMergeGeneration) return@fromIOToMain
                    // FENRIR-CI: сигнатура текущего набора офлайн-«довесков».
                    val sig = extras.joinToString("\u0001") { it.url ?: "" }
                    val firstLocalIdx = audios.indexOfFirst { it.isLocal }
                    // FENRIR-CI: набор офлайн-файлов не изменился и хвост уже на месте (либо
                    // офлайна нет вовсе) — выходим, чтобы не мигать списком и не пересобирать
                    // его на каждой странице VK. Условие самокорректирующееся: если хвост
                    // пропал (например, после fireRefresh список очистился), а extras не пуст —
                    // локальные треки будут добавлены заново.
                    if (sig == lastLocalExtrasSig && (firstLocalIdx >= 0 || extras.isEmpty())) {
                        syncUnifiedSnapshot()
                        return@fromIOToMain
                    }
                    if (firstLocalIdx >= 0) {
                        while (audios.size > firstLocalIdx) {
                            audios.removeAt(audios.size - 1)
                        }
                    }
                    if (extras.nonNullNoEmpty()) {
                        audios.addAll(extras)
                    }
                    lastLocalExtrasSig = sig
                    view?.notifyListChanged()
                    // FENRIR-CI: если «Моя музыка» уже играет — дозаписываем эти офлайн-треки
                    // в живую очередь плеера, чтобы они попали и в шафл без перезапуска.
                    syncUnifiedSnapshot()
                }) { }
        )
    }

    private fun syncUnifiedSnapshot() {
        val queueId = myAudioQueueId ?: return
        val context = Includes.provideApplicationContext()
        if (completeVkSnapshot) PersistentShuffle.confirmSnapshot(context, queueId, audios)
        if (MusicPlaybackController.isInitialized || MusicPlaybackController.isPreparing) {
            syncPlayListIfCurrent(context, audios, queueId)
        }
    }

    // FENRIR-CI: идентификатор ИСТОЧНИКА для персистентного шафла (см. PersistentShuffle).
    // «Моя музыка» и каждый отдельный VK-плейлист/альбом копят свой независимый проход
    // и не влияют друг на друга; select-mode и поиск эфемерны (null → обычный равномерный шафл).
    private val playbackQueueId: String?
        get() = when {
            iSSelectMode || searcher.isSearchMode -> null
            isMyAudio -> MyMusicPolicy.myAudioQueueId(accountId)
            playlistId != null -> "playlist_${ownerId}_$playlistId"
            else -> null
        }

    // FENRIR-CI: то же значение, что playbackQueueId для «Моей музыки» (единый источник
    // правды) — служит только гейтом живой дозаписи офлайна (только «Моя музыка»), по нему
    // сервис отличает нашу очередь от чужого запущенного плейлиста.
    private val myAudioQueueId: String?
        get() = if (isMyAudio) playbackQueueId else null

    // FENRIR-CI: живая дозапись догруженных офлайн-треков в уже играющую очередь «Моей
    // музыки». Гейты: это «Моя музыка» (не select/не поиск), есть что добавлять и
    // плеер реально играет/готовится (чтобы не поднимать сервис из фона впустую).
    // Сама привязка к нужной очереди — по queueId внутри сервиса (no-op, если играет что-то
    // другое). Порядок офлайна в списке = порядок дозаписи в конец очереди.
    private fun maybeAppendOfflineToLivePlayback(extras: List<Audio>) {
        val queueId = myAudioQueueId ?: return
        if (extras.isEmpty()) {
            return
        }
        if (!MusicPlaybackController.isPlaying && !MusicPlaybackController.isPreparing) {
            return
        }
        appendToPlayListIfCurrent(Includes.provideApplicationContext(), extras, queueId)
    }

    fun playAudio(context: Context, position: Int) {
        // FENRIR-CI: перед стартом гарантированно подмешиваем офлайн-файлы из тёплого кэша
        // сканера (синхронно, без чтения диска/ID3), чтобы они попали в очередь плеера и в
        // набор шафла, даже если фоновый mergeLocalUnified ещё не успел дописать их в список.
        // Иначе при старте до загрузки офлайна очередь (а значит и шафл) состояла только из
        // VK-треков — офлайн в случайное воспроизведение почти не попадал. Добавляем в конец,
        // поэтому переданный position (индекс в текущем списке) остаётся корректным.
        if (isMyAudio && !iSSelectMode && isNotSearch) {
            val extras = UnifiedPlaylist.cachedLocalExtras(audios)
            if (extras.nonNullNoEmpty()) {
                val startSize = audios.size
                audios.addAll(extras)
                lastLocalExtrasSig = audios.filter { it.isLocal }.joinToString("\u0001") { it.url ?: "" }
                view?.notifyDataAdded(startSize, extras.size)
            }
        }
        // FENRIR-CI: помечаем источник очереди (Моя музыка / конкретный плейлист / null для
        // поиска и select) — по нему персистентный шафл ведёт независимый проход, а живую
        // дозапись офлайна сервис разрешает только в «Мою музыку» (maybeAppendOfflineToLivePlayback).
        startForPlayList(context, audios, position, playbackQueueId)
        if (!Settings.get().main().isShow_mini_player) getPlayerPlace(accountId).tryOpenWith(
            context
        )
    }

    fun fireDelete(position: Int) {
        audios.removeAt(position)
        view?.notifyItemRemoved(position)
    }

    override fun onDestroyed() {
        audioListDisposable.cancel()
        swapDisposable.cancel()
        sleepDataDisposable.cancel()
        super.onDestroyed()
    }

    internal fun onListGetError(t: Throwable) {
        setLoadingNow(false)
        showError(getCauseIfRuntime(t))
    }

    fun fireSelectAll() {
        for (i in audios) {
            i.isSelected = true
        }
        view?.notifyListChanged()
    }

    fun getSelected(noDownloaded: Boolean): ArrayList<Audio> {
        val ret = ArrayList<Audio>()
        for (i in audios) {
            if (i.isSelected) {
                if (noDownloaded) {
                    if (TrackIsDownloaded(i) == 0 && i.url
                            .nonNullNoEmpty() && !i.url!!.contains("file://") && !i.url!!.contains("content://")
                    ) {
                        ret.add(i)
                    }
                } else {
                    ret.add(i)
                }
            }
        }
        return ret
    }

    fun getAudioPos(audio: Audio?): Int {
        if (audios.isNotEmpty() && audio != null) {
            for ((pos, i) in audios.withIndex()) {
                if (i.id == audio.id && i.ownerId == audio.ownerId) {
                    i.isAnimationNow = true
                    view?.notifyItemChanged(
                        pos
                    )
                    return pos
                }
            }
        }
        return -1
    }

    fun fireUpdateSelectMode() {
        for (i in audios) {
            if (i.isSelected) {
                i.isSelected = false
            }
        }
        view?.notifyListChanged()
    }

    private fun sleep_search(q: String?) {
        if (loadingNow) return
        sleepDataDisposable.cancel()
        if (q.isNullOrEmpty()) {
            searcher.cancel()
        } else {
            if (!searcher.isSearchMode) {
                searcher.insertCache(audios, audios.size)
            }
            sleepDataDisposable += delayTaskFlow(WEB_SEARCH_DELAY.toLong())
                .toMain { searcher.do_search(q) }
        }
    }

    fun fireSearchRequestChanged(q: String?) {
        sleep_search(q?.trim())
    }

    fun resumePermanentQueue(context: Context) {
        if (!isMyAudio || iSSelectMode) {
            return
        }
        val appContext = context.applicationContext
        val queueId = MyMusicPolicy.myAudioQueueId(accountId)
        appendJob(
            kotlinx.coroutines.flow.flow {
                val src = PlaybackResumeStore.loadSourceState(appContext, queueId)
                val saved = PlaybackResumeStore.loadSnapshot(appContext)
                emit(Pair(src, saved))
            }.fromIOToMain({ (src, saved) ->
                val targetKey = src?.shuffleKey
                    ?: if (saved?.queueId == queueId) {
                        saved.audios.getOrNull(saved.index)?.let(PersistentShuffle::keyOf)
                    } else {
                        null
                    }
                val targetPosMs = src?.positionMs
                    ?: if (saved?.queueId == queueId) saved.positionMs else 0L
                val savedQueueForMyAudio =
                    if (saved?.queueId == queueId && saved.audios.isNotEmpty()) {
                        saved.audios
                    } else {
                        null
                    }
                val targetList: ArrayList<Audio> = when {
                    audios.isNotEmpty() && (targetKey == null ||
                        audios.any { PersistentShuffle.keyOf(it) == targetKey } ||
                        savedQueueForMyAudio == null ||
                        savedQueueForMyAudio.none { PersistentShuffle.keyOf(it) == targetKey }) ->
                        ArrayList(audios)
                    savedQueueForMyAudio != null -> ArrayList(savedQueueForMyAudio)
                    else -> ArrayList()
                }
                val foundIndex = if (targetKey != null && targetList.isNotEmpty()) {
                    targetList.indexOfFirst { PersistentShuffle.keyOf(it) == targetKey }
                } else {
                    -1
                }
                val startIndex = if (foundIndex >= 0) foundIndex else 0
                val startPosMs = if (foundIndex >= 0) targetPosMs.coerceAtLeast(0L) else 0L
                if (targetList.isEmpty() &&
                    !MusicPlaybackController.isInitialized &&
                    !MusicPlaybackController.isPreparing
                ) {
                    view?.customToast?.showToast(R.string.null_audio)
                    return@fromIOToMain
                }
                startForPlayList(
                    context,
                    targetList,
                    startIndex,
                    queueId,
                    startPositionMs = startPosMs,
                    resumeIfSame = true
                )
                if (!Settings.get().main().isShow_mini_player) {
                    getPlayerPlace(accountId).tryOpenWith(context)
                }
            }) { t -> showError(getCauseIfRuntime(t)) }
        )
    }

    private fun refreshVkSnapshotPage(generation: Long, offset: Int) {
        audioListDisposable.add(
            audioInteractor[accountId, playlistId, ownerId, offset, GET_COUNT, accessKey]
                .fromIOToMain({ page ->
                    if (generation != vkRefreshGeneration) return@fromIOToMain
                    val pending = vkRefresh ?: return@fromIOToMain
                    if (isMyAudio && !iSSelectMode) {
                        PlaybackTrace.event(Includes.provideApplicationContext(), "library_page",
                            "offset" to offset, "size" to page.size)
                    }
                    try {
                        pending.addPage(offset, page)
                    } catch (error: IllegalStateException) {
                        vkRefresh = null
                        onListGetError(error)
                        return@fromIOToMain
                    }
                    if (page.isNotEmpty()) {
                        refreshVkSnapshotPage(generation, pending.offset)
                    } else if (offset == 0 && audios.any { !it.isLocal }) {
                        // A first empty page is ambiguous; don't erase a known snapshot.
                        vkRefresh = null
                        setLoadingNow(false)
                        if (isMyAudio && !iSSelectMode) {
                            PlaybackTrace.event(Includes.provideApplicationContext(), "library_empty_preserved")
                        }
                    } else {
                        val fresh = pending.result()
                        vkRefresh = null
                        localMergeGeneration++ // invalidate callbacks based on the old VK list
                        // Keep the previous offline extras visible until a fresh scan succeeds.
                        // Exclude recordings already represented in the new VK snapshot.
                        val freshKeys = fresh.mapTo(HashSet(), PersistentShuffle::keyOf)
                        val previousExtras = if (isMyAudio && !iSSelectMode) {
                            audios.filter { it.isLocal && freshKeys.add(PersistentShuffle.keyOf(it)) }
                        } else {
                            emptyList()
                        }
                        audios.clear()
                        audios.addAll(fresh)
                        audios.addAll(previousExtras)
                        receivedVkCount = fresh.size
                        endOfContent = true
                        completeVkSnapshot = true
                        actualReceived = true
                        view?.notifyListChanged()
                        if (playlistId == null && !iSSelectMode) {
                            // One transaction replaces the prior complete snapshot only now.
                            appendJob(kotlinx.coroutines.flow.flow {
                                snapshotWriteLock.lock()
                                try {
                                    if (generation == vkRefreshGeneration) {
                                        Includes.stores.tempStore().addAudios(ownerId, fresh, true).first()
                                    }
                                } finally {
                                    snapshotWriteLock.unlock()
                                }
                                emit(true)
                            }.hiddenIO())
                        }
                        if (isMyAudio && !iSSelectMode) {
                            PlaybackTrace.event(Includes.provideApplicationContext(), "library_committed", "size" to fresh.size)
                        } else if (!iSSelectMode) {
                            PersistentShuffle.confirmSnapshot(
                                Includes.provideApplicationContext(),
                                playbackQueueId,
                                audios
                            )
                        }
                        if (needDeadHelper) {
                            for (i in fresh) {
                                if (i.url.isNullOrEmpty() || i.url?.contains("audio_api_unavailable") == true) {
                                    needDeadHelper = false
                                    view?.showAudioDeadHelper()
                                    break
                                }
                            }
                        }
                        resolveMissingUrls(fresh)
                        mergeLocalUnified()
                        setLoadingNow(false)
                    }
                }) { error ->
                    if (generation == vkRefreshGeneration) {
                        vkRefresh = null
                        if (isMyAudio && !iSSelectMode) {
                            PlaybackTrace.event(Includes.provideApplicationContext(), "library_refresh_error", "offset" to offset,
                                "error" to error.javaClass.simpleName)
                        }
                        onListGetError(error)
                        if (audios.isEmpty()) mergeLocalUnified()
                    }
                }
        )
    }

    fun fireRefresh() {
        receivedVkCount = 0
        if (searcher.isSearchMode) {
            searcher.reset()
        } else {
            if (playlistId != null && playlistId != 0) {
                audioListDisposable.add(
                    audioInteractor.getPlaylistById(
                        accountId,
                        playlistId,
                        ownerId,
                        accessKey
                    )
                        .fromIOToMain({ t -> loadedPlaylist(t) }) { t ->
                            showError(
                                getCauseIfRuntime(t)
                            )
                        })
            }
            val generation = ++vkRefreshGeneration
            vkRefresh = PlaylistSnapshotBuffer(PersistentShuffle::keyOf)
            setLoadingNow(true)
            refreshVkSnapshotPage(generation, 0)
        }
    }

    fun onDelete(album: AudioPlaylist) {
        audioListDisposable.add(
            audioInteractor.deletePlaylist(
                accountId,
                album.id,
                album.owner_id
            )
                .fromIOToMain({
                    view?.customToast?.showToast(
                        R.string.success
                    )
                }) { throwable ->
                    showError(throwable)
                })
    }

    fun onAdd(album: AudioPlaylist) {
        audioListDisposable.add(
            audioInteractor.followPlaylist(
                accountId,
                album.id,
                album.owner_id,
                album.access_key
            )
                .fromIOToMain({
                    view?.customToast?.showToast(R.string.success)
                }) { throwable ->
                    showError(throwable)
                })
    }

    fun fireScrollToEnd() {
        if (audios.nonNullNoEmpty() && !loadingNow && actualReceived) {
            if (searcher.isSearchMode) {
                searcher.do_search()
            } else if (!endOfContent) {
                requestNext()
            }
        }
    }

    fun fireEditTrack(context: Context, audio: Audio) {
        val root = View.inflate(context, R.layout.entry_audio_info, null)
        root.findViewById<TextInputEditText>(R.id.edit_artist).setText(audio.artist)
        root.findViewById<TextInputEditText>(R.id.edit_title).setText(audio.title)
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.enter_audio_info)
            .setCancelable(true)
            .setView(root)
            .setPositiveButton(R.string.button_ok) { _, _ ->
                audioListDisposable.add(
                    audioInteractor.edit(
                        accountId,
                        audio.ownerId,
                        audio.id,
                        root.findViewById<TextInputEditText>(R.id.edit_artist).text.toString(),
                        root.findViewById<TextInputEditText>(R.id.edit_title).text.toString()
                    ).fromIOToMain({ fireRefresh() }) { t ->
                        showError(getCauseIfRuntime(t))
                    })
            }
            .setNegativeButton(R.string.button_cancel, null)
            .show()
    }

    private fun tempSwap(fromPosition: Int, toPosition: Int) {
        if (fromPosition < toPosition) {
            for (i in fromPosition until toPosition) {
                audios.swap(i, i + 1)
            }
        } else {
            for (i in fromPosition downTo toPosition + 1) {
                audios.swap(i, i - 1)
            }
        }
        view?.notifyItemMoved(
            fromPosition,
            toPosition
        )
    }

    fun fireItemMoved(fromPosition: Int, toPosition: Int): Boolean {
        if (audios.size < 2) {
            return false
        }
        val audio_from = audios[fromPosition]
        if (fromPosition < toPosition) {
            for (i in fromPosition until toPosition) {
                audios.swap(i, i + 1)
            }
        } else {
            for (i in fromPosition downTo toPosition + 1) {
                audios.swap(i, i - 1)
            }
        }
        view?.notifyItemMoved(
            fromPosition,
            toPosition
        )
        var before: Int? = null
        var after: Int? = null
        if (toPosition == 0) {
            before = audios[1].id
        } else {
            after = audios[toPosition - 1].id
        }
        swapDisposable.cancel()
        swapDisposable += audioInteractor.reorder(accountId, ownerId, audio_from.id, before, after)
            .fromIOToMain { tempSwap(toPosition, fromPosition) }
        return true
    }

    override fun onGuiCreated(viewHost: IAudiosView) {
        super.onGuiCreated(viewHost)
        viewHost.displayList(audios)
        Curr?.let {
            viewHost.updatePlaylists(it)
        }
    }

    private inner class FindAudio(disposable: CompositeJob) : FindAtWithContent<Audio>(
        disposable, SEARCH_VIEW_COUNT, SEARCH_COUNT
    ) {
        override fun search(offset: Int, count: Int): Flow<List<Audio>> {
            return audioInteractor[accountId, playlistId, ownerId, offset, count, accessKey]
        }

        override fun onError(e: Throwable) {
            onListGetError(e)
        }

        override fun onResult(data: MutableList<Audio>) {
            actualReceived = true
            val startSize = audios.size
            audios.addAll(data)
            view?.notifyDataAdded(
                startSize,
                data.size
            )
        }

        override fun updateLoading(loading: Boolean) {
            setLoadingNow(loading)
        }

        override fun clean() {
            audios.clear()
            view?.notifyListChanged()
        }

        private fun checkArtists(data: Map<String, String>?, q: String): Boolean {
            if (data.isNullOrEmpty()) {
                return false
            }
            for (i in data.values) {
                if (i.lowercase(Locale.getDefault())
                        .contains(q.lowercase(Locale.getDefault()))
                ) {
                    return true
                }
            }
            return false
        }

        private fun checkTitleArtists(data: Audio, q: String): Boolean {
            val r = q.split(Regex("( - )|( )|( {2})"), 2).toTypedArray()
            return if (r.size >= 2) {
                (safeCheck(
                    data.artist
                ) {
                    data.artist?.lowercase(Locale.getDefault())?.contains(
                        r[0].lowercase(
                            Locale.getDefault()
                        )
                    ) == true
                }
                        || checkArtists(
                    data.main_artists,
                    r[0]
                )) && safeCheck(
                    data.title
                ) {
                    data.title?.lowercase(Locale.getDefault())?.contains(
                        r[1].lowercase(
                            Locale.getDefault()
                        )
                    ) == true
                }
            } else false
        }

        override fun compare(data: Audio, q: String): Boolean {
            return if (q == "dw") {
                TrackIsDownloaded(data) == 0
            } else safeCheck(
                data.title
            ) {
                data.title?.lowercase(Locale.getDefault())
                    ?.contains(q.lowercase(Locale.getDefault())) == true
            }
                    || safeCheck(
                data.artist
            ) {
                data.artist?.lowercase(Locale.getDefault())?.contains(
                    q.lowercase(
                        Locale.getDefault()
                    )
                ) == true
            }
                    || checkArtists(data.main_artists, q) || checkTitleArtists(data, q)
        }

        override fun onReset(data: MutableList<Audio>, offset: Int, isEnd: Boolean) {
            if (data.isEmpty()) {
                fireRefresh()
            } else {
                endOfContent = isEnd
                audios.clear()
                audios.addAll(data)
                if (playlistId == null && !iSSelectMode) {
                    appendJob(
                        Includes.stores.tempStore().addAudios(ownerId, data, true)
                            .hiddenIO()
                    )
                }
                view?.notifyListChanged()
            }
        }
    }

    companion object {
        private const val GET_COUNT = 100
        private const val SEARCH_COUNT = 1000
        private const val SEARCH_VIEW_COUNT = 20
        private const val WEB_SEARCH_DELAY = 1000

        // FENRIR-CI: размер порции для повторного резолва ссылок (audio.getById).
        private const val RESOLVE_CHUNK = 100
    }

    init {
        needDeadHelper = hasHelp(HelperSimple.AUDIO_DEAD, 1)
    }
}
