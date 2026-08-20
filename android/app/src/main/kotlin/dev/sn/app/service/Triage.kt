package dev.sn.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.sn.app.SnApplication
import dev.sn.app.data.SnSettings
import dev.sn.app.tools.Notifications
import dev.sn.core.AgentEvent
import dev.sn.core.ChatMessage
import dev.sn.core.Confirmer
import dev.sn.core.OllamaException
import kotlinx.coroutines.flow.toList
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * The proactive pass: look at notifications that arrived since last time and
 * decide whether any of them is worth interrupting the user for.
 *
 * Deliberately narrow. It reads and it alerts; it never replies to anyone, and
 * it never runs a tool — the confirmer refuses everything, so a model that
 * decides to text someone during a background pass simply cannot.
 */
class TriageWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val container = SnApplication.from(applicationContext)
        val settings = container.currentSettings()

        if (!settings.proactiveEnabled || !settings.isConfigured) return Result.success()
        if (settings.inQuietHours(Calendar.getInstance().get(Calendar.HOUR_OF_DAY))) {
            return Result.success()
        }

        val since = System.currentTimeMillis() - LOOKBACK_MS
        val pending = container.database.notifications().untriaged(since)
            .filterNot { it.isOngoing }
        if (pending.isEmpty()) return Result.success()

        val digest = pending.joinToString("\n") { record ->
            "- [${record.appLabel}] ${record.title}: ${record.text.take(200)}"
        }

        val agent = container.agent(
            current = settings.copy(
                systemPrompt = TRIAGE_PROMPT,
                // One pass, no tools: this is a judgement call on text that is
                // already in hand, not an investigation.
                maxSteps = 1,
                disabledTools = container.tools.all.map { it.name }.toSet(),
            ),
            conversationId = { 0L },
            confirmer = Confirmer { _, _ -> false },
        )

        val answer = try {
            agent.run(prompt = digest, history = emptyList<ChatMessage>())
                .toList()
                .filterIsInstance<AgentEvent.Final>()
                .firstOrNull()
                ?.text
                .orEmpty()
        } catch (e: OllamaException) {
            // The laptop being asleep is the normal case, not an error worth
            // retrying aggressively or telling the user about.
            return Result.success()
        }

        val worthIt = answer.isNotBlank() && !answer.trim().startsWith(NOTHING, ignoreCase = true)
        if (worthIt) {
            Notifications.post(
                applicationContext,
                Notifications.ID_ALERT,
                "sn noticed something",
                answer.trim(),
            )
        }

        container.database.notifications().markTriaged(
            ids = pending.map { it.id },
            now = System.currentTimeMillis(),
            flagged = worthIt,
        )
        return Result.success()
    }

    private companion object {
        const val LOOKBACK_MS = 24L * 60 * 60 * 1000
        const val NOTHING = "NOTHING"

        val TRIAGE_PROMPT = """
            You are triaging notifications that arrived on the user's phone while they were away.

            Decide whether anything here genuinely needs their attention now. Be strict: most
            notifications do not. Marketing, social media likes, app updates, news headlines,
            delivery tracking and routine app chatter are all noise.

            Things that usually do matter: a message from a real person that asks something or
            expects a reply, a calendar reminder for something imminent, a payment or security
            alert, a missed call.

            If nothing needs attention, reply with exactly: NOTHING

            Otherwise reply with at most three short lines, each naming who or what it is about
            and why it matters. No preamble, no markdown.
        """.trimIndent()
    }
}

object TriageScheduler {
    private const val WORK_NAME = "sn_triage"

    /** Starts, reschedules or cancels the pass to match the current settings. */
    fun apply(context: Context, settings: SnSettings) {
        val manager = WorkManager.getInstance(context)
        if (!settings.proactiveEnabled) {
            manager.cancelUniqueWork(WORK_NAME)
            return
        }

        val request = PeriodicWorkRequestBuilder<TriageWorker>(
            settings.proactiveIntervalMinutes.toLong().coerceAtLeast(15),
            TimeUnit.MINUTES,
        ).setConstraints(
            // No point waking the model when there is no way to reach it.
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
        ).build()

        manager.enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }
}

/** Re-arms the proactive pass after a reboot. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // WorkManager restores periodic work itself; this exists so the app is
        // started once after boot, which also rebinds the notification listener.
        val pending = goAsync()
        Thread {
            try {
                SnApplication.from(context)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
