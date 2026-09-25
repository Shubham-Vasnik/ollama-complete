package com.shubhamvasnik.ollamacomplete.completion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OllamaCompletionServiceTest {

    @Test
    fun `detects text typed from a suggestion`() {
        val old = "val total = "
        assertEquals(3, OllamaCompletionService.typedPartOf("price * count", old, "fun f() {\n    val total = pri"))
    }

    @Test
    fun `ignores unrelated typing`() {
        assertNull(OllamaCompletionService.typedPartOf("price * count", "val total = ", "val total = qu"))
        assertNull(OllamaCompletionService.typedPartOf("price * count", "val total = ", "val sum = pri"))
        // Typing the whole suggestion leaves nothing to suggest.
        assertNull(OllamaCompletionService.typedPartOf("abc", "x = ", "x = abc"))
    }
}
