package com.lagradost.cloudstream3.utils

// TODO: FIX

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.APIHolder.apis
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.syncproviders.SyncAPI
import com.lagradost.cloudstream3.ui.SyncWatchType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.concurrent.TimeUnit

object SyncUtil {
    private val regexs = listOf(
        Regex("""(9anime)\.(?:to|center|id)/watch/.*?\.([^/?]*)"""),
        Regex("""(gogoanime|gogoanimes)\..*?/category/([^/?]*)"""),
        Regex("""(twist\.moe)/a/([^/?]*)"""),
    )

    private const val TAG = "SYNCUTIL"

    fun cleanTitle(title: String): String {
        return title
            .replace(Regex("""(?i)\s*[\(\[](?:Dub(?:bed)?|Sub(?:bed)?|Dual[\s-]?Audio|Uncensored|Censored|Multi|ENG|JAP|RAW)[^\)\]]*[\)\]]"""), "")
            .replace(Regex("""(?i)\s*[\(\[](?:4K|1080p|720p|HDR|HD)[^\)\]]*[\)\]]"""), "")
            .replace(Regex("""\s*\(\d{4}\)"""), "")
            .replace(Regex("""(?i)\s*-\s*(?:Dub(?:bed)?|Sub(?:bed)?|Dual[\s-]?Audio)"""), "")
            .trim()
    }

    fun stripSeason(title: String): String {
        return title
            .replace(Regex("""(?i)\s*(?:Season\s*\d+|S\d+|\d+(?:st|nd|rd|th)\s*Season|Part\s*\d+|Cour\s*\d+).*"""), "")
            .trim()
    }

    suspend fun syncStatus(
        status: SyncWatchType,
        title: String?,
        syncData: Map<String, String>? = null,
        extraSyncs: Map<String, String>? = null
    ): Boolean {
        Log.i(TAG, "syncStatus: status=$status, title=$title, syncData=$syncData")
        var anyUpdated = false

        for (repo in AccountManager.syncApis) {
            try {
                if (repo.authUser() == null) continue
                val prefix = repo.idPrefix

                // 1. Direct ID resolution
                var targetId: String? = extraSyncs?.get(prefix) ?: syncData?.get(prefix) ?: syncData?.get("${prefix}_id")
                if (targetId == null && prefix == AccountManager.simklApi.idPrefix) {
                    targetId = extraSyncs?.get("imdb") ?: syncData?.get("imdb") ?: syncData?.get("imdb_id")
                    if (targetId == null) {
                        val tmdbId = extraSyncs?.get("tmdb") ?: syncData?.get("tmdb") ?: syncData?.get("tmdb_id")
                        if (tmdbId != null) {
                            targetId = "tmdb:$tmdbId"
                        }
                    }
                }
                if (targetId == null && prefix == AccountManager.malApi.idPrefix) {
                    targetId = extraSyncs?.get("mal") ?: syncData?.get("mal") ?: syncData?.get("mal_id")
                }
                if (targetId == null && prefix == AccountManager.aniListApi.idPrefix) {
                    targetId = extraSyncs?.get("anilist") ?: syncData?.get("anilist") ?: syncData?.get("anilist_id")
                }

                // 2. Search fallback if targetId is still missing and title is provided
                if (targetId.isNullOrBlank() && !title.isNullOrBlank()) {
                    val cleaned = cleanTitle(title)
                    var match = repo.search(cleaned).getOrNull()?.firstOrNull()
                    if (match == null && cleaned != title) {
                        match = repo.search(title).getOrNull()?.firstOrNull()
                    }
                    if (match == null) {
                        val base = stripSeason(cleaned)
                        if (base != cleaned && base.isNotBlank()) {
                            match = repo.search(base).getOrNull()?.firstOrNull()
                        }
                    }
                    targetId = match?.syncId
                }

                if (!targetId.isNullOrBlank()) {
                    val currentStatus = repo.status(targetId).getOrNull()
                    val toSend: SyncAPI.AbstractSyncStatus = if (currentStatus != null) {
                        currentStatus.status = status
                        currentStatus
                    } else {
                        SyncAPI.SyncStatus(
                            status = status,
                            score = null,
                            watchedEpisodes = null,
                            isFavorite = null,
                            maxEpisodes = null
                        )
                    }
                    Log.i(TAG, "syncStatus updating ${repo.name} ($targetId) with $status")
                    val success = repo.updateStatus(targetId, toSend).getOrDefault(false)
                    if (success) anyUpdated = true
                }
            } catch (t: Throwable) {
                logError(t)
            }
        }
        return anyUpdated
    }

    private const val GOGOANIME = "Gogoanime"
    private const val NINE_ANIME = "9anime"
    private const val TWIST_MOE = "Twistmoe"

    private val matchList = mapOf(
        "9anime" to NINE_ANIME,
        "gogoanime" to GOGOANIME,
        "gogoanimes" to GOGOANIME,
        "twist.moe" to TWIST_MOE,
    )

    suspend fun getIdsFromUrl(url: String?): Pair<String?, String?>? {
        if (url == null) return null
        Log.i(TAG, "getIdsFromUrl $url")
        for (regex in regexs) {
            regex.find(url)?.let { match ->
                if (match.groupValues.size == 3) {
                    val site = match.groupValues[1]
                    val slug = match.groupValues[2]
                    matchList[site]?.let { realSite ->
                        getIdsFromSlug(slug, realSite)?.let {
                            return it
                        } ?: kotlin.run {
                            if (slug.endsWith("-dub")) {
                                println("testing non -dub slug $slug")
                                getIdsFromSlug(slug.removeSuffix("-dub"), realSite)?.let {
                                    return it
                                }
                            }
                        }
                    }
                }
            }
        }

        return null
    }

    /**
     * first. Mal, second. Anilist,
     * Valid sites are: Gogoanime, Twistmoe and 9anime
     */
    private suspend fun getIdsFromSlug(
        slug: String,
        site: String = "Gogoanime",
    ): Pair<String?, String?>? {
        Log.i(TAG, "getIdsFromSlug $slug $site")
        try {
            // Gogoanime, Twistmoe and 9anime
            val url = "https://raw.githubusercontent.com/MALSync/MAL-Sync-Backup/master/data/pages/$site/$slug.json"
            val response = app.get(url, cacheTime = 1, cacheUnit = TimeUnit.DAYS).text
            val mapped = tryParseJson<MalSyncPage>(response)

            val overrideMal = mapped?.malId ?: mapped?.mal?.id ?: mapped?.anilist?.malId
            val overrideAnilist = mapped?.aniId ?: mapped?.anilist?.id
            if (overrideMal != null) {
                return overrideMal.toString() to overrideAnilist?.toString()
            }

            return null
        } catch (e: Exception) {
            logError(e)
        }

        return null
    }

    suspend fun getUrlsFromId(id: String, type: String = "anilist"): List<String> {
        val url = "https://raw.githubusercontent.com/MALSync/MAL-Sync-Backup/master/data/$type/anime/$id.json"
        val response = app.get(url, cacheTime = 1, cacheUnit = TimeUnit.DAYS).parsed<SyncPage>()
        val pages = response.pages ?: return emptyList()
        val current = pages.gogoanime.values.union(pages.nineanime.values).union(pages.twistmoe.values)
            .mapNotNull { it.url }.toMutableList()

        if (type == "anilist") { // TODO MAKE BETTER
            apis.filter { it.name.contains("Aniflix", ignoreCase = true) }.forEach {
                current.add("${it.mainUrl}/anime/$id")
            }
        }

        return current
    }

    @Serializable
    data class SyncPage(
        @JsonProperty("Pages") @SerialName("Pages") val pages: SyncPages?,
    )

    @Serializable
    data class SyncPages(
        @JsonProperty("9anime") @SerialName("9anime") val nineanime: Map<String, ProviderPage> = emptyMap(),
        @JsonProperty("Gogoanime") @SerialName("Gogoanime") val gogoanime: Map<String, ProviderPage> = emptyMap(),
        @JsonProperty("Twistmoe") @SerialName("Twistmoe") val twistmoe: Map<String, ProviderPage> = emptyMap(),
    )

    @Serializable
    data class ProviderPage(
        @JsonProperty("url") @SerialName("url") val url: String?,
    )

    @Serializable
    data class MalSyncPage(
        @JsonProperty("identifier") @SerialName("identifier") val identifier: String?,
        @JsonProperty("type") @SerialName("type") val type: String?,
        @JsonProperty("page") @SerialName("page") val page: String?,
        @JsonProperty("title") @SerialName("title") val title: String?,
        @JsonProperty("url") @SerialName("url") val url: String?,
        @JsonProperty("image") @SerialName("image") val image: String?,
        @JsonProperty("hentai") @SerialName("hentai") val hentai: Boolean?,
        @JsonProperty("sticky") @SerialName("sticky") val sticky: Boolean?,
        @JsonProperty("active") @SerialName("active") val active: Boolean?,
        @JsonProperty("actor") @SerialName("actor") val actor: String?,
        @JsonProperty("malId") @SerialName("malId") val malId: Int?,
        @JsonProperty("aniId") @SerialName("aniId") val aniId: Int?,
        @JsonProperty("createdAt") @SerialName("createdAt") val createdAt: String?,
        @JsonProperty("updatedAt") @SerialName("updatedAt") val updatedAt: String?,
        @JsonProperty("deletedAt") @SerialName("deletedAt") val deletedAt: String?,
        @JsonProperty("Mal") @SerialName("Mal") val mal: Mal?,
        @JsonProperty("Anilist") @SerialName("Anilist") val anilist: Anilist?,
        @JsonProperty("malUrl") @SerialName("malUrl") val malUrl: String?,
    )

    @Serializable
    data class Anilist(
        @JsonProperty("id") @SerialName("id") val id: Int?,
        @JsonProperty("malId") @SerialName("malId") val malId: Int?,
        @JsonProperty("type") @SerialName("type") val type: String?,
        @JsonProperty("title") @SerialName("title") val title: String?,
        @JsonProperty("url") @SerialName("url") val url: String?,
        @JsonProperty("image") @SerialName("image") val image: String?,
        @JsonProperty("category") @SerialName("category") val category: String?,
        @JsonProperty("hentai") @SerialName("hentai") val hentai: Boolean?,
        @JsonProperty("createdAt") @SerialName("createdAt") val createdAt: String?,
        @JsonProperty("updatedAt") @SerialName("updatedAt") val updatedAt: String?,
        @JsonProperty("deletedAt") @SerialName("deletedAt") val deletedAt: String?,
    )

    @Serializable
    data class Mal(
        @JsonProperty("id") @SerialName("id") val id: Int?,
        @JsonProperty("type") @SerialName("type") val type: String?,
        @JsonProperty("title") @SerialName("title") val title: String?,
        @JsonProperty("url") @SerialName("url") val url: String?,
        @JsonProperty("image") @SerialName("image") val image: String?,
        @JsonProperty("category") @SerialName("category") val category: String?,
        @JsonProperty("hentai") @SerialName("hentai") val hentai: Boolean?,
        @JsonProperty("createdAt") @SerialName("createdAt") val createdAt: String?,
        @JsonProperty("updatedAt") @SerialName("updatedAt") val updatedAt: String?,
        @JsonProperty("deletedAt") @SerialName("deletedAt") val deletedAt: String?,
    )
}
