package com.seizethewind.lilyai.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.seizethewind.lilyai.LilyAiApplication

/**
 * Keeps LilyAi alive while it works on a longer request, shows the ongoing notification and
 * the floating Stop button. Started only when a request takes more than a couple of seconds.
 */
class AgentService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as LilyAiApplication
        if (intent?.action == ACTION_STOP) {
            app.agent.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        val notification = app.notifications.build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        if (app.settings.showStopOverlay) app.overlay.show()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        (application as LilyAiApplication).overlay.hide()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.seizethewind.lilyai.STOP"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, AgentService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AgentService::class.java))
        }
    }
}
