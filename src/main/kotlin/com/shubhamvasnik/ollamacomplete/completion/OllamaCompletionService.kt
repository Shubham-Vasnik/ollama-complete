package com.shubhamvasnik.ollamacomplete.completion

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.shubhamvasnik.ollamacomplete.api.GenerateRequest
import com.shubhamvasnik.ollamacomplete.api.ModelOptions
import com.shubhamvasnik.ollamacomplete.api.OllamaClient
import com.shubhamvasnik.ollamacomplete.settings.CompletionMode
import com.shubhamvasnik.ollamacomplete.settings.OllamaSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.transformWhile

/** Builds the Ollama request for a completion, streams it and cleans up the answer. */
@Service(Service.Level.APP)
class OllamaCompletionService {

    private val cache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 64
    }

    /** Recent answers, to keep suggesting the rest of one while the user types what it says. */
    private val recent = ArrayDeque<Recent>()

    private class Recent(val key: String, val prefixTail: String, val result: String)

    /** The whole completion at once; see [stream]. */
    suspend fun complete(context: CompletionContext, variant: Int = 0): String = stream(context, variant).toList().joinToString("")

    /**
     * Streams the completion as pieces of text to append to the gray text. [variant] 0 is the main suggestion;
     * higher variants are alternatives sampled at a higher temperature.
     */
    fun stream(context: CompletionContext, variant: Int = 0): Flow<String> = flow {
        val settings = OllamaSettings.getInstance()
        val client = OllamaClient.getInstance()
        val model = settings.completionModel
        val capabilities = client.capabilities(model)
        val useFim = when (settings.completionMode) {
            CompletionMode.FIM -> true
            CompletionMode.INSTRUCT -> false
            CompletionMode.AUTO -> "insert" in capabilities
        }
        val singleLine = !settings.multiLine || !context.allowsMultiLine

        val key = listOf(
            model, useFim, singleLine, variant, context.suffix.take(500),
            context.snippets.hashCode(), context.instructions.hashCode(),
        ).joinToString("\u0000")
        val fullKey = key + "\u0000" + context.prefix.takeLast(1500)
        val cached = synchronized(cache) { cache[fullKey] } ?: typedAhead(key, context.prefix)
        if (cached != null) {
            if (cached.isNotEmpty()) emit(cached)
            return@flow
        }

        val temperature = settings.temperature.toDouble().let { if (variant == 0) it else maxOf(it + 0.4, 0.6) }
        val options = ModelOptions(
            temperature = temperature,
            numPredict = settings.maxTokens,
            // Instruct models may open with a newline, so line limits are applied in post-processing instead.
            stop = if (useFim) CompletionPromptBuilder.stopSequences(singleLine) else null,
        )
        val think = if ("thinking" in capabilities) false else null
        val keepAlive = settings.keepAlive.ifBlank { null }
        val request = if (useFim) {
            GenerateRequest(
                model = model,
                prompt = CompletionPromptBuilder.fimPrompt(context),
                // An empty suffix would make Ollama use the chat template instead of the FIM template.
                suffix = context.suffix.ifEmpty { "\n" },
                think = think,
                options = options,
                keepAlive = keepAlive,
            )
        } else {
            GenerateRequest(
                model = model,
                system = CompletionPromptBuilder.instructSystemPrompt(context),
                prompt = CompletionPromptBuilder.instructPrompt(context),
                think = think,
                options = options,
                keepAlive = keepAlive,
            )
        }

        val completion = StreamingCompletion(context, singleLine, instructMode = !useFim)
        // Stopping the collection aborts the HTTP request, so Ollama stops generating once the result is known.
        client.generateStream(request).transformWhile { chunk ->
            emit(completion.append(chunk.response))
            !completion.finished && !chunk.done
        }.collect { if (it.isNotEmpty()) emit(it) }
        completion.finish().takeIf { it.isNotEmpty() }?.let { emit(it) }

        val result = completion.result
        synchronized(cache) {
            cache[fullKey] = result
            recent.addFirst(Recent(key, context.prefix.takeLast(TAIL), result))
            while (recent.size > 16) recent.removeLast()
        }
    }

    /**
     * If the user typed the start of a recent suggestion, the rest of it is still the best guess (Copilot's
     * "typing as suggested"), so it is returned without asking the model again.
     */
    private fun typedAhead(key: String, prefix: String): String? = synchronized(cache) {
        for (entry in recent) {
            if (entry.key != key) continue
            val typed = typedPartOf(entry.result, entry.prefixTail, prefix) ?: continue
            return entry.result.substring(typed).takeIf { it.isNotBlank() }
        }
        null
    }

    fun clearCache() = synchronized(cache) {
        cache.clear()
        recent.clear()
    }

    companion object {
        private const val TAIL = 200

        fun getInstance(): OllamaCompletionService = service()

        /**
         * Returns how many characters of [result] were typed since it was suggested after [oldPrefixTail],
         * or null if [prefix] isn't the old prefix plus the start of [result].
         */
        internal fun typedPartOf(result: String, oldPrefixTail: String, prefix: String): Int? {
            for (k in minOf(result.length - 1, TAIL) downTo 1) {
                if (prefix.endsWith(result.substring(0, k)) && prefix.regionMatches(prefix.length - k - oldPrefixTail.length, oldPrefixTail, 0, oldPrefixTail.length)) {
                    return k
                }
            }
            return null
        }
    }
}
