package com.shubhamvasnik.ollamacomplete.chat.commands

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatReferencesTest {

    @Test
    fun `finds references`() {
        val refs = ChatReferences.parse("Why does #file:src/Foo.kt fail? See #problems and #selection, or ask @workspace.")
        assertEquals(
            listOf(ChatReference.File("src/Foo.kt"), ChatReference.Problems, ChatReference.Selection, ChatReference.Workspace),
            refs,
        )
    }

    @Test
    fun `ignores lookalikes`() {
        assertEquals(emptyList<ChatReference>(), ChatReferences.parse("issue#selection, user@workspace.com, #selections"))
    }

    @Test
    fun `strips references from the question`() {
        assertEquals("where is the user saved?", ChatReferences.stripReferences("@workspace where is the user saved?"))
    }

    @Test
    fun `message has context, then command prompt, then the question`() {
        val message = ChatReferences.buildMessage(
            listOf(Attachment("Selected code from `Foo.kt`", "val x = 1\n", "kt")),
            "Explain the code.",
            "  Focus on x.  ",
        )
        assertEquals("Selected code from `Foo.kt`:\n```kt\nval x = 1\n```\n\nExplain the code.\n\nFocus on x.", message)
        assertEquals("Hi", ChatReferences.buildMessage(emptyList(), null, "Hi"))
    }
}
