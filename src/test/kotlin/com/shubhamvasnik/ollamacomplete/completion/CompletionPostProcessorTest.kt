package com.shubhamvasnik.ollamacomplete.completion

import org.junit.Assert.assertEquals
import org.junit.Test

class CompletionPostProcessorTest {

    private fun ctx(prefix: String, suffix: String = "") = CompletionContext(prefix, suffix, "Main.java", "Java")

    @Test
    fun `strips markdown fences from instruct output`() {
        val raw = "```java\nreturn a + b;\n```"
        assertEquals("return a + b;", CompletionPostProcessor.process(raw, ctx("    int add() {\n        "), singleLine = false, instructMode = true))
    }

    @Test
    fun `removes think blocks`() {
        val raw = "<think>the user wants a sum</think>return a + b;"
        assertEquals("return a + b;", CompletionPostProcessor.process(raw, ctx("        "), singleLine = false, instructMode = true))
    }

    @Test
    fun `removes echoed line prefix`() {
        val raw = "int x = 5;"
        assertEquals("5;", CompletionPostProcessor.process(raw, ctx("    int x = "), singleLine = true, instructMode = true))
    }

    @Test
    fun `removes echoed partial word`() {
        val raw = "getName();"
        assertEquals("Name();", CompletionPostProcessor.process(raw, ctx("        String n = user.get"), singleLine = true, instructMode = true))
    }

    @Test
    fun `removes an echoed single letter only when the identifier is known`() {
        val known = "static int zorbleTriple(int v)"
        assertEquals("orbleTriple(x);", CompletionPostProcessor.removeEchoedLinePrefix("zorbleTriple(x);", "return MathHelpers.z", known))
        // Unknown identifier: the letter may be the start of a new name, so it is kept.
        assertEquals("zorbleTriple(x);", CompletionPostProcessor.removeEchoedLinePrefix("zorbleTriple(x);", "return MathHelpers.z", ""))
        // `xxs` is not an echo of `x` when `xxs` is the known name.
        assertEquals("xs.size", CompletionPostProcessor.removeEchoedLinePrefix("xs.size", "return x", "val xs = 1; val xxs = 2"))
    }

    @Test
    fun `does not indent the first line twice and indents following lines`() {
        val raw = "if (x) {\n    return 1;\n}"
        val result = CompletionPostProcessor.process(raw, ctx("class A {\n        "), singleLine = false, instructMode = true)
        assertEquals("if (x) {\n            return 1;\n        }", result)
    }

    @Test
    fun `keeps absolute indentation written by the model`() {
        val raw = "        if (x) {\n            return 1;\n        }"
        val result = CompletionPostProcessor.process(raw, ctx("class A {\n        "), singleLine = false, instructMode = true)
        assertEquals("if (x) {\n            return 1;\n        }", result)
    }

    @Test
    fun `single line mode keeps only the first line`() {
        val raw = "foo(a, b);\nbar();"
        assertEquals("foo(a, b);", CompletionPostProcessor.process(raw, ctx("    "), singleLine = true, instructMode = false))
    }

    @Test
    fun `trims closing punctuation that already follows the caret`() {
        val raw = "a, b)"
        assertEquals("a, b", CompletionPostProcessor.process(raw, ctx("    foo(", ")"), singleLine = true, instructMode = false))
    }

    @Test
    fun `trims closing punctuation and semicolon that already follow the caret`() {
        val raw = "\"hi\");"
        assertEquals("\"hi\"", CompletionPostProcessor.process(raw, ctx("    println(", ");"), singleLine = true, instructMode = false))
    }

    @Test
    fun `does not trim identifiers that happen to match the suffix`() {
        val raw = "int max"
        assertEquals("int max", CompletionPostProcessor.process(raw, ctx("    ", "x = 5"), singleLine = true, instructMode = false))
    }

    @Test
    fun `drops trailing lines that duplicate the code after the caret`() {
        val raw = "return a + b;\n    }\n}"
        val suffix = "\n    }\n}\n"
        assertEquals("return a + b;", CompletionPostProcessor.process(raw, ctx("        ", suffix), singleLine = false, instructMode = false))
    }

    @Test
    fun `returns empty when the model only repeats the suffix`() {
        val raw = "}"
        assertEquals("", CompletionPostProcessor.process(raw, ctx("        return 1;\n    ", "}\n"), singleLine = false, instructMode = false))
    }

    @Test
    fun `fim output keeps its own leading whitespace`() {
        val raw = "    return a + b;"
        assertEquals("    return a + b;", CompletionPostProcessor.process(raw, ctx("    "), singleLine = false, instructMode = false))
    }

    @Test
    fun `blank output becomes empty`() {
        assertEquals("", CompletionPostProcessor.process("  \n \n", ctx("x"), singleLine = false, instructMode = true))
    }
}
