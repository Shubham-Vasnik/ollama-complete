package com.shubhamvasnik.ollamacomplete.completion

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
}
