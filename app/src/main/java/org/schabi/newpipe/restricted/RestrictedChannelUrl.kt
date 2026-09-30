package org.schabi.newpipe.restricted

import org.schabi.newpipe.extractor.ServiceList
import java.net.URI
import java.util.Locale

/**
 * The single place where a channel URL is turned into a **canonical channel identity**.
 */
object RestrictedChannelUrl {

    private const val SCHEME_HTTPS = "https://www.youtube.com"

    private val youtubeServiceId: Int by lazy { ServiceList.YouTube.serviceId }

    private val youtubeHosts = setOf(
        "youtube.com",
        "youtube-nocookie.com",
        "music.youtube.com",
    )

    /**
     * @return a canonical channel key, or `null` if [url] cannot be identified as a channel.
     */
    @JvmStatic
    fun canonicalKey(serviceId: Int, url: String?): String? {
        val raw = url?.trim().orEmpty()
        if (raw.isEmpty()) {
            return null
        }

        val uri = try {
            URI(raw)
        } catch (e: Exception) {
            // Malformed URL: uncertain identity, fail closed.
            return null
        }

        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme != "http" && scheme != "https") {
            return null
        }

        var host = uri.host?.lowercase(Locale.ROOT) ?: return null
        host = host.removePrefix("www.").removePrefix("m.")
        if (host.isEmpty()) {
            return null
        }

        val path = normalizePath(uri.path ?: return null) ?: return null

        return if (serviceId == youtubeServiceId && host in youtubeHosts) {
            canonicalYouTubeChannel(path)
        } else {
            "$scheme://$host$path"
        }
    }

    /**
     * @return `true` if both URLs (if any) identify the same channel of the same service.
     */
    @JvmStatic
    fun isSameChannel(serviceId: Int, first: String?, second: String?): Boolean {
        val a = canonicalKey(serviceId, first) ?: return false
        val b = canonicalKey(serviceId, second) ?: return false
        return a == b
    }

    /** Strips the query/fragment, collapses duplicate separators and drops a trailing slash. */
    private fun normalizePath(rawPath: String): String? {
        var path = rawPath
        while (path.length > 1 && path.endsWith('/')) {
            path = path.dropLast(1)
        }
        if (path.isEmpty() || path == "/") {
            return null
        }
        return path
    }

    /**
     * YouTube exposes a channel under several URL shapes.
     */
    private fun canonicalYouTubeChannel(path: String): String? {
        val segments = path.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) {
            return null
        }

        val head = segments[0].lowercase(Locale.ROOT)
        return when {
            head == "channel" && segments.size >= 2 && segments[1].startsWith("UC") ->
                "$SCHEME_HTTPS/channel/${segments[1]}"

            head == "user" && segments.size >= 2 && segments[1].isNotEmpty() ->
                "$SCHEME_HTTPS/user/${segments[1].lowercase(Locale.ROOT)}"

            head == "c" && segments.size >= 2 && segments[1].isNotEmpty() ->
                "$SCHEME_HTTPS/c/${segments[1].lowercase(Locale.ROOT)}"

            head.startsWith("@") && head.length > 1 ->
                "$SCHEME_HTTPS/$head"

            else -> null
        }
    }
}
