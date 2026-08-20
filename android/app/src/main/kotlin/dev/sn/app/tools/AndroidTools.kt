package dev.sn.app.tools

import android.content.Context
import dev.sn.app.data.SnDatabase
import dev.sn.core.Tool
import dev.sn.core.ToolRegistry

/**
 * Everything sn can do on the phone.
 *
 * This is the whole capability surface in one list, which is the point: adding
 * a tool means adding a line here, and auditing what the agent can reach means
 * reading one function.
 *
 * Note what is absent. There is no shell tool and no accessibility service, so
 * the agent cannot run arbitrary commands or drive other apps' interfaces. Each
 * capability below is a named, bounded operation, which is what makes the
 * confirmation list meaningful.
 */
fun buildToolRegistry(context: Context, db: SnDatabase): ToolRegistry {
    val application = context.applicationContext
    return ToolRegistry(
        listOf<Tool>(
            // messaging
            ContactsFindTool(application),
            SmsListTool(application),
            SmsSendTool(application),
            CallLogTool(application),
            CallPlaceTool(application),

            // notifications
            NotificationsListTool(application, db),
            NotificationSendTool(application),
            NotificationDismissTool(application),

            // calendar
            CalendarListTool(application),
            CalendarCreateTool(application),

            // files
            FilesListTool(application),
            FilesFindTool(application),
            FilesReadTool(application),

            // device
            BatteryStatusTool(application),
            NetworkStatusTool(application),
            ClipboardGetTool(application),
            ClipboardSetTool(application),
            VibrateTool(application),
            AppLaunchTool(application),
            AppListTool(application),
            AlarmSetTool(application),

            // sensing
            LocationTool(application),
            CameraInfoTool(application),
            CameraPhotoTool(application),
        ),
    )
}
