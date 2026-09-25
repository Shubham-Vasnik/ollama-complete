package com.shubhamvasnik.ollamacomplete

import com.intellij.codeInsight.inline.completion.testInlineCompletion
import com.intellij.openapi.application.ApplicationManager
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

    fun `test multi-line completion stops at the end of the block and alternatives can be cycled`() {
        if (!enabled) return
        OllamaSettings.getInstance().suggestionCount = 2
        myFixture.testInlineCompletion(3.minutes) {
            init(
                PlainTextFileType.INSTANCE,
                "public class Util {\n    public static int max(int a, int b) {\n        <caret>\n    }\n\n" +
                    "    public static int min(int a, int b) {\n        return a < b ? a : b;\n    }\n}\n",
            )
            callInlineCompletion()
            delay()
            val first = assertContextExists().textToInsert()
            println("multi-line suggestion: [$first]")
            assertTrue("unexpected suggestion: [$first]", first.contains("a") && first.contains("b"))
            assertFalse("must stop at the end of the method: [$first]", first.contains("public static"))
            assertFalse("must not repeat the closing brace: [$first]", first.trimEnd().endsWith("}") && first.count { it == '}' } > first.count { it == '{' })
            nextVariant()
            val second = assertContextExists().textToInsert()
            println("alternative suggestion: [$second]")
            assertTrue(second.isNotBlank())
        }
    }

    fun `test completion uses code from a neighboring file`() {
        if (!enabled) return
        // Plain text has no line comment syntax, so FIM prompts leave snippets out; the instruct prompt lists them.
        OllamaSettings.getInstance().apply {
            completionMode = CompletionMode.INSTRUCT
            useOpenFilesContext = true
        }
        ApplicationManager.getApplication().invokeAndWait {
            myFixture.addFileToProject(
                "MathHelpers.txt",
                "public final class MathHelpers {\n" +
                    "    /** Triples the value. */\n" +
                    "    public static int zorbleTriple(int value) {\n        return value * 3;\n    }\n}\n",
            )
        }
        myFixture.testInlineCompletion(3.minutes) {
            init(
                PlainTextFileType.INSTANCE,
                "public class Main {\n    /** Triples x with the helper from MathHelpers. */\n" +
                    "    public static int triple(int x) {\n        return MathHelpers.<caret>\n    }\n}\n",
            )
            typeChar('z')
            delay()
            val suggestion = assertContextExists().textToInsert()
            println("neighbor-file suggestion: [$suggestion]")
            assertTrue("unexpected suggestion: [$suggestion]", suggestion.contains("orbleTriple(x)"))
            insertWithTab()
            val text = withWriteAction { fixture.editor.document.text }
            assertTrue(text, text.contains("return MathHelpers.zorbleTriple(x)"))
        }
    }
}
