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
                        val startPos = audios.size
                        audios.addAll(extras)
                        view?.notifyDataAdded(startPos, extras.size)
                    }
                }) { }
        )
    }

    fun playAudio(context: Context, position: Int) {
        startForPlayList(context, audios, position)
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
            requestList(0, playlistId)
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
                audios.swap(i, i - 