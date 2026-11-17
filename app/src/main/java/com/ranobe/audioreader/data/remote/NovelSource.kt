package com.ranobe.audioreader.data.remote

enum class NovelStatus { UNKNOWN, ONGOING, COMPLETED, ON_HIATUS, CANCELLED }

data class NovelItem(
    val path: String,
    val name: String,
    val coverUrl: String? = null,
)

data class ChapterItem(
    val path: String,
    val name: String,
    val chapterNumber: Float? = null,
    val releaseTime: String? = null,
    val scanlator: String? = null,
)

data class SourceNovel(
    val path: String,
    val name: String,
    val coverUrl: String? = null,
    val author: String? = null,
    val artist: String? = null,
    val genres: String? = null,
    val summary: String? = null,
    val status: NovelStatus = NovelStatus.UNKNOWN,
    val rating: Float? = null,
    val chapters: List<ChapterItem> = emptyList(),
)

data class BrowseOptions(
    val latest: Boolean = false,
    val sort: String? = null,
    val filters: Map<String, List<String>> = emptyMap(),
)

interface NovelSource {
    val id: String
    val name: String
    val baseUrl: String
    val requiresAuth: Boolean

    suspend fun popularNovels(page: Int, options: BrowseOptions = BrowseOptions()): List<NovelItem>
    suspend fun searchNovels(query: String, page: Int = 1): List<NovelItem>
    suspend fun novelDetails(novelPath: String): SourceNovel
    suspend fun chapterContent(chapterPath: String): String
    fun webUrl(novelPath: String, chapterPath: String? = null): String
}
