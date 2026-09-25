package com.shubhamvasnik.ollamacomplete.vcs

import org.junit.Assert.assertEquals
import org.junit.Test

class UnifiedDiffTest {

    @Test
    fun `formats a change with context`() {
        val before = (1..10).joinToString("\n") { "l$it" } + "\n"
        val after = before.replace("l5\n", "L5\nnew\n")
        val diff = UnifiedDiff.format("a.txt", before, after, listOf(LineRange(4, 5, 4, 6)))
        assertEquals(
            "--- a/a.txt\n+++ b/a.txt\n@@ -2,7 +2,8 @@\n l2\n l3\n l4\n-l5\n+L5\n+new\n l6\n l7\n l8\n",
            diff,
        )
    }

    @Test
    fun `distant changes get separate hunks`() {
        val before = (1..30).joinToString("\n") { "l$it" }
        val after = before.replace("l2\n", "x\n").replace("l28", "y")
        val diff = UnifiedDiff.format("a.txt", before, after, listOf(LineRange(1, 2, 1, 2), LineRange(27, 28, 27, 28)))
        assertEquals(2, diff.lines().count { it.startsWith("@@ ") })
    }

    @Test
    fun `new file`() {
        assertEquals(
            "--- /dev/null\n+++ b/n.txt\n@@ -0,0 +1,2 @@\n+a\n+b\n",
            UnifiedDiff.format("n.txt", null, "a\nb\n", listOf(LineRange(0, 0, 0, 2))),
        )
    }
}
