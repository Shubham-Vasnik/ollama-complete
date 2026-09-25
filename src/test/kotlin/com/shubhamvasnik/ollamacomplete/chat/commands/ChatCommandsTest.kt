package com.shubhamvasnik.ollamacomplete.chat.commands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatCommandsTest {

    @Test
    fun `parses a command and the rest of the message`() {
        val (command, rest) = ChatCommands.parse("/fix the null check please", ChatCommands.BUILT_IN)!!
        assertEquals("fix", command.name)
        assertEquals("the null check please", rest)
        assertEquals("", ChatCommands.parse("  /TESTS", ChatCommands.BUILT_IN)!!.second)
    }

    @Test
    fun `unknown commands and slashes elsewhere are not commands`() {
        assertNull(ChatCommands.parse("/nope do it", ChatCommands.BUILT_IN))
        assertNull(ChatCommands.parse("what does a/b do", ChatCommands.BUILT_IN))
        assertNull(ChatCommands.parse("/fixed", ChatCommands.BUILT_IN))
    }

    @Test
    fun `prompt files become commands`() {
        val withTitle = ChatCommands.fromPromptFile("review.md", "# Review for our style\nCheck naming and logging.")!!
        assertEquals(SlashCommand("review", "Review for our style", "Check naming and logging."), withTitle)
        assertEquals("Prompt from perf.md", ChatCommands.fromPromptFile("perf.md", "Find slow code.")!!.description)
        assertNull(ChatCommands.fromPromptFile("empty.md", "  \n"))
        assertNull(ChatCommands.fromPromptFile("title-only.md", "# Just a title"))
    }

    @Test
    fun `project prompt files override built-ins`() {
        val custom = SlashCommand("tests", "Our tests", "Use JUnit 5.")
        val merged = ChatCommands.merge(ChatCommands.BUILT_IN, listOf(custom))
        assertEquals(custom, merged.single { it.name == "tests" })
        assertEquals(ChatCommands.BUILT_IN.size, merged.size)
    }
}
