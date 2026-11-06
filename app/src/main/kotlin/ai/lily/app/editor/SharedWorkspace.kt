package ai.lily.app.editor

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import java.io.File

/**
 * The folder the person's own files live in, shared with Termux: Documents/Lily/projects on the phone's storage. Everything with a
 * secret or app state (settings, plugins, drafts, tasks, remote copies) stays in the app's private folder; only the workspace moves here.
 * Without the permission the app keeps using its private workspace, so nothing is lost and nothing stops working.
 */
object SharedWorkspace {
    fun folder(): File = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "Lily/projects")

    /** Android 11 and later: "All files access". Android 8 to 9: the storage permission. */
    fun allowed(context: Context): Boolean = when {
        Build.VERSION.SDK_INT >= 30 -> Environment.isExternalStorageManager()
        // shortcut: Android 10 with targetSdk 35 gives no plain file access to shared storage, so it stays private, no upgrade planned.
        Build.VERSION.SDK_INT >= 29 -> false
        else -> context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    /** The shared folder when the permission is given and the folder can be made, otherwise [private]. The flag says which one it is. */
    fun choose(context: Context, private: File): Pair<File, Boolean> {
        if (!allowed(context)) return private to false
        val shared = folder()
        return if (shared.isDirectory || shared.mkdirs()) shared to true else private to false
    }

    /** The Android 11+ screen where the person turns on "All files access" for this app. */
    fun settingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))

    /**
     * Copies every file of [from] that [to] does not have yet. Nothing is overwritten and [from] is left as it was.
     * Returns how many files were copied and how many failed.
     */
    fun copyMissing(from: File, to: File): Pair<Int, Int> {
        if (!from.isDirectory) return 0 to 0
        var copied = 0
        var failed = 0
        for (file in from.walkTopDown().filter { it.isFile }) {
            val dest = File(to, file.relativeTo(from).path)
            if (dest.exists()) continue
            val ok = runCatching {
                dest.parentFile?.mkdirs()
                file.copyTo(dest)
            }.isSuccess
            if (ok) copied++ else failed++
        }
        return copied to failed
    }
}
