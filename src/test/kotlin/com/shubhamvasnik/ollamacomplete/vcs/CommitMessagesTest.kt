package com.shubhamvasnik.ollamacomplete.vcs

import org.junit.Assert.assertEquals
import org.junit.Test

class CommitMessagesTest {

    @Test
    fun `removes what models add around the message`() {
        assertEquals("Add login page", CommitMessages.clean("<think>hmm</think>\n```\nAdd login page\n```"))
        assertEquals("Fix crash on start", CommitMessages.clean("Commit message: \"Fix crash on start\""))
        assertEquals("Add x\n\nBecause y.", CommitMessages.clean("  Add x\n\nBecause y.  "))
    }
}
