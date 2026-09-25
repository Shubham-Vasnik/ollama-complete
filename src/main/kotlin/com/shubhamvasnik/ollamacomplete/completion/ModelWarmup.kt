package com.shubhamvasnik.ollamacomplete.completion

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.shubhamvasnik.ollamacomplete.api.GenerateRequest
import com.shubhamvasnik.ollamacomplete.api.OllamaClient
import com.shubhamvasnik.ollamacomplete.settings.OllamaSettings
import com.shubhamvasnik.ollamacomplete.settings.OllamaSettingsListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Loads the completion model into memory ahead of time, so the first suggestion doesn't wait for Ollama to load it.
 * An empty /api/generate request only loads the model.
 */
@Service(Service.Level.APP)
class ModelWarmup(private val scope: CoroutineScope) {

    @Volatile
    private var lastModel: String? = null

    @Volatile
    private var lastTime = 0L

    fun warmUp() {
        val settings = OllamaSettings.getInstance()
        val model = settings.completionModel
        if (!settings.completionEnabled || model.isBlank()) return
        val now = System.currentTimeMillis()
        if (model == lastModel && now - lastTime < MIN_INTERVAL_MS) return
        lastModel = model
        lastTime = now
        scope.launch {
            try {
                OllamaClient.getInstance().generate(
                    GenerateRequest(model = model, prompt = "", keepAlive = settings.keepAlive.ifBlank { null }),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Completion reports connection problems itself; a failed warm-up is not worth a notification.
                LOG.debug("Warm-up of $model failed", e)
            }
        }
    }

    companion object {
        private const val MIN_INTERVAL_MS = 60_000L
        private val LOG = logger<ModelWarmup>()

        fun getInstance(): ModelWarmup = service()
    }
}

class ModelWarmupActivity : ProjectActivity {
    override suspend fun execute(project: Project) = ModelWarmup.getInstance().warmUp()
}

class ModelWarmupSettingsListener : OllamaSettingsListener {
    override fun settingsChanged() = ModelWarmup.getInstance().warmUp()
}
