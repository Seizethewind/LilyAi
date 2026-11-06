package ai.lily.app.workspace

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import java.io.File

/**
 * Where the editor's workspace lives. By default it is the app's private `workspace` folder, which no other app can see. When the
 * person chooses a folder in Settings, it is `<folder>/lilyai` instead, so Termux and the person's file manager see the same files.
 *
 * Only the folder's path is kept here. Nothing secret is: settings, keys, plugins and drafts stay in the app's private storage
 * whichever workspace is in use. When the chosen folder cannot be used (no file access, folder gone), the private workspace is used
 * and Settings says so.
 */
class WorkspaceLocation(context: Context) {
    private val sp = context.getSharedPreferences("workspace_location", Context.MODE_PRIVATE)

    /** The folder the person chose, with the `lilyai` folder inside it. Blank means the private workspace. */
    var folder: String
        get() = sp.getString("folder", "").orEmpty()
        set(value) {
            sp.edit().putString("folder", value.trim()).apply()
        }

    /** `<folder>/lilyai` when a folder is chosen and a file can really be written in it, otherwise null. */
    fun sharedRoot(): File? {
        val raw = folder.takeIf { it.isNotBlank() } ?: return null
        val dir = File(WorkspaceSetup.baseFolder(raw), WorkspaceSetup.ROOT_NAME)
        return dir.takeIf { (it.isDirectory || it.mkdirs()) && canWriteIn(it) }
    }

    /** The workspace root to open: the shared one if usable, else the private one. */
    fun root(filesDir: File): File = sharedRoot() ?: File(filesDir, "workspace").apply { mkdirs() }

    /** File.canWrite() can say yes while Android blocks the write, so a real, throwaway file is written. */
    private fun canWriteIn(dir: File): Boolean = runCatching { File.createTempFile(".lily-", ".tmp", dir).delete() }.getOrDefault(false)

    companion object {
        private const val STORAGE_REQUEST = 7

        /** Where the setup suggests putting the `lilyai` folder. */
        fun defaultFolder(): String = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).path

        /** Whether Android lets the app write to shared storage: All files access on Android 11 and later, the storage permission before. */
        fun accessGranted(context: Context): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            }

        /** Opens the screen where the person allows it. They come back and use the button again. */
        fun askAccess(activity: Activity) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val uri = Uri.parse("package:${activity.packageName}")
                try {
                    activity.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, uri))
                } catch (e: ActivityNotFoundException) {
                    activity.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            } else {
                activity.requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), STORAGE_REQUEST)
            }
        }
    }
}
