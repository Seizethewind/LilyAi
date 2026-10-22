package com.seizethewind.lilyai

import android.app.Application
import com.seizethewind.lilyai.api.ApiKeyStore
import com.seizethewind.lilyai.api.ApiRegistry
import com.seizethewind.lilyai.core.Agent
import com.seizethewind.lilyai.inference.LlamaEngine
import com.seizethewind.lilyai.memory.CloudSync
import com.seizethewind.lilyai.memory.LongTermMemory
import com.seizethewind.lilyai.memory.MemoryManager
import com.seizethewind.lilyai.model.ModelDownloadManager
import com.seizethewind.lilyai.settings.SettingsManager

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
    lateinit var agent: Agent
        private set

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
        agent = Agent(apiRegistry, settings, localEngine, keyStore, memoryManager)
    }
}
