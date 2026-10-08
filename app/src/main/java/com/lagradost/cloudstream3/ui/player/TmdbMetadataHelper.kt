package com.lagradost.cloudstream3.ui.player

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

data class TmdbMediaInfo(
    val logoUrl: String?,
    val rating: String?,
    val year: String?,
    val overview: String?
)

object TmdbMetadataHelper {
    private const val TMDB_API_KEY = "e6333b32409e02a4a6eba6fb7ff866bb"
    private const val TMDB_SEARCH_URL = "https://api.themoviedb.org/3/search/multi"
    private const val TMDB_IMAGE_BASE = "https://image.tmdb.org/t/p/w500"

    private val cache = ConcurrentHashMap<String, TmdbMediaInfo>()

    fun cleanTitle(rawTitle: String): String {
        var t = rawTitle
        // Remove Season X, S01, Part X, Cour X, Dub, Sub, Uncensored, Movie etc.
        t = t.replace(Regex("(?i)\\b(season\\s*\\d+|s\\d+|part\\s*\\d+|cour\\s*\\d+|dub|sub|uncensored|bd|movie)\\b"), "")
        // Remove parenthesis/brackets content e.g. (2024), [Dub]
        t = t.replace(Regex("[\\(\\[\\{].*?[\\)\\]\\}]"), "")
        // Normalize whitespace
        t = t.replace(Regex("\\s+"), " ").trim()
        return if (t.length >= 2) t else rawTitle.trim()
    }

    suspend fun fetchMediaInfo(rawTitle: String): TmdbMediaInfo? = withContext(Dispatchers.IO) {
        val clean = cleanTitle(rawTitle)
        if (clean.isBlank()) return@withContext null

        cache[clean]?.let { return@withContext it }

        try {
            val searchRes = app.get(
                TMDB_SEARCH_URL,
                params = mapOf(
                    "api_key" to TMDB_API_KEY,
                    "query" to clean,
                    "include_adult" to "false",
                    "language" to "en-US"
                ),
                cacheTime = 60 * 24
            )

            val searchJson = JSONObject(searchRes.text)
            val results = searchJson.optJSONArray("results") ?: return@withContext null
            if (results.length() == 0) return@withContext null

            // Pick the first result that is movie or tv
            var selected: JSONObject? = null
            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                val mType = item.optString("media_type")
                if (mType == "movie" || mType == "tv") {
                    selected = item
                    break
                }
            }
            if (selected == null) selected = results.optJSONObject(0) ?: return@withContext null

            val mType = selected.optString("media_type", "movie")
            val id = selected.optInt("id", -1)
            if (id <= 0) return@withContext null

            // Rating
            val voteAvg = selected.optDouble("vote_average", 0.0)
            val ratingStr = if (voteAvg > 0.0) String.format("%.1f", voteAvg) else null

            // Year
            val releaseDate = selected.optString("release_date").ifBlank { selected.optString("first_air_date") }
            val yearStr = if (releaseDate.length >= 4) releaseDate.substring(0, 4) else null

            // Overview
            val overview = selected.optString("overview").takeIf { it.isNotBlank() }

            // Fetch Logos
            val imgUrl = "https://api.themoviedb.org/3/$mType/$id/images"
            val imgRes = app.get(
                imgUrl,
                params = mapOf(
                    "api_key" to TMDB_API_KEY,
                    "include_image_language" to "en,null,ja"
                ),
                cacheTime = 60 * 24
            )

            val imgJson = JSONObject(imgRes.text)
            val logos = imgJson.optJSONArray("logos")
            var bestLogoPath: String? = null

            if (logos != null && logos.length() > 0) {
                // Prefer English logo
                for (i in 0 until logos.length()) {
                    val l = logos.optJSONObject(i) ?: continue
                    val lang = l.optString("iso_639_1")
                    if (lang == "en") {
                        bestLogoPath = l.optString("file_path")
                        break
                    }
                }
                if (bestLogoPath == null) {
                    bestLogoPath = logos.optJSONObject(0)?.optString("file_path")
                }
            }

            val logoFullUrl = if (!bestLogoPath.isNullOrBlank()) "$TMDB_IMAGE_BASE$bestLogoPath" else null

            val info = TmdbMediaInfo(
                logoUrl = logoFullUrl,
                rating = ratingStr,
                year = yearStr,
                overview = overview
            )
            cache[clean] = info
            info
        } catch (e: Exception) {
            logError(e)
            null
        }
    }
}
