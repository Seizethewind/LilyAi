package com.seizethewind.lilyai

import android.app.Application
import com.seizethewind.lilyai.api.ApiKeyStore
import com.seizethewind.lilyai.api.ApiRegistry
import com.seizethewind.lilyai.core.Agent
import com.seizethewind.lilyai.core.TaskActivity
import com.seizethewind.lilyai.inference.LlamaEngine
import com.seizethewind.lilyai.memory.CloudSync
import com.seizethewind.lilyai.memory.LongTermMemory
import com.seizethewind.lilyai.memory.MemoryManager
import com.seizethewind.lilyai.model.ModelDownloadManager
import com.seizethewind.lilyai.notifications.AgentNotifications
import com.seizethewind.lilyai.permissions.PermissionManager
import com.seizethewind.lilyai.safety.ConfirmationManager
import com.seizethewind.lilyai.safety.SafetyPolicy
import com.seizethewind.lilyai.service.AgentService
import com.seizethewind.lilyai.settings.SettingsManager
import com.seizethewind.lilyai.tools.GetTimeTool
import com.seizethewind.lilyai.tools.ListMemoriesTool
import com.seizethewind.lilyai.tools.RememberTool
import com.seizethewind.lilyai.tools.ToolExecutor
import com.seizethewind.lilyai.tools.ToolRegistry
import com.seizethewind.lilyai.ui.OverlayManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class LilyAiApplication : Application() {

    lateinit var settings: SettingsManager
        private set
    lateinit var keyStore: ApiKeyStore
        private set
    lateinit var apiRegistry: ApiRegistry
        private set
    lateinit var modelManager: ModelDownloadManager
        private set
    lateinit var localEngine: LlamaEngine
        private set
    lateinit var longTermMemory: LongTermMemory
        private set
    lateinit var cloudSync: CloudSync
        private set
    lateinit var memoryManager: MemoryManager
        private set
    lateinit var permissions: PermissionManager
        private set
    lateinit var notifications: AgentNotifications
        private set
    lateinit var overlay: OverlayManager
        private set
    lateinit var confirmations: ConfirmationManager
        private set
    lateinit var safetyPolicy: SafetyPolicy
        private set
    lateinit var agent: Agent
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var busyJob: Job? = null

    /** Starts the foreground service only if a request runs longer than a couple of seconds. */
    private val taskActivity = object : TaskActivity {
        override fun onBusy() {
            busyJob?.cancel()
            busyJob = appScope.launch {
                delay(BUSY_DELAY_MS)
                try {
                    AgentService.start(this@LilyAiApplication)
                } catch (e: Exception) {
                    // Android may refuse to start a service from the background; the task still runs.
                }
            }
        }

        override fun onIdle() {
            busyJob?.cancel()
            AgentService.stop(this@LilyAiApplication)
        }
    }

    override fun onCreate() {
        super.onCreate()
        settings = SettingsManager(this)
        keyStore = ApiKeyStore(this)
        apiRegistry = ApiRegistry.load(this, keyStore, settings)
        modelManager = ModelDownloadManager(this)
        localEngine = LlamaEngine(modelManager)
        longTermMemory = LongTermMemory(this)
        cloudSync = CloudSync(keyStore, settings, longTermMemory)
        memoryManager = MemoryManager(longTermMemory).also { it.onChanged = { cloudSync.requestSync() } }

        permissions = PermissionManager(this)
        notifications = AgentNotifications(this)
        confirmations = ConfirmationManager()
        safetyPolicy = SafetyPolicy()
        val toolRegistry = ToolRegistry(
            listOf(GetTimeTool(), ListMemoriesTool(memoryManager), RememberTool(memoryManager))
        )
        val toolExecutor = ToolExecutor(safetyPolicy, confirmations)

        agent = Agent(
            registry = apiRegistry,
            settings = settings,
            localEngine = localEngine,
            keyStore = keyStore,
            memory = memoryManager,
            tools = toolRegistry,
            toolExecutor = toolExecutor,
            safety = safetyPolicy,
            confirmations = confirmations,
            activity = taskActivity
        )
        overlay = OverlayManager(this) { agent.stop() }
    }

    companion object {
        private const val BUSY_DELAY_MS = 2000L
    }
}
