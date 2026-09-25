package com.shubhamvasnik.ollamacomplete.completion

import com.shubhamvasnik.ollamacomplete.completion.context.Snippet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletionPromptBuilderTest {

    @Test
    fun `splits text at the caret`() {
        val text = "line1\nline2\nline3"
        val ctx = CompletionPromptBuilder.extract(text, 8, 1000, 1000, "a.txt", "Text")
        assertEquals("line1\nli", ctx.prefix)
        assertEquals("ne2\nline3", ctx.suffix)
        assertEquals("li", ctx.linePrefix)
        assertEquals("ne2", ctx.lineSuffix)
    }

    @Test
    fun `truncated prefix starts at a line boundary`() {
        val text = "aaaa\nbbbb\ncccc"
        val ctx = CompletionPromptBuilder.extract(text, text.length, 7, 0, "a.txt", "Text")
        assertEquals("cccc", ctx.prefix)
    }

    @Test
    fun `truncated suffix ends at a line boundary`() {
        val text = "x\naaaa\nbbbb\ncccc"
        val ctx = CompletionPromptBuilder.extract(text, 1, 100, 8, "a.txt", "Text")
        assertEquals("\naaaa", ctx.suffix)
    }

    @Test
    fun `caret offset is clamped`() {
        val ctx = CompletionPromptBuilder.extract("abc", 99, 100, 100, "a.txt", "Text")
        assertEquals("abc", ctx.prefix)
        assertEquals("", ctx.suffix)
    }

    @Test
    fun `mid line detection ignores closing punctuation`() {
        assertFalse(CompletionContext("foo(", ")", "", "").isMidLine)
        assertFalse(CompletionContext("x", "\nnext", "", "").isMidLine)
        assertTrue(CompletionContext("foo(", "bar)", "", "").isMidLine)
    }

    @Test
    fun `instruct prompt marks the caret`() {
        val prompt = CompletionPromptBuilder.instructPrompt(CompletionContext("int a = ", ";", "Main.java", "Java"))
        assertEquals("File: Main.java (Java)\n\nint a = <CURSOR>;", prompt)
    }

    private val snippet = Snippet("src/Util.java", "static int twice(int x) {\n\n    return 2 * x;\n}", 0.5)

    @Test
    fun `fim prompt shows snippets as line comments`() {
        val ctx = CompletionContext("int a = ", ";", "Main.java", "Java", filePath = "src/Main.java",
            snippets = listOf(snippet), commentPrefix = "// ")
        assertEquals(
            "// Path: src/Main.java\n" +
                "// Compare this snippet from src/Util.java:\n" +
                "// static int twice(int x) {\n//\n//     return 2 * x;\n// }\n" +
                "int a = ",
            CompletionPromptBuilder.fimPrompt(ctx),
        )
    }

    @Test
    fun `fim prompt without comment syntax or snippets is the prefix`() {
        val noComment = CompletionContext("<div>", "", "a.html", "HTML", snippets = listOf(snippet))
        assertEquals("<div>", CompletionPromptBuilder.fimPrompt(noComment))
        assertEquals("x = ", CompletionPromptBuilder.fimPrompt(CompletionContext("x = ", "", "a.py", "Python", commentPrefix = "#")))
    }

    @Test
    fun `instruct prompt lists related code and instructions`() {
        val ctx = CompletionContext("int a = ", ";", "Main.java", "Java", filePath = "src/Main.java",
            snippets = listOf(snippet), instructions = "Use tabs.")
        assertEquals(
            "Related code from other files (for reference only, do not repeat it):\n\n" +
                "--- src/Util.java ---\n${snippet.text}\n\n" +
                "File: src/Main.java (Java)\n\nint a = <CURSOR>;",
            CompletionPromptBuilder.instructPrompt(ctx),
        )
        assertTrue(CompletionPromptBuilder.instructSystemPrompt(ctx).endsWith("\n\nProject instructions:\nUse tabs."))
        assertEquals(CompletionPromptBuilder.INSTRUCT_SYSTEM_PROMPT, CompletionPromptBuilder.instructSystemPrompt(ctx.copy(instructions = null)))
    }
    @Test
    fun `multi line only where a block or statement starts`() {
        assertTrue(CompletionContext("    ", "", "", "").allowsMultiLine)
        assertTrue(CompletionContext("fun f() {", "", "", "").allowsMultiLine)
        assertTrue(CompletionContext("def f(x):", "", "", "").allowsMultiLine)
        assertTrue(CompletionContext("list.map { x ->", "", "", "").allowsMultiLine)
        assertFalse(CompletionContext("        return", "", "", "").allowsMultiLine)
        assertFalse(CompletionContext("    ", "foo()", "", "").allowsMultiLine)
    }
}
