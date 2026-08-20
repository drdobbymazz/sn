package dev.sn.app.tools

import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import dev.sn.core.BaseTool
import dev.sn.core.ToolException
import dev.sn.core.boolOr
import dev.sn.core.intOr
import dev.sn.core.requireString
import dev.sn.core.schema
import dev.sn.core.stringOr
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Where the file tools are allowed to look.
 *
 * Confining reads to shared storage keeps the agent out of other apps' private
 * data and out of sn's own database. Paths are canonicalised before the check,
 * so `..` and symlinks cannot walk out.
 */
private object Sandbox {
    fun roots(): List<File> = listOfNotNull(
        Environment.getExternalStorageDirectory(),
    ).filter { it.exists() }

    fun resolve(path: String): File {
        if (path.isBlank()) throw ToolException("No path given.")
        val candidate = if (path.startsWith("/")) {
            File(path)
        } else {
            File(Environment.getExternalStorageDirectory(), path)
        }

        val canonical = try {
            candidate.canonicalFile
        } catch (e: Exception) {
            throw ToolException("Cannot resolve '$path': ${e.message}")
        }

        val allowed = roots().any { root ->
            val prefix = root.canonicalPath
            canonical.canonicalPath == prefix || canonical.canonicalPath.startsWith("$prefix/")
        }
        if (!allowed) {
            throw ToolException(
                "'$path' is outside the storage sn may read. Allowed: " +
                    roots().joinToString(", ") { it.absolutePath },
            )
        }
        return canonical
    }
}

private fun File.describe(): JsonObject = ok(
    "name" to name,
    "path" to absolutePath,
    "type" to if (isDirectory) "dir" else "file",
    "size" to if (isFile) length() else 0L,
    "modified" to formatTime(lastModified()),
)

private fun noAccessHint(): String =
    "Reading files also needs All files access, which Android only grants by hand: " +
        "Settings > Apps > sn > Permissions > Files and media > Allow management of all files. " +
        "Without it, only media (photos, video, audio) can be found."

class FilesListTool(private val context: Context) : BaseTool(
    name = "files_list",
    description = "List the contents of a folder in shared storage. Common folders are Download, " +
        "DCIM, Documents and Pictures.",
    parameters = schema {
        string("path", "Folder to list, relative to shared storage or absolute. Defaults to the root.")
    },
    category = "files",
) {
    override suspend fun call(arguments: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        val target = Sandbox.resolve(arguments.stringOr("path", "/").ifBlank { "/" })
        if (!target.exists()) throw ToolException("No such folder: ${target.absolutePath}")
        if (!target.isDirectory) throw ToolException("${target.absolutePath} is a file, not a folder.")

        val entries = target.listFiles()
            ?: throw ToolException("Android denied access to ${target.absolutePath}. ${noAccessHint()}")

        val sorted = entries.sortedWith(compareBy({ it.isFile }, { it.name.lowercase() }))
        listResult("entries", sorted.take(MAX_ROWS).map { it.describe() }, total = sorted.size)
    }
}

class FilesFindTool(private val context: Context) : BaseTool(
    name = "files_find",
    description = "Search for files by name across the phone's storage. Use this when the user " +
        "refers to a file but does not know where it is.",
    parameters = schema {
        string("name", "Part of the file name to look for, e.g. 'invoice' or '.pdf'.", required = true)
        integer("limit", "Maximum matches to return (default 25).")
    },
    category = "files",
) {
    override suspend fun call(arguments: JsonObject): JsonElement {
        val needle = arguments.requireString("name")
        val limit = arguments.intOr("limit", 25).coerceIn(1, MAX_ROWS)

        // The media store index covers everything scanned, is fast, and works
        // without All files access. It is the right first stop.
        val rows = context.query(
            uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
            projection = arrayOf(
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.RELATIVE_PATH,
                MediaStore.Files.FileColumns.SIZE,
                MediaStore.Files.FileColumns.DATE_MODIFIED,
                MediaStore.Files.FileColumns.MIME_TYPE,
            ),
            selection = "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?",
            selectionArgs = arrayOf("%$needle%"),
            sortOrder = "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC",
            limit = limit,
        ) { cursor ->
            val relative = cursor.stringOrEmpty(MediaStore.Files.FileColumns.RELATIVE_PATH)
            val name = cursor.stringOrEmpty(MediaStore.Files.FileColumns.DISPLAY_NAME)
            ok(
                "name" to name,
                "path" to (Environment.getExternalStorageDirectory().absolutePath + "/" + relative + name),
                "size" to cursor.longOrZero(MediaStore.Files.FileColumns.SIZE),
                "modified" to formatTime(
                    cursor.longOrZero(MediaStore.Files.FileColumns.DATE_MODIFIED) * 1000L,
                ),
                "type" to cursor.stringOrEmpty(MediaStore.Files.FileColumns.MIME_TYPE).ifBlank { null },
            )
        }

        val extra = if (rows.isEmpty() && !hasAllFilesAccess()) {
            mapOf("note" to kotlinx.serialization.json.JsonPrimitive(noAccessHint()))
        } else {
            emptyMap()
        }
        return listResult("matches", rows, extra = extra)
    }
}

class FilesReadTool(private val context: Context) : BaseTool(
    name = "files_read",
    description = "Read a text file from the phone. Large files are truncated and binary files are " +
        "reported rather than dumped.",
    parameters = schema {
        string("path", "Full path to the file, as returned by files_find or files_list.", required = true)
        integer("max_bytes", "How much to read (default 100000).")
        boolean("tail", "Read the end of the file instead of the start.")
    },
    category = "files",
) {
    override suspend fun call(arguments: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        val target = Sandbox.resolve(arguments.requireString("path"))
        if (!target.exists()) throw ToolException("No such file: ${target.absolutePath}")
        if (target.isDirectory) throw ToolException("${target.absolutePath} is a folder — use files_list.")
        if (!target.canRead()) throw ToolException("Cannot read ${target.absolutePath}. ${noAccessHint()}")

        val cap = arguments.intOr("max_bytes", 100_000).coerceIn(1, 200_000)
        val size = target.length()
        val tail = arguments.boolOr("tail", false)

        val bytes = try {
            target.inputStream().use { stream ->
                if (tail && size > cap) stream.skip(size - cap)
                // Read by hand rather than with readNBytes, which needs API 33
                // and would not run on every device this app supports.
                val buffer = ByteArray(cap)
                var filled = 0
                while (filled < cap) {
                    val read = stream.read(buffer, filled, cap - filled)
                    if (read <= 0) break
                    filled += read
                }
                buffer.copyOf(filled)
            }
        } catch (e: Exception) {
            throw ToolException("Could not read ${target.name}: ${e.message}")
        }

        // A NUL byte early on is the usual signal that this is not text.
        if (bytes.take(4096).contains(0.toByte())) {
            return@withContext ok(
                "path" to target.absolutePath,
                "binary" to true,
                "size" to size,
                "note" to "Binary file — not decoded. Ask the user what to do with it.",
            )
        }

        ok(
            "path" to target.absolutePath,
            "size" to size,
            "truncated" to (size > bytes.size),
            "read_from" to if (tail) "end" else "start",
            "text" to String(bytes, Charsets.UTF_8),
        )
    }
}
