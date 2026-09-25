package com.shubhamvasnik.ollamacomplete.chat.apply

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CodeMergerTest {

    private val java = listOf(
        "package demo;",
        "",
        "public class Util {",
        "    /** Old doc. */",
        "    public static int max(int a, int b) {",
        "        if (a > b) {",
        "            return a;",
        "        }",
        "        return b;",
        "    }",
        "",
        "    public static int min(int a, int b) {",
        "        return a < b ? a : b;",
        "    }",
        "}",
        "",
    ).joinToString("\n")

    @Test
    fun `replaces the matching method and keeps the file's indentation`() {
        val block = "/** Returns the larger value. */\npublic static int max(int a, int b) {\n    return Math.max(a, b);\n}"
        val expected = java.replace(
            "    /** Old doc. */\n    public static int max(int a, int b) {\n        if (a > b) {\n            return a;\n        }\n        return b;\n    }",
            "    /** Returns the larger value. */\n    public static int max(int a, int b) {\n        return Math.max(a, b);\n    }",
        )
        assertEquals(expected, CodeMerger.merge(java, block))
    }

    @Test
    fun `replaces a python function by indentation`() {
        val py = "def double(x):\n    y = x\n    return y * 2\n\n\ndef other():\n    pass\n"
        val merged = CodeMerger.merge(py, "def double(x):\n    return x * 2")
        assertEquals("def double(x):\n    return x * 2\n\n\ndef other():\n    pass\n", merged)
    }

    @Test
    fun `a whole file replaces the file`() {
        val block = java.replace("return a < b ? a : b;", "return Math.min(a, b);").trimEnd()
        assertEquals(block + "\n", CodeMerger.merge(java, block))
    }

    @Test
    fun `keeps Windows line endings`() {
        val merged = CodeMerger.merge(java.replace("\n", "\r\n"), "public static int min(int a, int b) {\n    return 0;\n}")!!
        assertEquals(merged.count { it == '\n' }, merged.split("\r\n").size - 1)
    }

    @Test
    fun `gives up when the declaration is missing or ambiguous`() {
        assertNull(CodeMerger.merge(java, "public static int clamp(int x) {\n    return x;\n}"))
        assertNull(CodeMerger.merge(java, "return a + b;"))
        assertNull(CodeMerger.merge("fun a() {}\nfun a() {}\nfun b() {}\nfun c() {}\n", "fun a() { 1 }"))
    }

    @Test
    fun `declaration keys`() {
        assertEquals("public static int max", CodeMerger.declarationKey("    public static int max(int a, int b) {"))
        assertEquals("fun load", CodeMerger.declarationKey("fun load(): String {"))
        assertEquals("class Foo", CodeMerger.declarationKey("class Foo : Bar {"))
        assertNull(CodeMerger.declarationKey("return max(a, b);"))
        assertNull(CodeMerger.declarationKey("foo(a)"))
    }
}
