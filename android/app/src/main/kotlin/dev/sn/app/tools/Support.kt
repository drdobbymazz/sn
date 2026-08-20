package dev.sn.app.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import dev.sn.core.ToolException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Caps how much any one tool can return, so a single call cannot flood the context. */
const val MAX_ROWS = 50

private val TIMESTAMP = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

fun formatTime(millis: Long): String = TIMESTAMP.format(Date(millis))

/**
 * Checks a permission and fails with something the model can relay usefully.
 *
 * The wording matters: the user reads this through the agent, and "open
 * Settings and grant Contacts" is actionable where "SecurityException" is not.
 */
fun Context.requirePermission(permission: String, forWhat: String) {
    if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
        val label = permission.substringAfterLast('.')
        throw ToolException(
            "sn does not have permission to $forWhat ($label). Open sn's Setup screen, " +
                "or Android Settings > Apps > sn > Permissions, and grant it.",
        )
    }
}

fun Context.hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

/** True when the app can read arbitrary files, not just the media store. */
fun hasAllFilesAccess(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

/**
 * Runs a content provider query on the IO dispatcher and maps each row.
 *
 * Wraps the SecurityException that a revoked permission throws, which would
 * otherwise surface as an opaque crash inside a tool.
 */
suspend fun <T> Context.query(
    uri: Uri,
    projection: Array<String>,
    selection: String? = null,
    selectionArgs: Array<String>? = null,
    sortOrder: String? = null,
    limit: Int = MAX_ROWS,
    map: (Cursor) -> T,
): List<T> = withContext(Dispatchers.IO) {
    val rows = mutableListOf<T>()
    try {
        contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
            while (cursor.moveToNext() && rows.size < limit) {
                rows += map(cursor)
            }
        }
    } catch (e: SecurityException) {
        throw ToolException(
            "Android refused the request: ${e.message}. The matching permission is probably " +
                "not granted — check sn's Setup screen.",
        )
    }
    rows
}

fun Cursor.stringOrEmpty(column: String): String {
    val index = getColumnIndex(column)
    return if (index >= 0 && !isNull(index)) getString(index).orEmpty() else ""
}

fun Cursor.longOrZero(column: String): Long {
    val index = getColumnIndex(column)
    return if (index >= 0 && !isNull(index)) getLong(index) else 0L
}

fun Cursor.intOrZero(column: String): Int {
    val index = getColumnIndex(column)
    return if (index >= 0 && !isNull(index)) getInt(index) else 0
}

// -- building results -----------------------------------------------------

fun jsonList(items: List<JsonElement>): JsonArray = JsonArray(items)

/**
 * Standard shape for a list-returning tool.
 *
 * Always reporting the true total alongside a truncated page stops the model
 * from concluding there are only [MAX_ROWS] messages in existence.
 */
fun listResult(
    key: String,
    rows: List<JsonElement>,
    total: Int = rows.size,
    extra: Map<String, JsonElement> = emptyMap(),
): JsonObject = buildJsonObject {
    put("count", total)
    if (total > rows.size) put("truncated", true)
    extra.forEach { (name, value) -> put(name, value) }
    put(key, JsonArray(rows))
}

fun ok(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
    pairs.forEach { (key, value) ->
        when (value) {
            null -> {}
            is String -> put(key, value)
            is Int -> put(key, value)
            is Long -> put(key, value)
            is Double -> put(key, value)
            is Float -> put(key, value.toDouble())
            is Boolean -> put(key, value)
            is JsonElement -> put(key, value)
            else -> put(key, value.toString())
        }
    }
}

fun JsonObject.orNull(key: String, value: String?): JsonObject =
    if (value.isNullOrBlank()) this else JsonObject(this + (key to JsonPrimitive(value)))

// -- phone numbers --------------------------------------------------------

/**
 * Reduce a phone number to comparable digits.
 *
 * +44 7700 900123, 07700 900123 and 7700900123 should all match each other,
 * which is as precise as contact matching actually needs to be.
 */
fun normalizeNumber(raw: String): String {
    val digits = raw.filter { it.isDigit() }
    return if (digits.length > 9) digits.takeLast(9) else digits
}

private val NUMBER_SHAPE = Regex("""[+\d][\d\s\-().]{4,}""")

fun looksLikePhoneNumber(value: String): Boolean = NUMBER_SHAPE.matches(value.trim())

/** Permission groups, named once so the Setup screen and the tools agree. */
object Permissions {
    val MESSAGING = listOf(
        Manifest.permission.READ_SMS,
        Manifest.permission.SEND_SMS,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.CALL_PHONE,
    )
    val CALENDAR = listOf(
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.WRITE_CALENDAR,
    )
    val SENSING = listOf(
        Manifest.permission.CAMERA,
        Manifest.permission.ACCESS_FINE_LOCATION,
    )
    val NOTIFICATIONS = listOf(Manifest.permission.POST_NOTIFICATIONS)

    val MEDIA: List<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        listOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO,
        )
    } else {
        listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    val ALL: List<String> = MESSAGING + CALENDAR + SENSING + NOTIFICATIONS + MEDIA
}
