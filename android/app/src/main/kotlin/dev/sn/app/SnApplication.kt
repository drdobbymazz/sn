package dev.sn.app

import android.app.Application
import android.content.Context
import dev.sn.app.data.ConversationRepository
import dev.sn.app.data.DatabaseAuditor
import dev.sn.app.data.SnDatabase
import dev.sn.app.data.SettingsStore
import dev.sn.app.data.SnSettings
import dev.sn.app.service.TriageScheduler
import dev.sn.app.tools.Notifications
import dev.sn.app.tools.buildToolRegistry
import dev.sn.core.Agent
import dev.sn.core.Confirmer
import dev.sn.core.OllamaClient
import dev.sn.core.ToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Wiring for the whole app.
 *
 * Hand-rolled rather than a DI framework: there are six things to construct and
 * they are all singletons, so a container is easier to follow than a graph.
 */
class SnContainer(context: Context) {
    val database: SnDatabase = SnDatabase.get(context)
    val settings = SettingsStore(context)
    val conversations = ConversationRepository(database)
    val tools: ToolRegistry = buildToolRegistry(context, database)

    /** Rebuilt per turn, since the host and model can change in Settings. */
    fun client(current: SnSettings): OllamaClient = OllamaClient(current.ollama())

    fun agent(
        current: SnSettings,
        conversationId: () -> Long,
        confirmer: Confirmer,
    ): Agent = Agent(
        client = client(current),
        registry = tools,
        config = current.agent(),
        confirmer = confirmer,
        auditor = DatabaseAuditor(database, conversationId),
    )

    suspend fun currentSettings(): SnSettings = settings.settings.first()
}

class SnApplication : Application() {

    lateinit var container: SnContainer
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = SnContainer(this)
        Notifications.ensureChannels(this)

        // Bring the proactive pass in line with settings at every launch, so a
        // setting changed while the app was closed still takes effect.
        scope.launch {
            val settings = container.currentSettings()
            TriageScheduler.apply(this@SnApplication, settings)
        }
    }

    companion object {
        fun from(context: Context): SnContainer =
            (context.applicationContext as SnApplication).container
    }
}
