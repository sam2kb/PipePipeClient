package org.schabi.newpipe.restricted

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Restricted Mode authorizes on `(serviceId, canonical channel URL)`, so the canonicalisation
 * is part of the security boundary: a key that is too permissive would match a channel that was
 * never subscribed, and a key that collapses two different channels would grant access to both.
 */
class RestrictedChannelUrlTest {

    private val youtube = 0
    private val soundCloud = 1

    // -------------------- The shapes PipePipe itself stores
    @Test
    fun `youtube channel url is its own canonical key`() {
        val url = "https://www.youtube.com/channel/UCabcdefghijklmnopqrstuv"
        assertEquals(url, RestrictedChannelUrl.canonicalKey(youtube, url))
    }

    @Test
    fun `canonicalisation is idempotent`() {
        val inputs = listOf(
            "https://www.youtube.com/channel/UCabcdefghijklmnopqrstuv",
            "https://m.youtube.com/channel/UCabcdefghijklmnopqrstuv/videos",
            "https://youtube.com/@SomeHandle",
            "https://www.youtube.com/c/SomeName",
            "https://www.youtube.com/user/SomeUser",
            "https://soundcloud.com/some-artist",
        )
        inputs.forEach { input ->
            val key = RestrictedChannelUrl.canonicalKey(youtube, input)
            assertEquals(input, key, RestrictedChannelUrl.canonicalKey(youtube, key))
        }
    }

    // -------------------- URL shapes the extractor produces for a video's uploader
    @Test
    fun `mobile and trailing path variants collapse onto the stored channel url`() {
        val stored = "https://www.youtube.com/channel/UCabcdefghijklmnopqrstuv"
        val variants = listOf(
            "https://www.youtube.com/channel/UCabcdefghijklmnopqrstuv",
            "https://m.youtube.com/channel/UCabcdefghijklmnopqrstuv",
            "https://youtube.com/channel/UCabcdefghijklmnopqrstuv/",
            "https://www.youtube.com/channel/UCabcdefghijklmnopqrstuv/videos",
            "https://www.youtube.com/channel/UCabcdefghijklmnopqrstuv?flow=grid",
        )
        variants.forEach { variant ->
            assertEquals(variant, stored, RestrictedChannelUrl.canonicalKey(youtube, variant))
        }
    }

    @Test
    fun `handles and legacy paths keep their own lowercase key`() {
        assertEquals("https://www.youtube.com/@somehandle",
            RestrictedChannelUrl.canonicalKey(youtube, "https://www.youtube.com/@SomeHandle"))
        assertEquals("https://www.youtube.com/c/somename",
            RestrictedChannelUrl.canonicalKey(youtube, "https://www.youtube.com/c/SomeName/videos"))
        assertEquals("https://www.youtube.com/user/someuser",
            RestrictedChannelUrl.canonicalKey(youtube, "https://www.youtube.com/user/SomeUser"))
    }

    @Test
    fun `a handle key does not match a channel id key`() {
        assertFalse(RestrictedChannelUrl.isSameChannel(
            youtube,
            "https://www.youtube.com/@somehandle",
            "https://www.youtube.com/channel/UCabcdefghijklmnopqrstuv"))
    }

    @Test
    fun `two different channels never share a key`() {
        assertFalse(RestrictedChannelUrl.isSameChannel(
            youtube,
            "https://www.youtube.com/channel/UCaaaaaaaaaaaaaaaaaaaaaa",
            "https://www.youtube.com/channel/UCbbbbbbbbbbbbbbbbbbbbbb"))
        assertFalse(RestrictedChannelUrl.isSameChannel(
            youtube,
            "https://www.youtube.com/c/somename",
            "https://www.youtube.com/user/somename"))
        // The same path on a different service must not collide either.
        assertFalse(RestrictedChannelUrl.isSameChannel(youtube,
            "https://www.youtube.com/channel/UCabcdefghijklmnopqrstuv",
            "https://peertube.example/channel/UCabcdefghijklmnopqrstuv"))
    }

    // -------------------- Fail closed: anything that is not a channel has no key at all
    @Test
    fun `non channel urls canonicalise to null`() {
        val notChannels = listOf(
            null,
            "",
            "   ",
            "UCabcdefghijklmnopqrstuv",                       // bare id, no URL
            "Placeholder",                                    // PlayerMediaItem.placeholder()
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",     // a video
            "https://www.youtube.com/playlist?list=PL123",     // a playlist
            "https://www.youtube.com/",                        // no channel path
            "https://www.youtube.com",                         // no path at all
            "https://www.youtube.com/channel/",                // channel without an id
            "https://www.youtube.com/channel/notAChannelId",   // not a UC id
            "ftp://www.youtube.com/channel/UCabcdefghijklmnopqrstuv",
            "not a url at all",
        )
        notChannels.forEach { input ->
            assertNull("expected no canonical key for <$input>",
                RestrictedChannelUrl.canonicalKey(youtube, input))
        }
    }

    @Test
    fun `fail closed helper denies when either side has no key`() {
        assertFalse(RestrictedChannelUrl.isSameChannel(youtube, null,
            "https://www.youtube.com/channel/UCabcdefghijklmnopqrstuv"))
        assertFalse(RestrictedChannelUrl.isSameChannel(youtube,
            "https://www.youtube.com/channel/UCabcdefghijklmnopqrstuv", null))
        assertFalse(RestrictedChannelUrl.isSameChannel(youtube, null, null))
    }

    @Test
    fun `the host decides, not the userinfo or the path`() {
        // userinfo must not change the identity: the real host is youtube.com here.
        assertEquals("https://www.youtube.com/channel/UCabcdefghijklmnopqrstuv",
            RestrictedChannelUrl.canonicalKey(youtube,
                "https://evil.example@www.youtube.com/channel/UCabcdefghijklmnopqrstuv"))

        // A lookalike host is a different channel identity and can never match a YouTube row.
        val lookalike = RestrictedChannelUrl.canonicalKey(youtube,
            "https://www.youtube.com.evil.example/channel/UCabcdefghijklmnopqrstuv")
        assertEquals("https://youtube.com.evil.example/channel/UCabcdefghijklmnopqrstuv", lookalike)
        assertFalse(RestrictedChannelUrl.isSameChannel(youtube, lookalike,
            "https://www.youtube.com/channel/UCabcdefghijklmnopqrstuv"))
    }

    @Test
    fun `path traversal does not smuggle a channel id through`() {
        // The id has to be the first path segment after /channel/, not something reached by
        // normalising the path.
        assertNull(RestrictedChannelUrl.canonicalKey(youtube,
            "https://www.youtube.com/channel/../channel/UCabcdefghijklmnopqrstuv"))
        assertEquals("https://www.youtube.com/channel/UCabcdefghijklmnopqrstuv",
            RestrictedChannelUrl.canonicalKey(youtube,
                "https://www.youtube.com/channel/UCabcdefghijklmnopqrstuv/../videos"))
    }

    @Test
    fun `a channel tab url canonicalises onto its channel`() {
        // ChannelTabFragment authorizes on the tab's own URL.
        val channel = "https://www.youtube.com/channel/UCabcdefghijklmnopqrstuv"
        listOf("videos", "shorts", "streams", "playlists", "community", "search?query=x")
            .forEach { tab ->
                assertEquals("tab=$tab", channel,
                    RestrictedChannelUrl.canonicalKey(youtube, "$channel/$tab"))
            }
        assertEquals(channel,
            RestrictedChannelUrl.canonicalKey(youtube, "$channel/videos?view=0&sort=dd"))
    }

    // -------------------- Other services
    @Test
    fun `non youtube channel urls keep host and path and drop the query`() {
        assertEquals("https://soundcloud.com/some-artist",
            RestrictedChannelUrl.canonicalKey(soundCloud, "https://soundcloud.com/some-artist"))
        assertEquals("https://soundcloud.com/some-artist",
            RestrictedChannelUrl.canonicalKey(soundCloud,
                "https://www.soundcloud.com/some-artist/?si=abc#frag"))
    }
}
