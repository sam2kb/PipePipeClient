package org.schabi.newpipe.restricted

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import org.schabi.newpipe.App
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.util.Locale

/**
 * The central authorization helper of Restricted Mode.
 */
object RestrictedChannelAccess {
    private const val TAG = "RestrictedChannelAccess"

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Timestamp of the last "blocked" toast, so a queue of denials does not spam the screen. */
    @Volatile
    private var lastToastAt = 0L

    // ------------------------------------------------------------------ Mode

    @JvmStatic
    fun isRestricted(context: Context?): Boolean {
        val ctx: Context? = context ?: App.getApp()
        if (ctx == null) {
            return false
        }
        return RestrictedModeManager.isEnabled(ctx)
    }

    // --------------------------------------------------- Subscription lookup

    /**
     * @return every canonical channel key that is currently subscribed for [serviceId].
     *         An empty set on any error, which denies.
     */
    @JvmStatic
    fun allowedKeysBlocking(context: Context?, serviceId: Int): Set<String> {
        val ctx: Context? = context ?: App.getApp()
        if (ctx == null) {
            return emptySet()
        }
        return try {
            NewPipeDatabase.getInstance(ctx.applicationContext)
                .subscriptionDAO()
                .subscriptionUrlsByService(serviceId)
                .blockingGet()
                .mapNotNullTo(HashSet()) { RestrictedChannelUrl.canonicalKey(serviceId, it) }
        } catch (e: Exception) {
            Log.e(TAG, "Subscriptions lookup failed, denying everything for service $serviceId", e)
            emptySet()
        }
    }

    /**
     * @return `true` when Restricted Mode is off, or when the channel behind [channelUrl] is
     *         currently subscribed for [serviceId].
     */
    @JvmStatic
    fun isSubscribed(context: Context?, serviceId: Int, channelUrl: String?): Single<Boolean> {
        if (!isRestricted(context)) {
            return Single.just(true)
        }

        val key = RestrictedChannelUrl.canonicalKey(serviceId, channelUrl)
            ?: return Single.just(false) // uncertain identity -> deny
        val ctx: Context? = context ?: App.getApp()
        if (ctx == null) {
            return Single.just(false)
        }

        return Single.fromCallable { isCanonicalKeySubscribed(context, serviceId, key) }
            .subscribeOn(Schedulers.io())
            .onErrorReturnItem(false)
    }

    /** Blocking form of [isSubscribed]. */
    @JvmStatic
    fun isSubscribedBlocking(context: Context?, serviceId: Int, channelUrl: String?): Boolean {
        if (!isRestricted(context)) {
            return true
        }
        val key = RestrictedChannelUrl.canonicalKey(serviceId, channelUrl) ?: return false
        return isCanonicalKeySubscribed(context, serviceId, key)
    }

    /**
     * The two-step lookup, always sequential so that it can never block a Room executor thread
     * while waiting for another query on that same executor.
     */
    private fun isCanonicalKeySubscribed(
        context: Context?,
        serviceId: Int,
        canonicalKey: String,
    ): Boolean {
        val ctx: Context? = context ?: App.getApp()
        if (ctx == null) {
            return false
        }
        val dao = try {
            NewPipeDatabase.getInstance(ctx.applicationContext).subscriptionDAO()
        } catch (e: Exception) {
            Log.e(TAG, "Subscriptions database unavailable, denying", e)
            return false
        }

        // Fast path: an exact match on the indexed (service_id, url) pair. A hit is decisive,
        // because a stored row equal to the canonical key is by definition the canonical URL.
        try {
            if (dao.isSubscribed(serviceId, canonicalKey).blockingGet()) {
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Subscription lookup failed, denying", e)
            return false
        }

        // The stored row may be a non-canonical variant of the same channel (an imported
        // subscription, a legacy URL shape, a trailing slash, …), so compare canonicalised rows
        // before denying.
        return allowedKeysBlocking(ctx, serviceId).contains(canonicalKey)
    }

    /** Alias kept for the wording used by the design document. */
    @JvmStatic
    fun canOpenChannel(
        context: Context?,
        serviceId: Int,
        channelUrl: String?,
    ): Single<Boolean> = isSubscribed(context, serviceId, channelUrl)

    // -------------------------------------- Stream / queue item authorization

    /**
     * Authoritative check for a resolved stream. A [StreamInfo] without a usable uploader URL is
     * denied: the identity of its channel is unknown, so it cannot be authorized.
     */
    @JvmStatic
    fun canPlayStream(context: Context?, info: StreamInfo?): Single<Boolean> {
        if (!isRestricted(context)) {
            return Single.just(true)
        }
        if (info == null) {
            return Single.just(false)
        }
        return isSubscribed(context, info.serviceId, info.uploaderUrl)
    }

    /**
     * Authoritative check for a queue item.
     */
    @JvmStatic
    fun canPlay(
        context: Context?,
        serviceId: Int,
        uploaderUrl: String?,
    ): Single<Boolean> = isSubscribed(context, serviceId, uploaderUrl)

    /** Blocking form of [canPlay]. */
    @JvmStatic
    fun canPlayBlocking(context: Context?, serviceId: Int, uploaderUrl: String?): Boolean {
        if (!isRestricted(context)) {
            return true
        }
        val key = RestrictedChannelUrl.canonicalKey(serviceId, uploaderUrl) ?: return false
        return allowedKeysBlocking(context, serviceId).contains(key)
    }

    // ------------------------------------------------------- Result filtering

    /**
     * Returns the entries of [items] that belong to a subscribed channel, in their original
     * order.
     *
     * @return the surviving entries, or all of [items] when Restricted Mode is off
     */
    @JvmStatic
    fun allowedItems(
        context: Context?,
        serviceId: Int,
        items: List<out InfoItem>?,
    ): List<InfoItem> {
        if (items.isNullOrEmpty()) {
            return emptyList()
        }
        if (!isRestricted(context)) {
            // The caller only ever reads the result; a copy keeps the signature simple.
            return items.toList()
        }
        val allowedKeys = allowedKeysBlocking(context, serviceId)
        val allowedNames = allowedNamesBlocking(context, serviceId)
        return items.filter { item -> isItemAllowed(serviceId, allowedKeys, allowedNames, item) }
    }

    /**
     * Names of the subscribed channels of a service, normalised for comparison.
     *
     * @return an empty set on any error, which denies
     */
    @JvmStatic
    fun allowedNamesBlocking(context: Context?, serviceId: Int): Set<String> {
        val ctx: Context? = context ?: App.getApp()
        if (ctx == null) {
            return emptySet()
        }
        return try {
            NewPipeDatabase.getInstance(ctx.applicationContext)
                .subscriptionDAO()
                .subscriptionNamesByService(serviceId)
                .blockingGet()
                .mapNotNullTo(HashSet()) { name -> normalizeName(name) }
        } catch (e: Exception) {
            Log.e(TAG, "Subscription name lookup failed, denying everything for service $serviceId",
                e)
            emptySet()
        }
    }

    /** Channel names are compared case-insensitively and without surrounding whitespace. */
    private fun normalizeName(name: String?): String? =
        name?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }

    /**
     * Whether a single result entry belongs to a subscribed channel. The channel ID decides
     * whenever the entry carries one.
     */
    private fun isItemAllowed(
        serviceId: Int,
        allowedKeys: Set<String>,
        allowedNames: Set<String>,
        item: InfoItem,
    ): Boolean {
        val channelUrl = when (item) {
            is StreamInfoItem -> item.uploaderUrl
            is ChannelInfoItem -> item.url
            else -> null
        }
        val key = RestrictedChannelUrl.canonicalKey(serviceId, channelUrl)
        if (key != null && allowedKeys.contains(key)) {
            return true
        }

        val channelName = when (item) {
            is StreamInfoItem -> item.uploaderName
            is ChannelInfoItem -> item.name
            is PlaylistInfoItem -> item.uploaderName
            else -> null
        }
        return normalizeName(channelName)?.let { allowedNames.contains(it) } ?: false
    }

    // --------------------------------------------------------- User feedback

    /** Shows the standard "not from a subscribed channel" message, rate-limited. */
    @JvmStatic
    fun notifyVideoBlocked(context: Context?) {
        notifyBlocked(context, R.string.restricted_mode_video_blocked)
    }

    /** Shows the standard "search is disabled" message, rate-limited. */
    @JvmStatic
    fun notifySearchBlocked(context: Context?) {
        notifyBlocked(context, R.string.restricted_mode_search_blocked)
    }

    /** Shows the "results are limited to subscribed channels" message, rate-limited. */
    @JvmStatic
    fun notifyResultsFiltered(context: Context?) {
        notifyBlocked(context, R.string.restricted_mode_results_filtered)
    }

    /** Shows the standard "subscriptions are read-only" message, rate-limited. */
    @JvmStatic
    fun notifySubscriptionsReadOnly(context: Context?) {
        notifyBlocked(context, R.string.restricted_mode_subscriptions_read_only)
    }

    /** Shows the standard "channel not subscribed" message, rate-limited. */
    @JvmStatic
    fun notifyChannelBlocked(context: Context?) {
        notifyBlocked(context, R.string.restricted_mode_channel_blocked)
    }

    /**
     * Shows [messageRes] on the main thread.
     */
    @JvmStatic
    @JvmOverloads
    fun notifyBlocked(context: Context?, messageRes: Int = R.string.restricted_mode_video_blocked) {
        val ctx: Context? = context ?: App.getApp()
        if (ctx == null) {
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastToastAt < TOAST_MIN_INTERVAL_MS) {
            return
        }
        lastToastAt = now
        mainHandler.post {
            try {
                Toast.makeText(ctx.applicationContext, messageRes, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.w(TAG, "Could not show the Restricted Mode message", e)
            }
        }
    }

    private const val TOAST_MIN_INTERVAL_MS = 1500L
}
