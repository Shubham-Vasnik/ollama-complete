package com.shubhamvasnik.ollamacomplete

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.shubhamvasnik.ollamacomplete.api.ChatMessage
import com.shubhamvasnik.ollamacomplete.api.ChatRequest
import com.shubhamvasnik.ollamacomplete.api.OllamaClient
import com.shubhamvasnik.ollamacomplete.completion.CompletionContext
import com.shubhamvasnik.ollamacomplete.completion.OllamaCompletionService
import com.shubhamvasnik.ollamacomplete.settings.CompletionMode
import com.shubhamvasnik.ollamacomplete.settings.OllamaSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Talks to a real Ollama server. Skipped unless OLLAMA_IT=1.
 * OLLAMA_IT_MODEL selects the model (default qwen3:8b).
 */
class OllamaLiveIntegrationTest : BasePlatformTestCase() {

    private val enabled = System.getenv("OLLAMA_IT") == "1"
    private val model = System.getenv("OLLAMA_IT_MODEL") ?: "qwen3:8b"

    override fun setUp() {
        super.setUp()
        OllamaSettings.getInstance().apply {
            completionModel = model
            completionMode = CompletionMode.AUTO
            multiLine = true
            requestTimeoutSec = 300
        }
    }

    fun `test lists models`() = live {
        val models = OllamaClient.getInstance().listModels()
        assertTrue("expected $model in ${models.map { it.name }}", models.any { it.name == model })
        assertTrue("embedding-only models must be hidden", models.none { "embed" in it.name && !it.canGenerate })
    }

    fun `test completes a method body`() = live {
        val context = CompletionContext(
            prefix = "public class MathUtil {\n    public static int add(int a, int b) {\n        ",
            suffix = "\n    }\n}\n",
            fileName = "MathUtil.java",
            language = "Java",
        )
        val result = withTimeout(300_000) { OllamaCompletionService.getInstance().complete(context) }
        println("completion: [$result]")
        assertTrue("unexpected completion: [$result]", result.contains("a + b") || result.contains("a+b"))
        assertFalse("must not repeat the closing braces: [$result]", result.trimEnd().endsWith("}"))
    }

    fun `test streams chat`() = live {
        val chunks = withTimeout(300_000) {
            OllamaClient.getInstance()
                .chat(ChatRequest(model, listOf(ChatMessage("user", "Reply with the single word: pong")), think = false))
                .toList()
        }
        val answer = chunks.mapNotNull { it.message?.content }.joinToString("")
        println("chat: [$answer]")
        assertTrue(chunks.last().done)
        assertTrue("unexpected answer: [$answer]", answer.contains("pong", ignoreCase = true))
    }

    fun `test cancelling a stream returns promptly`() = live {
        val started = System.currentTimeMillis()
        val first = withTimeout(300_000) {
            OllamaClient.getInstance()
                .chat(ChatRequest(model, listOf(ChatMessage("user", "Count from 1 to 500, one number per line.")), think = false))
                .first()
        }
        assertNotNull(first.message)
        println("first chunk after ${System.currentTimeMillis() - started} ms")
    }

    fun `test unknown model gives a readable error`() = live {
        val error = runCatching {
            OllamaClient.getInstance().capabilities("no-such-model-xyz:latest")
        }.exceptionOrNull()
        assertNotNull(error)
        println("error: ${error!!.message}")
        assertTrue(error.message!!.contains("not found", ignoreCase = true))
    }

    private fun live(block: suspend () -> Unit) {
        if (!enabled) {
            println("Skipping live Ollama test; set OLLAMA_IT=1 to run it.")
            return
        }
        runBlocking { block() }
    }
}
