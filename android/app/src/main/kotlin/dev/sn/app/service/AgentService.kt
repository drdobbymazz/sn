package dev.sn.app.service

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.sn.app.tools.Notifications

/**
 * Keeps a turn alive while the screen is off.
 *
 * One UI is aggressive about freezing background work, and a generation that
 * takes thirty seconds will otherwise be cut off mid-answer when the screen
 * locks. The service holds no logic of its own — it exists purely so Android
 * leaves the process alone until the answer is finished.
 */
class AgentService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val label = intent?.getStringExtra(EXTRA_LABEL) ?: "Thinking…"
        startForegroundCompat(label)
        return START_NOT_STICKY
    }

    private fun startForegroundCompat(label: String) {
        Notifications.ensureChannels(this)
        val notification: Notification =
            NotificationCompat.Builder(this, Notifications.CHANNEL_SERVICE)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("sn")
                .setContentText(label)
                .setOngoing(true)
                .setSilent(true)
                .build()

        ServiceCompat.startForeground(
            this,
            Notifications.ID_SERVICE,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    companion object {
        private const val EXTRA_LABEL = "label"

        fun start(context: Context, label: String = "Thinking…") {
            runCatching {
                context.startForegroundService(
                    Intent(context, AgentService::class.java).putExtra(EXTRA_LABEL, label),
                )
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, AgentService::class.java)) }
        }
    }
}
