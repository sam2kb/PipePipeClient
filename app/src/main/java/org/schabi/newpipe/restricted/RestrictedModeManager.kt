package org.schabi.newpipe.restricted

import android.content.Context
import android.util.Log
import org.schabi.newpipe.App
import java.io.File

/**
 * Central access point for the administrator-controlled **Restricted Mode** of this PipePipe
 * fork.
 */
object RestrictedModeManager {
    private const val TAG = "RestrictedMode"

    /** Name of the sentinel file; the administrator creates/removes exactly this file. */
    const val LOCK_FILE_NAME = ".subscriptions_only.lock"

    /**
     * The primary sentinel: `<app-specific external files dir>/.subscriptions_only.lock`, which the
     * administrator creates over ADB.
     *
     * @return the file handle, or `null` if the app-specific external directory is not available on
     *         this device right now.
     */
    @JvmStatic
    fun lockFile(context: Context): File? {
        val dir = context.getExternalFilesDir(null) ?: return null
        return File(dir, LOCK_FILE_NAME)
    }

    /**
     * The fallback sentinel inside the app's private files directory
     * (`/data/data/<applicationId>/files/.subscriptions_only.lock`).
     */
    @JvmStatic
    fun internalLockFile(context: Context): File = File(context.filesDir, LOCK_FILE_NAME)

    /**
     * @return `true` while Restricted Mode is active, i.e. while **any** sentinel exists.
     *
     * This always stats the filesystem; the result is never memoized. Any sentinel present means
     * restricted, so a leftover fallback sentinel cannot silently unlock the app.
     */
    @JvmStatic
    fun isEnabled(context: Context): Boolean {
        return try {
            lockFile(context)?.exists() == true || internalLockFile(context).exists()
        } catch (e: SecurityException) {
            Log.e(TAG, "Cannot stat the Restricted Mode sentinel, keeping the mode off", e)
            false
        }
    }

    /**
     * Creates the app-specific external files directory if it does not exist yet.
     */
    @JvmStatic
    fun ensureSentinelDirectory(context: Context) {
        try {
            val dir = context.getExternalFilesDir(null)
            if (dir != null && !dir.isDirectory && !dir.mkdirs()) {
                Log.w(TAG, "Could not create the app external files directory: $dir")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not prepare the app external files directory", e)
        }
    }

    /**
     * Convenience overload for call sites that have no `Context` at hand (the play queue is a
     * plain model object). Falls back to the application context held by [App].
     */
    @JvmStatic
    fun isEnabled(): Boolean {
        val app: Context? = App.getApp()
        if (app == null) {
            Log.w(TAG, "Application context unavailable; treating Restricted Mode as off")
            return false
        }
        return isEnabled(app)
    }
}
