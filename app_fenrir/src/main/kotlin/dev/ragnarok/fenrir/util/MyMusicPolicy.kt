package dev.ragnarok.fenrir.util

import java.io.File
import java.net.URI

/**
 * FENRIR-CI: правила принадлежности к списку «Моя музыка+оффлайн».
 *
 * «Папка с загруженной музыкой» (musicDir) — это локальная копия онлайн-«Моей музыки»
 * VK. Туда не должны попадать треки не из списка (найденные в поиске, из чужих
 * плейлистов, рекомендаций), и файлы оттуда не должны становиться самостоятельными
 * «резидентами» единого списка:
 *  - трек из «Моей музыки», у которого есть копия в musicDir, уже присутствует в списке
 *    своей VK-записью и играет из локального файла (приоритет локальной копии);
 *  - файл в musicDir без VK-записи в «Моей музыке» — значит, он не из списка.
 * Поэтому в хвост единого списка подмешиваются только файлы пользовательской
 * «Папки с локальной музыкой» (localAudioFolderA), а файлы musicDir — никогда.
 *
 * Без Android-зависимостей: проверяется JVM-тестами ci/test-playback.py.
 */
object MyMusicPolicy {

    /** Идентификатор источника очереди «Моя музыка» (тот же, что у персистентного шафла). */
    fun myAudioQueueId(accountId: Long): String = "myaudio_$accountId"

    /**
     * Прозрачное кэширование — только для треков очереди, запущенной из «Моей музыки».
     * Поиск, чужие плейлисты, артисты, рекомендации и т. п. в musicDir не кэшируются.
     */
    fun shouldTransparentCache(queueId: String?, accountId: Long): Boolean =
        queueId != null && queueId == myAudioQueueId(accountId)

    /**
     * Ручной выбор трека в очереди при включённом персистентном шафле: порядок
     * перестраивается от выбранного трека, чтобы дальше шли только ещё не звучавшие.
     * Для эфемерных очередей (queueId == null) поведение Media3 не меняем.
     */
    fun reanchorOnExplicitSelection(shuffleEnabled: Boolean, queueId: String?): Boolean =
        shuffleEnabled && !queueId.isNullOrEmpty()

    /**
     * Можно ли подмешать локальный трек с данным url в хвост «Моей музыки+оффлайн».
     * true — файл из пользовательской папки (или вне musicDir); false — файл из musicDir.
     * Пользовательская папка проверяется первой: если она вложена в musicDir, её файлы
     * остаются в списке.
     */
    fun keepAsUnifiedExtra(url: String?, musicDir: String?, userFolder: String?): Boolean {
        val path = filePath(url) ?: return true
        if (!userFolder.isNullOrEmpty() && isInside(path, userFolder)) {
            return true
        }
        if (!musicDir.isNullOrEmpty() && isInside(path, musicDir)) {
            return false
        }
        return true
    }

    /** Путь файла из file://-url (с декодированием %XX), иначе null. */
    fun filePath(url: String?): String? {
        if (url.isNullOrEmpty() || !url.startsWith("file:")) {
            return null
        }
        return try {
            URI(url).path
        } catch (_: Exception) {
            null
        } ?: url.removePrefix("file://")
    }

    fun isInside(path: String, folder: String): Boolean {
        if (folder.isEmpty()) {
            return false
        }
        if (isInsideNormalized(normalize(path), normalize(folder))) {
            return true
        }
        // /sdcard vs /storage/emulated/0 и подобные симлинки.
        return try {
            isInsideNormalized(File(path).canonicalPath, File(folder).canonicalPath)
        } catch (_: Exception) {
            false
        }
    }

    private fun normalize(p: String): String = File(p).absoluteFile.normalize().path

    private fun isInsideNormalized(path: String, folder: String): Boolean {
        val f = folder.trimEnd(File.separatorChar)
        if (f.isEmpty()) {
            return true
        }
        return path == f || path.startsWith(f + File.separatorChar)
    }
}
