package org.schabi.newpipe.restricted

import android.content.Context
import android.util.Log
import org.schabi.newpipe.App
import java.io.File

/**
 * Central access point for the administrator-controlled **comments switch** of this PipePipe
 * fork.
 */
object CommentsModeManager {
    private const val TAG = "CommentsMode"

    /** Name of the sentinel file; the administrator creates/removes exactly this file. */
    const val LOCK_FILE_NAME = ".comments_off.lock"

    /**
     * The primary sentinel: `<app-specific external files dir>/.comments_off.lock`, which the
     * administrator creates over ADB.
     *
     * @return the file handle, or `null` if the app-specific external directory is not available
     *         on this device right now.
     */
    @JvmStatic
    fun externalLockFile(context: Context): File? {
        val dir = context.getExternalFilesDir(null) ?: return null
        return File(dir, LOCK_FILE_NAME)
    }

    /**
     * The fallback sentinel inside the app's private files directory
     * (`/data/data/<applicationId>/files/.comments_off.lock`), usable over `adb shell run-as
     * <applicationId>` on debuggable builds and from a root shell.
     */
    @JvmStatic
    fun internalLockFile(context: Context): File = File(context.filesDir, LOCK_FILE_NAME)

    /**
     * @return `true` while comments are switched off, i.e. while **any** sentinel exists.
     *
     * This always stats the filesystem; the result is never memoized. Any sentinel present means
     * off, so a leftover fallback sentinel cannot silently re-enable comments.
     */
    @JvmStatic
    fun isEnabled(context: Context?): Boolean {
        val ctx: Context? = context ?: App.getApp()
        if (ctx == null) {
            return false
        }
        return try {
            externalLockFile(ctx)?.exists() == true || internalLockFile(ctx).exists()
        } catch (e: SecurityException) {
            Log.e(TAG, "Cannot stat the comments sentinel, keeping comments enabled", e)
            false
        }
    }
}
