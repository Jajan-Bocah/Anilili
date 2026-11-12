package com.anilili.data.provider.remote.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteSlugStoreTest {

    @Test
    fun `store and retrieve anime slug`() {
        val store = RemoteSlugStore(maxEntries = 10)
        store.putAnimeSlug("anichin", 12345, "btth-season-5")

        assertEquals("btth-season-5", store.getAnimeSlug("anichin", 12345))
        assertNull(store.getAnimeSlug("kuramanime", 12345))
        assertNull(store.getAnimeSlug("anichin", 99999))
    }

    @Test
    fun `store and retrieve episode slug`() {
        val store = RemoteSlugStore(maxEntries = 10)
        store.putEpisodeSlug("anichin", 12345, 218.0, "btth-s5-ep-218")

        assertEquals("btth-s5-ep-218", store.getEpisodeSlug("anichin", 12345, 218.0))
        assertNull(store.getEpisodeSlug("anichin", 12345, 217.0))
    }

    @Test
    fun `invalidate mapping removes anime and associated episodes`() {
        val store = RemoteSlugStore(maxEntries = 10)
        store.putAnimeSlug("anichin", 100, "series-slug")
        store.putEpisodeSlug("anichin", 100, 1.0, "ep-1")
        store.putEpisodeSlug("anichin", 100, 2.0, "ep-2")

        store.invalidateMapping("anichin", 100)

        assertNull(store.getAnimeSlug("anichin", 100))
        assertNull(store.getEpisodeSlug("anichin", 100, 1.0))
        assertNull(store.getEpisodeSlug("anichin", 100, 2.0))
    }

    @Test
    fun `lru eviction maintains maximum entries limit`() {
        val store = RemoteSlugStore(maxEntries = 3)
        store.putAnimeSlug("p", 1, "s1")
        store.putAnimeSlug("p", 2, "s2")
        store.putAnimeSlug("p", 3, "s3")
        store.putAnimeSlug("p", 4, "s4") // Evicts 1

        assertNull(store.getAnimeSlug("p", 1))
        assertEquals("s4", store.getAnimeSlug("p", 4))
    }
}
