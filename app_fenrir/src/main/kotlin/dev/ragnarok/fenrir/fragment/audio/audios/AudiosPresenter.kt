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
import dev.ragnarok.fenrir.media.music.MusicPlaybackService.Companion.startForPlayList
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
    private var receivedVkCount = 0
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
                                if (isMyAudio) {
                                    requestList(receivedVkCount, playlistId)
                                }
                            }
                        }, {
                            fireRefresh()
                        })
                )
            } else fireRefresh()
        } else if (isMyAudio && !iSSelectMode && !loadingNow) {
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
            mergeLocalUnified()
        } else {
            val insertPos = audios.indexOfFirst {
                it.url?.startsWith("file://") == true || it.url?.startsWith("content://") == true
            }.takeIf { it >= 0 } ?: audios.size
            audios.addAll(insertPos, data)
            receivedVkCount += data.size
            view?.notifyDataAdded(insertPos, data.size)
        }
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
        if (isMyAudio && !endOfContent && !iSSelectMode) {
            requestNext()
        }
    }

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
        audioListDisposable.add(
            UnifiedPlaylist.appendLocalOnly(accountId, audios)
                .fromIOToMain({ extras ->
                    if (extras.nonNullNoEmpty()) {
                        val