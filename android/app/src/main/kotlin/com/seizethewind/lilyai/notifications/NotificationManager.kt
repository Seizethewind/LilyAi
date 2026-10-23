package com.seizethewind.lilyai.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.seizethewind.lilyai.service.AgentService
import com.seizethewind.lilyai.ui.MainActivity

/** The ongoing notification shown while LilyAi works, with a Stop action. */
class AgentNotifications(private val context: Context) {

    private fun ensureChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Assistant tasks", NotificationManager.IMPORTANCE_LOW)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun build(): Notification {
        ensureChannel()
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            context,
            1,
            Intent(context, AgentService::class.java).setAction(AgentService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("LilyAi is working")
            .setContentText("Tap Stop to cancel.")
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "agent_tasks"
    }
}
