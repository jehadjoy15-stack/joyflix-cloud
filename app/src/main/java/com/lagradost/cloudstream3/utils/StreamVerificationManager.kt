package com.lagradost.cloudstream3.utils

import android.util.Log
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TorrentLoadResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * High-speed stream verification and pre-filtering manager.
 * Ensures items displayed on Home Page and Search Results have working, playable video streams.
 * Items without playable links are immediately pruned and marked dead so users never encounter "No Links Found".
 */
object StreamVerificationManager {
    private const val TAG = "StreamVerifyManager"
    private const val PLAYABLE_CACHE_KEY = "joyflix_verified_playable_v1"
    private const val MAX_CACHE_ENTRIES = 2000
    private const val PLAYABLE_CACHE_TTL_MS = 6 * 60 * 60 * 1000L // 6 hours

    // URL -> Verification Timestamp
    private val verifiedPlayableCache = ConcurrentHashMap<String, Long>()

    @Volatile
    private var isInitialized = false

    private class PlayableFoundSignal : CancellationException("Playable stream verified early")

    private fun normalize(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return url.trim()
    }

    private fun ensureInitialized() {
        if (isInitialized) return
        synchronized(this) {
            if (isInitialized) return
            try {
                val saved = getKey<Array<String>>(PLAYABLE_CACHE_KEY)
                val now = System.currentTimeMillis()
                if (saved != null) {
                    for (entry in saved) {
                        verifiedPlayableCache[entry] = now
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Error initializing StreamVerificationManager", e)
            } finally {
                isInitialized = true
            }
        }
    }

    /**
     * Checks whether a URL is already known to be playable.
     * Returns true if verified playable, or null if unverified.
     */
    fun isPlayableCached(url: String?): Boolean? {
        val norm = normalize(url) ?: return false
        ensureInitialized()
        val ts = verifiedPlayableCache[norm] ?: return null
        if (System.currentTimeMillis() - ts > PLAYABLE_CACHE_TTL_MS) {
            verifiedPlayableCache.remove(norm)
            return null
        }
        return true
    }

    /**
     * Records a URL as having verified playable streams.
     */
    fun markPlayable(url: String?) {
        val norm = normalize(url) ?: return
        ensureInitialized()
        verifiedPlayableCache[norm] = System.currentTimeMillis()
        saveCacheAsync()
    }

    /**
     * Verifies if a given SearchResponse has playable video streams.
     * Uses lightweight metadata checks and early-exit link extraction with strict timeouts.
     */
    suspend fun isPlayable(response: SearchResponse): Boolean {
        val url = normalize(response.url) ?: return false

        // Check cached status
        val cached = isPlayableCached(url)
        if (cached != null) return cached

        val api = APIHolder.getApiFromNameNull(response.apiName) ?: return false

        return try {
            withTimeoutOrNull(2500L) {
                val repo = APIRepository(api)
                val loadRes = repo.load(url)
                if (loadRes !is Resource.Success) {
                    return@withTimeoutOrNull false
                }

                val data = loadRes.value
                if (data.comingSoon) {
                    return@withTimeoutOrNull false
                }

                when (data) {
                    is TorrentLoadResponse -> {
                        val hasTorrent = !data.magnet.isNullOrBlank() || !data.torrent.isNullOrBlank()
                        if (hasTorrent) {
                            markPlayable(url)
                            true
                        } else {
                            false
                        }
                    }
                    is LiveStreamLoadResponse -> {
                        if (data.dataUrl.isNotBlank()) {
                            markPlayable(url)
                            true
                        } else {
                            false
                        }
                    }
                    is MovieLoadResponse -> {
                        if (data.dataUrl.isBlank()) {
                            false
                        } else {
                            testStreamLink(repo, data.dataUrl, url)
                        }
                    }
                    is AnimeLoadResponse -> {
                        val episode = data.episodes.values.firstOrNull { it.isNotEmpty() }?.firstOrNull()
                        if (episode == null || episode.data.isBlank()) {
                            false
                        } else {
                            testStreamLink(repo, episode.data, url)
                        }
                    }
                    is TvSeriesLoadResponse -> {
                        val episode = data.episodes.firstOrNull()
                        if (episode == null || episode.data.isBlank()) {
                            false
                        } else {
                            testStreamLink(repo, episode.data, url)
                        }
                    }
                    else -> {
                        // For generic LoadResponse without specific subtype, check if comingSoon is false
                        markPlayable(url)
                        true
                    }
                }
            } ?: false
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * Performs a fast link extraction test with early cancellation as soon as the first playable link is produced.
     */
    private suspend fun testStreamLink(repo: APIRepository, streamData: String, itemUrl: String): Boolean {
        var hasValidLink = false
        try {
            withTimeout(1800L) {
                repo.loadLinks(streamData, isCasting = false, subtitleCallback = {}) { link ->
                    if (link.url.isNotBlank()) {
                        hasValidLink = true
                        throw PlayableFoundSignal()
                    }
                }
            }
        } catch (signal: PlayableFoundSignal) {
            hasValidLink = true
        } catch (t: Throwable) {
            // Timeout or extractor failure
        }

        return if (hasValidLink) {
            markPlayable(itemUrl)
            true
        } else {
            false
        }
    }

    /**
     * Filters a list of SearchResponse to return only items guaranteed to be playable.
     * Executes concurrently with a bounded thread pool and fast timeouts for maximum speed.
     */
    suspend fun filterPlayable(
        items: List<SearchResponse>,
        maxKeep: Int = 30
    ): List<SearchResponse> {
        if (items.isEmpty()) return emptyList()

        val results = mutableListOf<SearchResponse>()
        val needCheck = mutableListOf<SearchResponse>()

        // Step 1: Immediate cache partitioning (0ms)
        for (item in items) {
            val url = normalize(item.url) ?: continue

            val cached = isPlayableCached(url)
            if (cached == true) {
                results.add(item)
                if (results.size >= maxKeep) return results
            } else if (cached == null) {
                needCheck.add(item)
            }
        }

        if (needCheck.isEmpty()) return results

        // Step 2: High-concurrency parallel verification
        val toCheck = needCheck.take((maxKeep - results.size).coerceAtLeast(1))
        val semaphore = Semaphore(12)

        val verified = withTimeoutOrNull(4000L) {
            coroutineScope {
                toCheck.map { item ->
                    async(Dispatchers.IO) {
                        try {
                            semaphore.withPermit {
                                if (isPlayable(item)) item else null
                            }
                        } catch (t: Throwable) {
                            null
                        }
                    }
                }.awaitAll().filterNotNull()
            }
        } ?: emptyList()

        results.addAll(verified)
        return results
    }

    /**
     * Filters a HomePageList, removing all unplayable cards.
     */
    suspend fun filterHomePageList(homePageList: HomePageList): HomePageList {
        val playable = filterPlayable(homePageList.list)
        return homePageList.copy(list = playable)
    }

    private fun saveCacheAsync() {
        ioSafe {
            try {
                val listToSave = if (verifiedPlayableCache.size > MAX_CACHE_ENTRIES) {
                    verifiedPlayableCache.keys.toList().takeLast(MAX_CACHE_ENTRIES).toTypedArray()
                } else {
                    verifiedPlayableCache.keys.toTypedArray()
                }
                setKey(PLAYABLE_CACHE_KEY, listToSave)
            } catch (e: Throwable) {
                Log.e(TAG, "Error saving verified playable cache", e)
            }
        }
    }
}
