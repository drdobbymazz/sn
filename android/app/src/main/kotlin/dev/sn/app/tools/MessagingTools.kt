package dev.sn.app.tools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import dev.sn.core.BaseTool
import dev.sn.core.ToolException
import dev.sn.core.intOr
import dev.sn.core.requireString
import dev.sn.core.schema
import dev.sn.core.stringOr
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** name + number, as read from the contacts provider. */
private data class Contact(val name: String, val number: String)

private suspend fun Context.contacts(query: String = ""): List<Contact> {
    requirePermission(Manifest.permission.READ_CONTACTS, "read your contacts")
    val selection = if (query.isBlank()) null else "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
    val arguments = if (query.isBlank()) null else arrayOf("%$query%")

    return query(
        uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
        projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        ),
        selection = selection,
        selectionArgs = arguments,
        sortOrder = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
        limit = 500,
    ) { cursor ->
        Contact(
            name = cursor.stringOrEmpty(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME),
            number = cursor.stringOrEmpty(ContactsContract.CommonDataKinds.Phone.NUMBER),
        )
    }
}

/**
 * Turn a contact name or raw number into a number to send to.
 *
 * Refuses rather than guesses when a name matches more than one person —
 * texting the wrong human is not a recoverable error.
 */
private suspend fun Context.resolveRecipient(to: String): String {
    val target = to.trim()
    if (target.isEmpty()) throw ToolException("No recipient given.")
    if (looksLikePhoneNumber(target)) return target

    val matches = contacts(target)
    if (matches.isEmpty()) {
        throw ToolException(
            "No contact matching '$target'. Use contacts_find to see what names exist, " +
                "or pass a full phone number.",
        )
    }

    val exact = matches.filter { it.name.equals(target, ignoreCase = true) }
    val candidates = exact.ifEmpty { matches }

    // One person with the same number listed twice is not a real ambiguity.
    val distinct = candidates.map { normalizeNumber(it.number) }.toSet()
    if (distinct.size > 1) {
        val listing = candidates.take(8).joinToString(", ") { "${it.name} <${it.number}>" }
        throw ToolException(
            "'$target' is ambiguous — it matches ${candidates.size} contacts: $listing. " +
                "Ask the user which one, then pass the exact number.",
        )
    }
    return candidates.first().number
}

class ContactsFindTool(private val context: Context) : BaseTool(
    name = "contacts_find",
    description = "Search the phone's contacts by name and return matching names and numbers. " +
        "Call this before messaging or calling someone referred to by name.",
    parameters = schema {
        string("query", "Part of a contact's name, case insensitive. Omit to list all contacts.")
    },
    category = "messaging",
) {
    override suspend fun call(arguments: JsonObject): JsonElement {
        val found = context.contacts(arguments.stringOr("query"))
        val rows = found.take(MAX_ROWS).map { ok("name" to it.name, "number" to it.number) }
        return listResult("contacts", rows, total = found.size)
    }
}

class SmsListTool(private val context: Context) : BaseTool(
    name = "sms_list",
    description = "Read SMS messages from the phone, newest first. Use this to answer questions " +
        "about what someone said or whether a message arrived.",
    parameters = schema {
        integer("limit", "How many messages to return (default 10, maximum 50).")
        string("box", "Which mailbox to read.", enum = listOf("inbox", "sent"))
        string("contact", "Optional contact name or phone number to filter by.")
    },
    category = "messaging",
) {
    override suspend fun call(arguments: JsonObject): JsonElement {
        context.requirePermission(Manifest.permission.READ_SMS, "read your messages")

        val limit = arguments.intOr("limit", 10).coerceIn(1, MAX_ROWS)
        val uri = when (arguments.stringOr("box", "inbox")) {
            "sent" -> Telephony.Sms.Sent.CONTENT_URI
            else -> Telephony.Sms.Inbox.CONTENT_URI
        }

        val contact = arguments.stringOr("contact")
        // Filtering happens here rather than in the query, because a stored
        // address may be formatted differently from the contact's number.
        val wanted = if (contact.isBlank()) {
            null
        } else {
            normalizeNumber(if (looksLikePhoneNumber(contact)) contact else context.resolveRecipient(contact))
        }

        val fetch = if (wanted == null) limit else (limit * 10).coerceAtMost(500)
        val messages = context.query(
            uri = uri,
            projection = arrayOf(
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.READ,
            ),
            sortOrder = "${Telephony.Sms.DATE} DESC",
            limit = fetch,
        ) { cursor ->
            Triple(
                cursor.stringOrEmpty(Telephony.Sms.ADDRESS),
                cursor.stringOrEmpty(Telephony.Sms.BODY),
                cursor.longOrZero(Telephony.Sms.DATE) to (cursor.intOrZero(Telephony.Sms.READ) == 1),
            )
        }

        val filtered = messages.filter { wanted == null || normalizeNumber(it.first) == wanted }

        // Naming the sender is a nicety. If Contacts is not granted, show the
        // number rather than failing a read the user asked for.
        val names = runCatching { context.contacts() }.getOrDefault(emptyList())
            .associateBy { normalizeNumber(it.number) }

        val rows = filtered.take(limit).map { (address, body, meta) ->
            ok(
                "from" to (names[normalizeNumber(address)]?.name ?: address),
                "number" to address,
                "body" to body,
                "received" to formatTime(meta.first),
                "read" to meta.second,
            )
        }
        return listResult("messages", rows, total = filtered.size)
    }
}

class SmsSendTool(private val context: Context) : BaseTool(
    name = "sms_send",
    description = "Send an SMS. The recipient may be a contact name or a phone number. " +
        "The user confirms the exact text before it goes out.",
    parameters = schema {
        string("to", "Contact name or phone number to send to.", required = true)
        string("message", "The message body, exactly as it should be sent.", required = true)
    },
    category = "messaging",
    consequential = true,
) {
    override suspend fun call(arguments: JsonObject): JsonElement {
        context.requirePermission(Manifest.permission.SEND_SMS, "send messages")
        val message = arguments.requireString("message")
        if (message.isBlank()) throw ToolException("Refusing to send an empty message.")

        val number = context.resolveRecipient(arguments.requireString("to"))
        val manager = context.getSystemService(SmsManager::class.java)
            ?: throw ToolException("This device has no SMS service available.")

        try {
            // Anything over one segment must be split, or the send silently
            // fails on some carriers.
            val parts = manager.divideMessage(message)
            if (parts.size > 1) {
                manager.sendMultipartTextMessage(number, null, parts, null, null)
            } else {
                manager.sendTextMessage(number, null, message, null, null)
            }
        } catch (e: Exception) {
            throw ToolException("Could not send the message: ${e.message}")
        }

        return ok(
            "sent" to true,
            "to" to number,
            "message" to message,
            "segments" to manager.divideMessage(message).size,
        )
    }
}

class CallLogTool(private val context: Context) : BaseTool(
    name = "call_log",
    description = "List recent incoming, outgoing and missed calls, newest first.",
    parameters = schema {
        integer("limit", "How many entries to return (default 10, maximum 50).")
    },
    category = "messaging",
) {
    override suspend fun call(arguments: JsonObject): JsonElement {
        context.requirePermission(Manifest.permission.READ_CALL_LOG, "read your call history")
        val limit = arguments.intOr("limit", 10).coerceIn(1, MAX_ROWS)

        val rows = context.query(
            uri = CallLog.Calls.CONTENT_URI,
            projection = arrayOf(
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.NUMBER,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION,
            ),
            sortOrder = "${CallLog.Calls.DATE} DESC",
            limit = limit,
        ) { cursor ->
            val kind = when (cursor.intOrZero(CallLog.Calls.TYPE)) {
                CallLog.Calls.INCOMING_TYPE -> "incoming"
                CallLog.Calls.OUTGOING_TYPE -> "outgoing"
                CallLog.Calls.MISSED_TYPE -> "missed"
                CallLog.Calls.REJECTED_TYPE -> "rejected"
                CallLog.Calls.BLOCKED_TYPE -> "blocked"
                else -> "other"
            }
            ok(
                "name" to cursor.stringOrEmpty(CallLog.Calls.CACHED_NAME).ifBlank { null },
                "number" to cursor.stringOrEmpty(CallLog.Calls.NUMBER),
                "type" to kind,
                "when" to formatTime(cursor.longOrZero(CallLog.Calls.DATE)),
                "duration_seconds" to cursor.longOrZero(CallLog.Calls.DURATION),
            )
        }
        return listResult("calls", rows)
    }
}

class CallPlaceTool(private val context: Context) : BaseTool(
    name = "call_place",
    description = "Place a phone call. The recipient may be a contact name or a number. " +
        "The user confirms before it is dialled.",
    parameters = schema {
        string("to", "Contact name or phone number to call.", required = true)
    },
    category = "messaging",
    consequential = true,
) {
    override suspend fun call(arguments: JsonObject): JsonElement {
        context.requirePermission(Manifest.permission.CALL_PHONE, "place calls")
        val number = context.resolveRecipient(arguments.requireString("to"))

        val intent = Intent(Intent.ACTION_CALL, Uri.fromParts("tel", number, null)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            throw ToolException("Could not start the call: ${e.message}")
        }
        return ok("dialing" to number)
    }
}
