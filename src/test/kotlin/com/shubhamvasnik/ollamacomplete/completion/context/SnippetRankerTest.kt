package com.shubhamvasnik.ollamacomplete.completion.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SnippetRankerTest {

    @Test
    fun `tokenize keeps identifiers and drops keywords`() {
        assertEquals(setOf("user", "userRepo", "findById", "id"), SnippetRanker.tokenize("val user = userRepo.findById(id) // x"))
    }

    @Test
    fun `jaccard similarity`() {
        assertEquals(1.0, SnippetRanker.jaccard(setOf("ab", "cd"), setOf("ab", "cd")), 1e-9)
        assertEquals(1.0 / 3, SnippetRanker.jaccard(setOf("ab", "cd"), setOf("ab", "ef")), 1e-9)
        assertEquals(0.0, SnippetRanker.jaccard(emptySet(), setOf("ab")), 1e-9)
    }

    @Test
    fun `picks the window of a file that matches the query`() {
        val filler = (1..100).joinToString("\n") { "unrelated()" }
        val relevant = "fun computeInvoiceTotal(invoice: Invoice): Money = invoice.lines.sumOf { it.price }"
        val file = CandidateFile("Billing.kt", "$filler\n$relevant\n$filler")
        val snippets = SnippetRanker.rank("val total = computeInvoiceTotal(invoice)", listOf(file), 4, 10_000)
        assertEquals(1, snippets.size)
        assertTrue(snippets[0].text.contains(relevant))
        assertEquals(SnippetRanker.WINDOW_LINES, snippets[0].text.split('\n').size)
    }

    @Test
    fun `ranks files by similarity and ignores unrelated ones`() {
        val query = "customer.email customer.address sendReceipt(customer)"
        val files = listOf(
            CandidateFile("Weather.kt", "fun forecast(city: City) = sunny"),
            CandidateFile("Mailer.kt", "fun sendReceipt(customer: Customer) = send(customer.email)"),
            CandidateFile("Customer.kt", "class Customer(val email: String, val address: Address)\nfun sendReceipt(customer: Customer)"),
        )
        val snippets = SnippetRanker.rank(query, files, 4, 10_000)
        assertEquals(listOf("Customer.kt", "Mailer.kt"), snippets.map { it.path })
    }

    @Test
    fun `budget limits count and size`() {
        val ranked = listOf(
            Snippet("a", "aaaa\naaaa", 0.9),
            Snippet("b", "bbbb\nbbbb\nbbbb", 0.8),
            Snippet("c", "cccc", 0.7),
        )
        assertEquals(listOf("a"), SnippetRanker.fitToBudget(ranked, 1, 1000).map { it.path })
        // "b" does not fit whole, so it is cut at a line boundary and nothing after it is added.
        val cut = SnippetRanker.fitToBudget(ranked, 3, 20)
        assertEquals(listOf("a", "b"), cut.map { it.path })
        assertEquals("bbbb\nbbbb", cut[1].text)
    }

    @Test
    fun `query is the last lines of the prefix`() {
        val prefix = (1..100).joinToString("\n") { "l$it" }
        val query = SnippetRanker.queryText(prefix)
        assertEquals(SnippetRanker.WINDOW_LINES, query.split('\n').size)
        assertTrue(query.endsWith("l100"))
    }
}
