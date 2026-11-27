package dev.ragnarok.fenrir.fragment.audio.audiosbyartist

import android.content.Context
import android.os.Bundle
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
import dev.ragnarok.fenrir.util.DownloadWorkUtils.TrackIsDownloaded
import dev.ragnarok.fenrir.util.PersistentShuffle
import dev.ragnarok.fenrir.util.PlaylistSnapshotBuffer
import dev.ragnarok.fenrir.util.Utils.getCauseIfRuntime
import dev.ragnarok.fenrir.util.coroutines.CompositeJob
import dev.ragnarok.fenrir.util.coroutines.CoroutinesUtils.fromIOToMain

class AudiosByArtistPresenter(
    accountId: Long,
    private val artist: String,
    savedInstanceState: Bundle?
) :
    AccountDependencyPresenter<IAudiosByArtistView>(accountId, savedInstanceState) {
    private val audioInteractor: IAudioInteractor = InteractorFactory.createAudioInteractor()
    private val audios: ArrayList<Audio> = ArrayList()
    private val audioListDisposable = CompositeJob()
    private var actualReceived = false
    private var loadingNow = false
    private var endOfContent = false
    @Volatile private var loadGeneration = 0L
    private var stagedBuffer: PlaylistSnapshotBuffer<Audio>? = null
    val isMyAudio: Boolean
        get() = false

    fun setLoadingNow(loadingNow: Boolean) {
        this.loadingNow = loadingNow
        resolveRefreshingView()
    }

    override fun onGuiResumed() {
        super.onGuiResumed()
        resolveRefreshingView()
    }

    private fun resolveRefreshingView() {
        resumedView?.displayRefreshing(
            loadingNow
        )
    }

    private fun requestNext() {
        if (!loadingNow) {
            fireRefresh()
        }
    }

    private fun requestListPage(generation: Long, offset: Int) {
        audioListDisposable.add(
            audioInteractor.getAudiosByArtist(
                accountId,
                artist,
                offset,
                GET_COUNT
            )
                .fromIOToMain({ page ->
                    if (generation != loadGeneration) return@fromIOToMain
                    val pending = stagedBuffer ?: return@fromIOToMain
                    try {
                        pending.addPage(offset, page)
                    } catch (error: IllegalStateException) {
                        stagedBuffer = null
                        onListGetError(error)
                        return@fromIOToMain
                    }
                    if (page.isNotEmpty()) {
                        requestListPage(generation, pending.offset)
                    } else if (offset == 0 && audios.isNotEmpty()) {
                        stagedBuffer = null
                        setLoadingNow(false)
                    } else {
                        val fresh = pending.result()
                        stagedBuffer = null
                        audios.clear()
                        audios.addAll(fresh)
                        endOfContent = true
                        actualReceived = true
                        setLoadingNow(false)
                        view?.notifyListChanged()
                    }
                }) { t ->
                    if (generation == loadGeneration) {
                        stagedBuffer = null
                        onListGetError(t)
                    }
                })
    }

    fun playAudio(context: Context, position: Int) {
        startForPlayList(context, audios, position)
        if (!Settings.get().main().isShow_mini_player) getPlayerPlace(accountId).tryOpenWith(
            context
        )
    }

    override fun onDestroyed() {
        audioListDisposable.cancel()
        super.onDestroyed()
    }

    private fun onListGetError(t: Throwable) {
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
                    if (TrackIsDownloaded(i) == 0 && i.url.nonNullNoEmpty() && !i.url!!.contains("file://") && !i.url!!.contains(
                            "content://"
                        )
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

    fun fireRefresh() {
        val generation = ++loadGeneration
        stagedBuffer = PlaylistSnapshotBuffer(PersistentShuffle::keyOf)
        setLoadingNow(true)
        requestListPage(generation, 0)
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
                    view?.customToast?.showToast(
                        R.string.success
                    )
                }) { throwable ->
                    showError(
                        throwable
                    )
                })
    }

    fun fireScrollToEnd() {
        if (actualReceived && !endOfContent) {
            requestNext()
        }
    }

    override fun onGuiCreated(viewHost: IAudiosByArtistView) {
        super.onGuiCreated(viewHost)
        viewHost.displayList(audios)
    }

    companion object {
        private const val GET_COUNT = 100
    }

    init {
        fireRefresh()
    }
}