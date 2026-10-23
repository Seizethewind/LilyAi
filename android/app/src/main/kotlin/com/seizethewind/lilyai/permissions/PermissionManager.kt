package com.seizethewind.lilyai.permissions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/** Checks the special permissions the app asks for. The user grants them in Android's settings. */
class PermissionManager(private val context: Context) {

    fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(context)

    fun overlayPermissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + context.packageName))

    /** Always true below Android 13, where notifications need no permission. */
    fun notificationsGranted(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
}
