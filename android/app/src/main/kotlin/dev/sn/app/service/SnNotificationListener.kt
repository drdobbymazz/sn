package dev.sn.app.service

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.sn.app.data.NotificationEntity
import dev.sn.app.data.SnDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Captures notifications into the local database as they arrive.
 *
 * This is the capability the Termux build could not have at all: Android only
 * exposes the status bar to a bound NotificationListenerService, which needs a
 * special access grant rather than a runtime permission.
 *
 * Nothing here leaves the phone. Notifications are stored locally and only sent
 * to the model when the agent is asked about them, or by the proactive triage
 * pass if the user turned that on.
 */
class SnNotificationListener : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val db by lazy { SnDatabase.get(applicationContext) }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
    }

    override fun onListenerDisconnected() {
        instance = null
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn ?: return
        if (notification.packageName == packageName) return // don't record our own

        val extras = notification.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_TEXT)
            )?.toString().orEmpty()

        // A notification with neither title nor body is a placeholder or an
        // icon-only badge; storing it would only add noise for the model.
        if (title.isBlank() && text.isBlank()) return

        val entity = NotificationEntity(
            key = notification.key,
            packageName = notification.packageName,
            appLabel = appLabel(notification.packageName),
            title = title,
            text = text,
            postedAt = notification.postTime,
            isOngoing = notification.isOngoing,
            isClearable = notification.isClearable,
        )

        scope.launch {
            db.notifications().insert(entity)
            // Keep the table from growing without bound; two weeks is far more
            // history than "what did I miss" ever needs.
            db.notifications().deleteOlderThan(System.currentTimeMillis() - RETENTION_MS)
        }
    }

    private fun appLabel(packageName: String): String = runCatching {
        val info = packageManager.getApplicationInfo(packageName, 0)
        packageManager.getApplicationLabel(info).toString()
    }.getOrDefault(packageName)

    /** Clears dismissible notifications, optionally only from one app. */
    fun dismiss(appFilter: String?): Int {
        val active = runCatching { activeNotifications }.getOrNull() ?: return 0
        var cleared = 0
        for (notification in active) {
            if (!notification.isClearable) continue
            if (appFilter != null) {
                val matches = notification.packageName.contains(appFilter, ignoreCase = true) ||
                    appLabel(notification.packageName).contains(appFilter, ignoreCase = true)
                if (!matches) continue
            }
            cancelNotification(notification.key)
            cleared++
        }
        return cleared
    }

    companion object {
        private const val RETENTION_MS = 14L * 24 * 60 * 60 * 1000

        /** Set while the service is bound; null means access is not granted. */
        @Volatile
        var instance: SnNotificationListener? = null
            private set

        /**
         * Whether the user has granted notification access.
         *
         * Read from the secure setting rather than from [instance], because the
         * service is not bound until shortly after the grant.
         */
        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners",
            ) ?: return false
            val expected = ComponentName(context, SnNotificationListener::class.java)
            return flat.split(':').any { entry ->
                ComponentName.unflattenFromString(entry)?.packageName == expected.packageName
            }
        }

        fun settingsIntent() = android.content.Intent(
            Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS,
        )
    }
}
