package dev.sn.app.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.AlarmClock
import dev.sn.core.BaseTool
import dev.sn.core.ToolException
import dev.sn.core.intOr
import dev.sn.core.requireString
import dev.sn.core.schema
import dev.sn.core.stringOr
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

class BatteryStatusTool(private val context: Context) : BaseTool(
    name = "battery_status",
    description = "Battery percentage, whether it is charging, and its temperature in degrees Celsius.",
    category = "device",
) {
    override suspend fun call(arguments: JsonObject): JsonElement {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: throw ToolException("Battery state is unavailable.")

        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)

        return ok(
            "percentage" to if (level >= 0 && scale > 0) level * 100 / scale else -1,
            "charging" to (
                status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
                ),
            "full" to (status == BatteryManager.BATTERY_STATUS_FULL),
            "power_source" to when (plugged) {
                BatteryManager.BATTERY_PLUGGED_AC -> "ac"
                BatteryManager.BATTERY_PLUGGED_USB -> "usb"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
                else -> "battery"
            },
            "temperature_celsius" to intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0,
            "health" to when (intent.getIntExtra(BatteryManager.EXTRA_HEALTH, 0)) {
                BatteryManager.BATTERY_HEALTH_GOOD -> "good"
                BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheating"
                BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
                BatteryManager.BATTERY_HEALTH_COLD -> "cold"
                BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over voltage"
                else -> "unknown"
            },
        )
    }
}

class NetworkStatusTool(private val context: Context) : BaseTool(
    name = "network_status",
    description = "How the phone is connected to the network: Wi-Fi, mobile or offline, and " +
        "whether the connection actually works. Useful when the model cannot be reached.",
    category = "device",
) {
    override suspend fun call(arguments: JsonObject): JsonElement {
        val manager = context.getSystemService(ConnectivityManager::class.java)
            ?: throw ToolException("Connectivity service unavailable.")
        val network = manager.activeNetwork
            ?: return ok("connected" to false, "detail" to "No active network.")
        val capabilities = manager.getNetworkCapabilities(network)
            ?: return ok("connected" to false, "detail" to "No network capabilities reported.")

        val transport = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }

        return ok(
            "connected" to true,
            "transport" to transport,
            // Tailscale shows up as a VPN transport, which is the interesting
            // signal when the model is unreachable.
            "vpn_active" to capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
            "internet_validated" to
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            "metered" to !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            "downstream_kbps" to capabilities.linkDownstreamBandwidthKbps,
        )
    }
}

class ClipboardGetTool(private val context: Context) : BaseTool(
    name = "clipboard_get",
    description = "Read the phone's clipboard. Only works while sn is the app on screen — " +
        "Android blocks background clipboard reads.",
    category = "device",
) {
    override suspend fun call(arguments: JsonObject): JsonElement {
        val manager = context.getSystemService(ClipboardManager::class.java)
            ?: throw ToolException("Clipboard service unavailable.")
        val clip = manager.primaryClip
        if (clip == null || clip.itemCount == 0) {
            // Since Android 10 this is what a background read looks like, so
            // say so rather than reporting an empty clipboard as fact.
            throw ToolException(
                "The clipboard is empty, or Android blocked the read because sn is not the " +
                    "app currently on screen. Ask the user to open sn and try again.",
            )
        }
        val text = clip.getItemAt(0).coerceToText(context).toString()
        return ok("text" to text, "length" to text.length)
    }
}

class ClipboardSetTool(private val context: Context) : BaseTool(
    name = "clipboard_set",
    description = "Put text on the phone's clipboard so the user can paste it into another app.",
    parameters = schema { string("text", "Text to place on the clipboard.", required = true) },
    category = "device",
) {
    override suspend fun call(arguments: JsonObject): JsonElement {
        val text = arguments.requireString("text")
        val manager = context.getSystemService(ClipboardManager::class.java)
            ?: throw ToolException("Clipboard service unavailable.")
        manager.setPrimaryClip(ClipData.newPlainText("sn", text))
        return ok("copied" to true, "length" to text.length)
    }
}

class VibrateTool(private val context: Context) : BaseTool(
    name = "vibrate",
    description = "Vibrate the phone briefly, to get the user's attention.",
    parameters = schema { integer("milliseconds", "How long to vibrate (default 400, max 3000).") },
    category = "device",
) {
    @Suppress("DEPRECATION")
    override suspend fun call(arguments: JsonObject): JsonElement {
        val duration = arguments.intOr("milliseconds", 400).coerceIn(1, 3000).toLong()
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            context.getSystemService(Vibrator::class.java)
        } ?: throw ToolException("This device has no vibrator.")

        vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
        return ok("vibrated_ms" to duration)
    }
}

class AppLaunchTool(private val context: Context) : BaseTool(
    name = "app_launch",
    description = "Open an app on the phone by name, for example 'Spotify' or 'Maps'. " +
        "Use app_list first if unsure what is installed.",
    parameters = schema {
        string("name", "The app's name, or part of it.", required = true)
    },
    category = "device",
) {
    override suspend fun call(arguments: JsonObject): JsonElement {
        val wanted = arguments.requireString("name").lowercase()
        val packages = context.packageManager.getInstalledApplications(0)

        val matches = packages.filter { app ->
            val label = context.packageManager.getApplicationLabel(app).toString().lowercase()
            (label.contains(wanted) || app.packageName.lowercase().contains(wanted)) &&
                context.packageManager.getLaunchIntentForPackage(app.packageName) != null
        }
        if (matches.isEmpty()) throw ToolException("No launchable app matching '$wanted'.")

        // Prefer an exact label match before falling back to the first partial.
        val chosen = matches.firstOrNull {
            context.packageManager.getApplicationLabel(it).toString().equals(wanted, ignoreCase = true)
        } ?: matches.first()

        val intent = context.packageManager.getLaunchIntentForPackage(chosen.packageName)
            ?: throw ToolException("'${chosen.packageName}' cannot be launched.")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)

        return ok(
            "launched" to context.packageManager.getApplicationLabel(chosen).toString(),
            "package" to chosen.packageName,
        )
    }
}

class AppListTool(private val context: Context) : BaseTool(
    name = "app_list",
    description = "List the apps installed on the phone that can be opened.",
    parameters = schema { string("query", "Optional filter on the app name.") },
    category = "device",
) {
    override suspend fun call(arguments: JsonObject): JsonElement {
        val filter = arguments.stringOr("query").lowercase()
        val apps = context.packageManager.getInstalledApplications(0)
            .filter { context.packageManager.getLaunchIntentForPackage(it.packageName) != null }
            .map { context.packageManager.getApplicationLabel(it).toString() to it.packageName }
            .filter { filter.isBlank() || it.first.lowercase().contains(filter) }
            .sortedBy { it.first }

        val rows = apps.take(MAX_ROWS).map { ok("name" to it.first, "package" to it.second) }
        return listResult("apps", rows, total = apps.size)
    }
}

class AlarmSetTool(private val context: Context) : BaseTool(
    name = "alarm_set",
    description = "Set an alarm or a countdown timer on the phone's clock app.",
    parameters = schema {
        string("kind", "Whether to set a clock alarm or a countdown timer.", enum = listOf("alarm", "timer"))
        integer("hour", "For an alarm: the hour in 24-hour form, 0 to 23.")
        integer("minute", "For an alarm: the minute, 0 to 59.")
        integer("seconds", "For a timer: how many seconds from now.")
        string("label", "What the alarm is for.")
    },
    category = "device",
    consequential = true,
) {
    override suspend fun call(arguments: JsonObject): JsonElement {
        val label = arguments.stringOr("label", "sn")
        val intent = when (arguments.stringOr("kind", "alarm")) {
            "timer" -> {
                val seconds = arguments.intOr("seconds", 0)
                if (seconds <= 0) throw ToolException("A timer needs a positive number of seconds.")
                Intent(AlarmClock.ACTION_SET_TIMER).apply {
                    putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                    putExtra(AlarmClock.EXTRA_MESSAGE, label)
                    putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                }
            }
            else -> {
                val hour = arguments.intOr("hour", -1)
                val minute = arguments.intOr("minute", 0)
                if (hour !in 0..23) throw ToolException("An alarm needs an hour between 0 and 23.")
                if (minute !in 0..59) throw ToolException("An alarm needs a minute between 0 and 59.")
                Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_HOUR, hour)
                    putExtra(AlarmClock.EXTRA_MINUTES, minute)
                    putExtra(AlarmClock.EXTRA_MESSAGE, label)
                    putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                }
            }
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            throw ToolException("No clock app accepted the request: ${e.message}")
        }
        return ok("set" to true, "label" to label)
    }
}
