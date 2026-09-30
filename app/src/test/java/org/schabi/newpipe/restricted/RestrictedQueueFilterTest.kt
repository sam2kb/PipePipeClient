package org.schabi.newpipe.restricted

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.player.mediaitem.PlayerMediaItem

/**
 * Restricted Mode has to fail closed on queue entries: a stream whose channel cannot be
 * identified must never be played. These tests pin that decision table down.
 */
class RestrictedQueueFilterTest {

    private val youtube = 0
    private val soundCloud = 1

    private val subscribedChannel = "https://www.youtube.com/channel/UCabcdefghijklmnopqrstuv"
    private val otherChannel = "https://www.youtube.com/channel/UCzzzzzzzzzzzzzzzzzzzzzz"

    private val allowlist = setOf(subscribedChannel)

    private fun item(
        serviceId: Int = youtube,
        url: String = "https://www.youtube.com/watch?v=aaaaaaaaaaa",
        uploaderUrl: String? = subscribedChannel,
        uploader: String = "Approved Channel",
    ) = PlayerMediaItem.Builder()
        .serviceId(serviceId)
        .url(url)
        .title("title")
        .uploader(uploader)
        .uploaderUrl(uploaderUrl)
        .duration(60)
        .thumbnailUrl(null)
        .streamType(StreamType.VIDEO_STREAM)
        .build()

    // -------------------- The decision table
    @Test
    fun `subscribed uploader is allowed`() {
        assertTrue(RestrictedQueueFilter.isItemAllowed(item(), allowlist))
    }

    @Test
    fun `non subscribed uploader is denied`() {
        assertFalse(RestrictedQueueFilter.isItemAllowed(
            item(uploaderUrl = otherChannel), allowlist))
    }

    @Test
    fun `missing uploader url is denied`() {
        assertFalse(RestrictedQueueFilter.isItemAllowed(item(uploaderUrl = null), allowlist))
        assertFalse(RestrictedQueueFilter.isItemAllowed(item(uploaderUrl = ""), allowlist))
        assertFalse(RestrictedQueueFilter.isItemAllowed(item(uploaderUrl = "  "), allowlist))
    }

    @Test
    fun `placeholder item is denied`() {
        assertFalse(RestrictedQueueFilter.isItemAllowed(PlayerMediaItem.placeholder(), allowlist))
    }

    @Test
    fun `a non channel uploader url is denied even when it looks authorised`() {
        // A video URL must never be treated as a channel identity.
        assertFalse(RestrictedQueueFilter.isItemAllowed(
            item(uploaderUrl = "https://www.youtube.com/watch?v=aaaaaaaaaaa"), allowlist))
        assertFalse(RestrictedQueueFilter.isItemAllowed(
            item(uploaderUrl = "https://www.youtube.com/playlist?list=PL123"), allowlist))
    }

    @Test
    fun `empty allowlist denies everything`() {
        assertFalse(RestrictedQueueFilter.isItemAllowed(item(), emptySet()))
    }

    @Test
    fun `the service id separates two services with the same path`() {
        val scItem = item(serviceId = soundCloud,
            uploaderUrl = "https://soundcloud.com/artist")
        assertFalse(RestrictedQueueFilter.isItemAllowed(scItem, allowlist))
        assertTrue(RestrictedQueueFilter.isItemAllowed(scItem, setOf("https://soundcloud.com/artist")))
    }

    @Test
    fun `uploader name is never used as the identity`() {
        // Same name, different channel: still denied.
        assertFalse(RestrictedQueueFilter.isItemAllowed(
            item(uploaderUrl = otherChannel, uploader = "Approved Channel"), allowlist))
    }

    // -------------------- The list filter built on top of it
    @Test
    fun `mixed channel playlist keeps only subscribed entries`() {
        val a = item(url = "https://www.youtube.com/watch?v=a")
        val b = item(url = "https://www.youtube.com/watch?v=b", uploaderUrl = otherChannel)
        val c = item(url = "https://www.youtube.com/watch?v=c", uploaderUrl = null)
        val d = item(url = "https://www.youtube.com/watch?v=d")

        val kept = RestrictedQueueFilter.filterItems(listOf(a, b, c, d)) { allowlist }

        assertEquals(2, kept.size)
        assertEquals(listOf(a, d), kept)
    }

    @Test
    fun `the allowlist is resolved once per service`() {
        var lookups = 0
        val items = (1..5).map { item(url = "https://www.youtube.com/watch?v=$it") }
        RestrictedQueueFilter.filterItems(items) { lookups++; allowlist }
        assertEquals(1, lookups)
    }

    @Test
    fun `an entirely unauthorised queue filters down to nothing`() {
        val items = (1..3).map {
            item(url = "https://www.youtube.com/watch?v=$it", uploaderUrl = otherChannel)
        }
        assertTrue(RestrictedQueueFilter.filterItems(items) { allowlist }.isEmpty())
    }
}
