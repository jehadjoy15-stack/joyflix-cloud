package com.lagradost.cloudstream3.utils

import android.util.Log
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages dead, broken, or unreachable video stream links.
 * When a source/link fails (e.g., 404, player error, invalid stream data),
 * it is added to this dead list so it won't be shown or attempted again.
 */
object DeadLinkManager {
    private const val TAG = "DeadLinkManager"
    private const val DEAD_LINKS_KEY = "joyflix_dead_links_v1"
    private const val MAX_DEAD_LINKS = 2000

    private val deadLinks: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var isInitialized = false

    private fun normalize(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return url.trim()
    }

    private fun ensureInitialized() {
        if (isInitialized) return
        synchronized(this) {
            if (isInitialized) return
            try {
                val saved = getKey<Array<String>>(DEAD_LINKS_KEY)
                if (saved != null) {
                    deadLinks.addAll(saved)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Error initializing DeadLinkManager", e)
            } finally {
                isInitialized = true
            }
        }
    }

    /**
     * Checks if the given URL is recorded in the dead links list.
     */
    fun isDead(url: String?): Boolean {
        val norm = normalize(url) ?: return false
        ensureInitialized()
        return deadLinks.contains(norm)
    }

    /**
     * Marks a URL as dead so it won't be displayed or played again.
     */
    fun markDead(url: String?, reason: String? = null) {
        val norm = normalize(url) ?: return
        ensureInitialized()
        if (deadLinks.add(norm)) {
            Log.w(TAG, "Marked link as dead ($reason): $norm")
            saveAsync()
        }
    }

    /**
     * Clears all dead links from memory and persistent storage.
     */
    fun clearDeadLinks() {
        deadLinks.clear()
        ioSafe {
            setKey(DEAD_LINKS_KEY, emptyArray<String>())
        }
    }

    fun deadCount(): Int {
        ensureInitialized()
        return deadLinks.size
    }

    private fun saveAsync() {
        ioSafe {
            try {
                val listToSave = if (deadLinks.size > MAX_DEAD_LINKS) {
                    deadLinks.toList().takeLast(MAX_DEAD_LINKS).toTypedArray()
                } else {
                    deadLinks.toTypedArray()
                }
                setKey(DEAD_LINKS_KEY, listToSave)
            } catch (e: Throwable) {
                Log.e(TAG, "Error saving dead links", e)
            }
        }
    }
}
