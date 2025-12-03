package eu.kanade.tachiyomi.extension.all.netraragi

import kotlinx.serialization.Serializable

@Serializable
data class Gallery(
    val id: String,
    val tags: Array<String>?,
    val title: String,
    val pages: Int?,
    val dateAdded: String,
    val pageCount: Int?,
    val thumbnailPath: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Gallery

        if (pages != other.pages) return false
        if (pageCount != other.pageCount) return false
        if (id != other.id) return false
        if (!tags.contentEquals(other.tags)) return false
        if (title != other.title) return false
        if (dateAdded != other.dateAdded) return false
        if (thumbnailPath != other.thumbnailPath) return false

        return true
    }

    override fun hashCode(): Int {
        var result = pages ?: 0
        result = 31 * result + (pageCount ?: 0)
        result = 31 * result + id.hashCode()
        result = 31 * result + (tags?.contentHashCode() ?: 0)
        result = 31 * result + title.hashCode()
        result = 31 * result + dateAdded.hashCode()
        result = 31 * result + thumbnailPath.hashCode()
        return result
    }
}

@Serializable
data class ArchivePage(
    val pages: List<String>,
)

@Serializable
data class ArchiveSearchResult(
    val galleryItems: List<Gallery>,
    val count: Int,
)

@Serializable
data class Category(
    val id: String,
    val name: String,
    val pinned: String,
)
