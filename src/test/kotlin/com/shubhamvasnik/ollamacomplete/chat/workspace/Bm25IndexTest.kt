package com.shubhamvasnik.ollamacomplete.chat.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Bm25IndexTest {

    @Test
    fun `tokenize splits identifiers and stems`() {
        assertEquals(listOf("user", "repository", "save", "user"), Bm25Index.tokenize("userRepository.saves(the users)"))
        assertEquals(listOf("http", "client", "pars", "json"), Bm25Index.tokenize("HTTPClient parsing JSON"))
    }

    @Test
    fun `finds the chunk that matches the question`() {
        val index = Bm25Index(
            listOf(
                Chunk("src/Weather.kt", 1, "fun forecast(city: City): Forecast = api.load(city)"),
                Chunk("src/UserRepository.kt", 1, "class UserRepository { fun save(user: User) = db.insert(user) }"),
                Chunk("src/Billing.kt", 1, "fun charge(user: User, amount: Money) = gateway.charge(user.card, amount)"),
            ),
        )
        val hits = index.search("where is the user saved?", 2)
        assertEquals("src/UserRepository.kt", hits.first().first.path)
        assertTrue(hits.none { it.first.path == "src/Weather.kt" })
    }

    @Test
    fun `chunks split files by lines`() {
        val text = (1..95).joinToString("\n") { "line$it" }
        val chunks = Bm25Index.chunk("a.txt", text, lines = 40)
        assertEquals(listOf(1, 41, 81), chunks.map { it.startLine })
        assertTrue(chunks.last().text.endsWith("line95"))
    }
}
