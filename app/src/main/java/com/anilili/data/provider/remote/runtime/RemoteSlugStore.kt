package com.anilili.data.provider.remote.runtime

import java.util.Collections
import java.util.LinkedHashMap

/**
 * Thread-safe LRU cache storing cross-workflow entity identifiers bridging
 * AniList IDs to site-specific anime URLs/slugs and episode URLs/slugs.
 */
class RemoteSlugStore(private val maxEntries: Int = 512) {

    private val animeSlugs: MutableMap<String, String> = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(maxEntries, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
                return size > maxEntries
            }
        }
    )

    private val episodeSlugs: MutableMap<String, String> = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(maxEntries * 4, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
                return size > (maxEntries * 4)
            }
        }
    )

    private fun animeKey(providerId: String, anilistId: Int): String = "$providerId:$anilistId"
    private fun episodeKey(providerId: String, anilistId: Int, episodeNumber: Double): String =
        "$providerId:$anilistId:$episodeNumber"

    fun getAnimeSlug(providerId: String, anilistId: Int): String? {
        return animeSlugs[animeKey(providerId, anilistId)]
    }

    fun putAnimeSlug(providerId: String, anilistId: Int, slugOrUrl: String) {
        animeSlugs[animeKey(providerId, anilistId)] = slugOrUrl
    }

    fun getEpisodeSlug(providerId: String, anilistId: Int, episodeNumber: Double): String? {
        return episodeSlugs[episodeKey(providerId, anilistId, episodeNumber)]
    }

    fun putEpisodeSlug(providerId: String, anilistId: Int, episodeNumber: Double, episodeSlugOrUrl: String) {
        episodeSlugs[episodeKey(providerId, anilistId, episodeNumber)] = episodeSlugOrUrl
    }

    fun invalidateMapping(providerId: String, anilistId: Int) {
        animeSlugs.remove(animeKey(providerId, anilistId))
        val prefix = "$providerId:$anilistId:"
        synchronized(episodeSlugs) {
            val iterator = episodeSlugs.keys.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().startsWith(prefix)) {
                    iterator.remove()
                }
            }
        }
    }

    fun clear() {
        animeSlugs.clear()
        episodeSlugs.clear()
    }
}
