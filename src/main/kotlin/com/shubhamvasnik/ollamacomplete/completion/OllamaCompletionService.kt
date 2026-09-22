package com.shubhamvasnik.ollamacomplete.completion

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.shubhamvasnik.ollamacomplete.api.GenerateRequest
import com.shubhamvasnik.ollamacomplete.api.ModelOptions
import com.shubhamvasnik.ollamacomplete.api.OllamaClient
import com.shubhamvasnik.ollamacomplete.settings.CompletionMode
import com.shubhamvasnik.ollamacomplete.settings.OllamaSettings

/** Builds the Ollama request for a completion, runs it and cleans up the answer. */
@Service(Service.Level.APP)
class OllamaCompletionService {

    private val cache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 64
    }

    suspend fun complete(context: CompletionContext): String {
        val settings = OllamaSettings.getInstance()
        val client = OllamaClient.getInstance()
        val model = settings.completionModel
        val capabilities = client.capabilities(model)
        val useFim = when (settings.completionMode) {
            CompletionMode.FIM -> true
            CompletionMode.INSTRUCT -> false
            CompletionMode.AUTO -> "insert" in capabilities
        }
        val singleLine = !settings.multiLine || context.isMidLine

        val key = listOf(model, useFim, singleLine, context.prefix.takeLast(1500), context.suffix.take(500)).joinToString("\u0000")
        synchronized(cache) { cache[key] }?.let { return it }

        val options = ModelOptions(
            temperature = settings.temperature.toDouble(),
            numPredict = settings.maxTokens,
            // Instruct models may open with a newline, so line limits are applied in post-processing instead.
            stop = if (useFim) CompletionPromptBuilder.stopSequences(singleLine) else null,
        )
        val think = if ("thinking" in capabilities) false else null
        val keepAlive = settings.keepAlive.ifBlank { null }
        val request = if (useFim) {
            GenerateRequest(
                model = model,
                prompt = context.prefix,
                // An empty suffix would make Ollama use the chat template instead of the FIM template.
                suffix = context.suffix.ifEmpty { "\n" },
                think = think,
                options = options,
                keepAlive = keepAlive,
            )
        } else {
            GenerateRequest(
                model = model,
                system = CompletionPromptBuilder.INSTRUCT_SYSTEM_PROMPT,
                prompt = CompletionPromptBuilder.instructPrompt(context),
                think = think,
                options = options,
                keepAlive = keepAlive,
            )
        }

        val raw = client.generate(request).response
        val result = CompletionPostProcessor.process(raw, context, singleLine, instructMode = !useFim)
        synchronized(cache) { cache[key] = result }
        return result
    }

    fun clearCache() = synchronized(cache) { cache.clear() }

    companion object {
        fun getInstance(): OllamaCompletionService = service()
    }
}
