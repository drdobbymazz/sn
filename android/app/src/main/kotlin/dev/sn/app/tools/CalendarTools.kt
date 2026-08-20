package dev.sn.app.tools

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import dev.sn.core.BaseTool
import dev.sn.core.ToolException
import dev.sn.core.intOr
import dev.sn.core.requireString
import dev.sn.core.schema
import dev.sn.core.stringOr
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Parses the date formats a model actually produces.
 *
 * Being liberal here is worth it: the alternative is a tool error that the
 * model then has to recover from, which usually costs a whole extra round trip.
 */
internal object When {
    private val FORMATS = listOf(
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd'T'HH:mm",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd HH:mm",
        "yyyy-MM-dd",
    )

    fun parse(raw: String, fallback: Long = System.currentTimeMillis()): Long {
        val text = raw.trim()
        if (text.isEmpty() || text.equals("now", ignoreCase = true)) return fallback

        midnight(text)?.let { return it }

        for (pattern in FORMATS) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }.parse(text)
            }.getOrNull()
            if (parsed != null) return parsed.time
        }
        throw ToolException(
            "Could not read '$raw' as a date. Use ISO format, for example 2026-08-21T14:30, " +
                "or the words today or tomorrow.",
        )
    }

    private fun midnight(text: String): Long? {
        val offsetDays = when (text.lowercase()) {
            "today" -> 0
            "tomorrow" -> 1
            "yesterday" -> -1
            else -> return null
        }
        return Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, offsetDays)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}

class CalendarListTool(private val context: Context) : BaseTool(
    name = "calendar_list_events",
    description = "List calendar events in a time window. Use this for what is coming up, whether " +
        "a time is free, or when something is scheduled.",
    parameters = schema {
        string("start", "When the window starts: an ISO date or datetime, or 'today'/'tomorrow'. Defaults to now.")
        integer("days", "How many days the window covers (default 7).")
    },
    category = "calendar",
) {
    override suspend fun call(arguments: JsonObject): JsonElement {
        context.requirePermission(Manifest.permission.READ_CALENDAR, "read your calendar")

        val begin = When.parse(arguments.stringOr("start", "now"))
        val days = arguments.intOr("days", 7).coerceIn(1, 365)
        val end = begin + days * 86_400_000L

        // Instances expands recurring events, which Events does not.
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(begin.toString())
            .appendPath(end.toString())
            .build()

        val rows = context.query(
            uri = uri,
            projection = arrayOf(
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.EVENT_LOCATION,
                CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
            ),
            sortOrder = "${CalendarContract.Instances.BEGIN} ASC",
            limit = MAX_ROWS,
        ) { cursor ->
            val allDay = cursor.intOrZero(CalendarContract.Instances.ALL_DAY) == 1
            ok(
                "title" to cursor.stringOrEmpty(CalendarContract.Instances.TITLE)
                    .ifBlank { "(no title)" },
                "start" to formatTime(cursor.longOrZero(CalendarContract.Instances.BEGIN)),
                "end" to formatTime(cursor.longOrZero(CalendarContract.Instances.END)),
                "all_day" to allDay,
                "location" to cursor.stringOrEmpty(CalendarContract.Instances.EVENT_LOCATION)
                    .ifBlank { null },
                "calendar" to cursor.stringOrEmpty(CalendarContract.Instances.CALENDAR_DISPLAY_NAME)
                    .ifBlank { null },
            )
        }

        return listResult(
            "events",
            rows,
            extra = mapOf(
                "window" to kotlinx.serialization.json.JsonPrimitive(
                    "${formatTime(begin)} to ${formatTime(end)}",
                ),
            ),
        )
    }
}

class CalendarCreateTool(private val context: Context) : BaseTool(
    name = "calendar_create_event",
    description = "Create a calendar event. The user confirms the details before it is saved.",
    parameters = schema {
        string("title", "Event title.", required = true)
        string("start", "Start time as an ISO datetime, e.g. 2026-08-21T14:30.", required = true)
        integer("duration_minutes", "How long the event lasts (default 60).")
        string("location", "Optional location.")
        string("description", "Optional notes for the event body.")
    },
    category = "calendar",
    consequential = true,
) {
    override suspend fun call(arguments: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        context.requirePermission(Manifest.permission.WRITE_CALENDAR, "add calendar events")

        val title = arguments.requireString("title")
        val begin = When.parse(arguments.requireString("start"))
        val minutes = arguments.intOr("duration_minutes", 60).coerceIn(1, 60 * 24 * 7)
        val end = begin + minutes * 60_000L

        val calendarId = primaryCalendarId()
            ?: throw ToolException(
                "No writable calendar found on this phone. Add an account in the Calendar app first.",
            )

        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, begin)
            put(CalendarContract.Events.DTEND, end)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            arguments.stringOr("location").ifBlank { null }
                ?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            arguments.stringOr("description").ifBlank { null }
                ?.let { put(CalendarContract.Events.DESCRIPTION, it) }
        }

        val uri = try {
            context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
        } catch (e: SecurityException) {
            throw ToolException("Android refused to add the event: ${e.message}")
        } ?: throw ToolException("The calendar provider rejected the event.")

        ok(
            "created" to true,
            "id" to ContentUris.parseId(uri),
            "title" to title,
            "start" to formatTime(begin),
            "end" to formatTime(end),
        )
    }

    /** The account's own calendar, preferring one marked primary and writable. */
    private suspend fun primaryCalendarId(): Long? {
        val candidates = context.query(
            uri = CalendarContract.Calendars.CONTENT_URI,
            projection = arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.IS_PRIMARY,
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
                CalendarContract.Calendars.VISIBLE,
            ),
            limit = 50,
        ) { cursor ->
            Triple(
                cursor.longOrZero(CalendarContract.Calendars._ID),
                cursor.intOrZero(CalendarContract.Calendars.IS_PRIMARY) == 1,
                cursor.intOrZero(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL),
            )
        }

        val writable = candidates.filter {
            it.third >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR
        }
        return (writable.firstOrNull { it.second } ?: writable.firstOrNull())?.first
    }
}
