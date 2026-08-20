package dev.sn.app.tools

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.sn.app.MainActivity
import dev.sn.app.data.SnDatabase
import dev.sn.app.service.SnNotificationListener
import dev.sn.core.BaseTool
import dev.sn.core.ToolException
import dev.sn.core.intOr
import dev.sn.core.requireString
import dev.sn.core.schema
import dev.sn.core.stringOr
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

object Notifications {
    const val CHANNEL_ALERTS = "sn_alerts"
    const val CHANNEL_SERVICE = "sn_service"
    const val ID_ANSWER = 4001
    const val ID_ALERT = 4002
    const val ID_SERVICE = 4003

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERTS,
                "Answers and alerts",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "Replies from sn, and anything it decides is worth interrupting you for." },
        )
        manager.createNotificationChannel(
            // Low importance: this one exists only because Android requires a
            // visible notification for a foreground service.
            NotificationChannel(
                CHANNEL_SERVICE,
                "Working",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Shown while sn is busy answering." },
        )
    }

    fun post(context: Context, id: Int, title: String, text: String) {
        if (!context.hasPermission(Manifest.permission.POST_NOTIFICATIONS)) return
        ensureChannels(context)

        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()

        context.getSystemService(NotificationManager::class.java)?.notify(id, notification)
    }
}

class NotificationsListTool(
    private val context: Context,
    private val db: SnDatabase,
) : BaseTool(
    name = "notifications_list",
    description = "List notifications the phone has received recently, newest first, with the app " +
        "that posted each one. This is how to answer 'what have I missed'.",
    parameters = schema {
        integer("hours", "How far back to look, in hours (default 12).")
        integer("limit", "How many to return (default 25, maximum 50).")
        string("app", "Optional filter on the app name or package, e.g. 'whatsapp'.")
    },
    category = "notifications",
) {
    override suspend fun call(arguments: JsonObject): JsonElement {
        if (!SnNotificationListener.isEnabled(context)) {
            throw ToolException(
                "sn cannot read notifications yet. The user must grant notification access: " +
                    "Android Settings > Notifications > Device & app notifications > sn. " +
                    "The Setup screen in sn links straight there.",
            )
        }

        val hours = arguments.intOr("hours", 12).coerceIn(1, 24 * 14)
        val limit = arguments.intOr("limit", 25).coerceIn(1, MAX_ROWS)
        val since = System.currentTimeMillis() - hours * 3600_000L
        val app = arguments.stringOr("app").ifBlank { null }

        val rows = db.notifications().since(since, app, limit).map { record ->
            ok(
                "app" to record.appLabel.ifBlank { record.packageName },
                "title" to record.title.ifBlank { null },
                "text" to record.text.ifBlank { null },
                "when" to formatTime(record.postedAt),
                "ongoing" to record.isOngoing,
            )
        }
        return listResult(
            "notifications",
            rows,
            extra = mapOf("window_hours" to kotlinx.serialization.json.JsonPrimitive(hours)),
        )
    }
}

class NotificationSendTool(private val context: Context) : BaseTool(
    name = "notification_send",
    description = "Post a notification to the phone's status bar, so the user sees something later " +
        "even if they are not looking at sn.",
    parameters = schema {
        string("title", "Notification title.", required = true)
        string("text", "Notification body.", required = true)
    },
    category = "notifications",
) {
    override suspend fun call(arguments: JsonObject): JsonElement {
        context.requirePermission(Manifest.permission.POST_NOTIFICATIONS, "post notifications")
        val title = arguments.requireString("title")
        val text = arguments.requireString("text")
        Notifications.post(context, Notifications.ID_ALERT, title, text)
        return ok("posted" to true, "title" to title)
    }
}

class NotificationDismissTool(private val context: Context) : BaseTool(
    name = "notification_dismiss",
    description = "Clear notifications from the status bar. Use this only when the user asks to " +
        "tidy up; dismissing something they have not seen loses it.",
    parameters = schema {
        string("app", "Only dismiss notifications from this app name or package. " +
            "Omit to clear everything dismissible.")
    },
    category = "notifications",
    consequential = true,
) {
    override suspend fun call(arguments: JsonObject): JsonElement {
        val listener = SnNotificationListener.instance
            ?: throw ToolException(
                "sn is not connected to the notification service. Grant notification access " +
                    "in Android Settings, then try again.",
            )
        val app = arguments.stringOr("app").ifBlank { null }
        val cleared = listener.dismiss(app)
        return ok("dismissed" to cleared, "filter" to (app ?: "all"))
    }
}
