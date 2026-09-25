package com.shubhamvasnik.ollamacomplete.completion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingCompletionTest {

    private fun ctx(prefix: String, suffix: String = "") = CompletionContext(prefix, suffix, "Main.java", "Java")

    private fun StreamingCompletion.feed(vararg chunks: String): List<String> =
        chunks.map { append(it) } + finish()

    @Test
    fun `shows complete lines while streaming and the rest at the end`() {
        val completion = StreamingCompletion(ctx("    void f() {\n        "), singleLine = false, instructMode = false)
        assertEquals("", completion.append("int a"))
        assertEquals("int a = 1;", completion.append(" = 1;\n        int "))
        assertEquals("", completion.append("b = 2;"))
        assertEquals("\n        int b = 2;", completion.finish())
        assertEquals("int a = 1;\n        int b = 2;", completion.result)
    }

    @Test
    fun `finishes when the block at the caret ends`() {
        val completion = StreamingCompletion(ctx("        ", "\n    }\n}"), singleLine = false, instructMode = false)
        completion.append("return a * b;\n    }\n")
        assertFalse(completion.finished)
        completion.append("    int other() {\n")
        assertTrue(completion.finished)
        completion.finish()
        assertEquals("return a * b;", completion.result)
    }

    @Test
    fun `single line finishes after the first line`() {
        val completion = StreamingCompletion(ctx("int x = "), singleLine = true, instructMode = true)
        val pieces = completion.feed("```java\n", "42;\n", "more")
        assertTrue(completion.finished)
        assertEquals("42;", completion.result)
        assertEquals("42;", pieces.joinToString(""))
    }

    @Test
    fun `holds back lines that may duplicate the code after the caret`() {
        val completion = StreamingCompletion(ctx("        ", "\n    }\n}"), singleLine = false, instructMode = false)
        // "    }" repeats the line after the caret, so it is not shown yet...
        assertEquals("return 1;", completion.append("return 1;\n    }\n"))
        // ...and it never is, because the answer ends there.
        assertEquals("", completion.finish())
        assertEquals("return 1;", completion.result)
    }

    @Test
    fun `shown text is always a prefix of the final result`() {
        val completion = StreamingCompletion(ctx("    void f() {\n        "), singleLine = false, instructMode = false)
        val pieces = completion.feed("foo(", "1);\n", "        bar(2", ");\n", "        baz();")
        assertEquals(completion.result, pieces.joinToString(""))
    }
}
