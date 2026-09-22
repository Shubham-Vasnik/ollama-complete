package com.shubhamvasnik.ollamacomplete

import com.intellij.codeInsight.inline.completion.testInlineCompletion
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.shubhamvasnik.ollamacomplete.settings.CompletionMode
import com.shubhamvasnik.ollamacomplete.settings.OllamaSettings
import kotlin.time.Duration.Companion.minutes

/**
 * Types into a real editor and checks that Ollama's suggestion is shown as gray text and inserted with Tab.
 * Skipped unless OLLAMA_IT=1. OLLAMA_IT_MODEL selects the model (default qwen3:8b).
 */
class OllamaLiveInlineCompletionTest : BasePlatformTestCase() {

    private val enabled = System.getenv("OLLAMA_IT") == "1"
    private val model = System.getenv("OLLAMA_IT_MODEL") ?: "qwen3:8b"

    // The inline completion test DSL dispatches to the EDT itself, so the test must not block it.
    override fun runInDispatchThread() = false

    override fun setUp() {
        super.setUp()
        OllamaSettings.getInstance().apply {
            completionModel = model
            completionEnabled = true
            completionMode = CompletionMode.AUTO
            multiLine = true
            debounceMs = 0
            requestTimeoutSec = 300
        }
    }

    fun `test gray text appears in the editor and Tab inserts it`() {
        if (!enabled) return
        myFixture.testInlineCompletion(3.minutes) {
            init(
                PlainTextFileType.INSTANCE,
                "public class MathUtil {\n    public static int multiply(int a, int b) {\n        return<caret>\n    }\n}\n",
            )
            typeChar(' ')
            delay()
            val context = assertContextExists()
            val suggestion = context.textToInsert()
            println("inline suggestion: [$suggestion]")
            assertTrue("unexpected suggestion: [$suggestion]", suggestion.contains("a * b") || suggestion.contains("a*b"))
            insertWithTab()
            assertInlineHidden()
            val text = withWriteAction { fixture.editor.document.text }
            println("document after Tab:\n$text")
            assertTrue(text, text.contains("return a * b") || text.contains("return a*b"))
        }
    }
}
