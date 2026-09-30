package org.schabi.newpipe.restricted

import android.content.Context
import android.util.Log
import org.schabi.newpipe.player.mediaitem.PlayerMediaItem
import org.schabi.newpipe.player.playqueue.PlayQueue

/**
 * Removes every entry whose channel is not authorized from a [PlayQueue], and refuses to start
 * a player on an empty result.
 */
object RestrictedQueueFilter {
    private const val TAG = "RestrictedQueueFilter"

    /**
     * Filters [queue] in place.
     *
     * @return `true` if the queue is allowed to be played, i.e. Restricted Mode is off or at least
     *         one entry survived the filter.
     */
    @JvmStatic
    fun filterInPlace(context: Context?, queue: PlayQueue?): Boolean {
        if (queue == null) {
            return false
        }
        if (!RestrictedChannelAccess.isRestricted(context)) {
            return !queue.isEmpty
        }

        val count = queue.size()
        if (count == 0) {
            return false
        }

        val allowedByService = HashMap<Int, Set<String>>()
        var removed = 0

        // Walk backwards: removing an entry only shifts the entries after it.
        for (index in count - 1 downTo 0) {
            val item = queue.getItem(index) ?: continue
            val allowed = allowedByService.getOrPut(item.serviceId) {
                RestrictedChannelAccess.allowedKeysBlocking(context, item.serviceId)
            }
            if (!isItemAllowed(item, allowed)) {
                queue.remove(index)
                removed++
            }
        }

        if (removed > 0) {
            Log.i(TAG, "Restricted Mode removed $removed of $count queue entries")
            RestrictedChannelAccess.notifyVideoBlocked(context)
        }
        return !queue.isEmpty
    }

    /**
     * @return [queue] itself when it may be played, `null` when Restricted Mode filtered everything
     *         out (the caller must then not start a player at all).
     */
    @JvmStatic
    fun sanitized(context: Context?, queue: PlayQueue?): PlayQueue? =
        if (filterInPlace(context, queue)) queue else null

    /**
     * The list-level filter used at queue-append time. Returns [items] unchanged while Restricted
     * Mode is off, and otherwise only the entries whose uploader channel is subscribed.
     */
    @JvmStatic
    fun filterItems(
        context: Context?,
        items: List<PlayerMediaItem>?,
    ): List<PlayerMediaItem> {
        if (items == null || items.isEmpty()) {
            return emptyList()
        }
        if (!RestrictedChannelAccess.isRestricted(context)) {
            return items
        }

        val kept = filterItems(items) { serviceId ->
            RestrictedChannelAccess.allowedKeysBlocking(context, serviceId)
        }
        if (kept.size != items.size) {
            RestrictedChannelAccess.notifyVideoBlocked(context)
        }
        return kept
    }

    /**
     * The allowlist-injected form of [filterItems], used by the unit tests and by callers that
     * already hold an allowlist.
     */
    @JvmStatic
    fun filterItems(
        items: List<PlayerMediaItem>,
        allowedKeysFor: (Int) -> Set<String>,
    ): List<PlayerMediaItem> {
        val allowedByService = HashMap<Int, Set<String>>()
        val kept = ArrayList<PlayerMediaItem>(items.size)
        for (item in items) {
            val allowed = allowedByService.getOrPut(item.serviceId) {
                allowedKeysFor(item.serviceId)
            }
            if (isItemAllowed(item, allowed)) {
                kept.add(item)
            } else {
                Log.i(TAG, "Restricted Mode dropped queue entry " + item.url)
            }
        }
        return kept
    }

    /**
     * The pure decision function of the queue filter: an entry is allowed only if the canonical
     * channel key of its uploader URL is in [allowedKeys] (the canonical channel keys
     * subscribed for the item's service).
     */
    @JvmStatic
    fun isItemAllowed(item: PlayerMediaItem, allowedKeys: Set<String>): Boolean {
        if (allowedKeys.isEmpty()) {
            return false
        }
        val key = RestrictedChannelUrl.canonicalKey(item.serviceId, item.uploaderUrl) ?: return false
        return allowedKeys.contains(key)
    }

    /**
     * Convenience for call sites that only have the individual fields.
     */
    @JvmStatic
    fun isAllowed(context: Context?, serviceId: Int, uploaderUrl: String?): Boolean =
        RestrictedChannelAccess.canPlayBlocking(context, serviceId, uploaderUrl)
}
