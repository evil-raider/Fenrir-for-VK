package dev.ragnarok.fenrir.model
@kotlinx.serialization.Serializable
data class Audio(val id: Int = 0, val ownerId: Long = 1, val isLocal: Boolean = false,
    val artist: String? = null, val title: String? = null, val isLocalServer: Boolean = false, val url: String? = null)
