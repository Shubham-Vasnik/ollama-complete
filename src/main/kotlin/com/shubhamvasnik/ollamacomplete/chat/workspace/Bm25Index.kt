package com.shubhamvasnik.ollamacomplete.chat.workspace

import kotlin.math.ln

/** A few lines of a project file that can be returned for `@workspace`. */
data class Chunk(val path: String, val startLine: Int, val text: String)

/**
 * Keyword search over chunks of the project with BM25, the ranking function classic search engines use. Identifiers
 * are split into words (`userRepository` → `user`, `repository`) so a question in plain words finds the code.
 */
class Bm25Index(private val chunks: List<Chunk>) {
    private val docs: List<Map<String, Int>> = chunks.map { chunk ->
        // The file path counts too, so "where is the billing service" finds BillingService.kt.
        termCounts(tokenize(chunk.text) + tokenize(chunk.path) + tokenize(chunk.path))
    }
    private val docFreq: Map<String, Int> = HashMap<String, Int>().also { df ->
        docs.forEach { doc -> doc.keys.forEach { df.merge(it, 1, Int::plus) } }
    }
    private val lengths = docs.map { it.values.sum() }
    private val avgLength = if (lengths.isEmpty()) 0.0 else lengths.average()

    val size: Int get() = chunks.size

    fun search(query: String, limit: Int): List<Pair<Chunk, Double>> {
        val terms = tokenize(query).distinct()
        if (terms.isEmpty() || chunks.isEmpty()) return emptyList()
        val n = chunks.size.toDouble()
        return docs.indices.asSequence()
            .map { i ->
                val doc = docs[i]
                var score = 0.0
                for (term in terms) {
                    val tf = doc[term] ?: continue
                    val df = docFreq[term] ?: continue
                    val idf = ln(1 + (n - df + 0.5) / (df + 0.5))
                    score += idf * tf * (K1 + 1) / (tf + K1 * (1 - B + B * lengths[i] / avgLength))
                }
                i to score
            }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(limit)
            .map { chunks[it.first] to it.second }
            .toList()
    }

    companion object {
        private const val K1 = 1.2
        private const val B = 0.75

        private val word = Regex("[A-Za-z][A-Za-z0-9]*")
        private val camelBoundary = Regex("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])")
        private val stopWords = setOf(
            "the", "a", "an", "is", "are", "was", "be", "to", "of", "in", "on", "for", "and", "or", "it", "this",
            "that", "what", "where", "which", "who", "how", "why", "when", "does", "do", "did", "can", "i", "we",
            "you", "my", "our", "with", "from", "by", "as", "at", "val", "var", "fun", "def", "return", "import",
            "public", "private", "class", "new", "if", "else", "null", "true", "false", "workspace",
        )

        fun tokenize(text: String): List<String> =
            word.findAll(text).flatMap { match -> match.value.split(camelBoundary).asSequence() }
                .map { stem(it.lowercase()) }
                .filter { it.length > 1 && it !in stopWords }
                .toList()

        /** A very small stemmer so "saves", "saved" and "saving" all match "save". */
        internal fun stem(word: String): String = when {
            word.length > 5 && word.endsWith("ing") -> word.dropLast(3)
            word.length > 4 && word.endsWith("ed") -> word.dropLast(2)
            word.length > 4 && word.endsWith("es") && !word.endsWith("ses") -> word.dropLast(1)
            word.length > 3 && word.endsWith("s") && !word.endsWith("ss") -> word.dropLast(1)
            else -> word
        }

        private fun termCounts(tokens: List<String>): Map<String, Int> =
            HashMap<String, Int>().also { counts -> tokens.forEach { counts.merge(it, 1, Int::plus) } }

        /** Splits a file into chunks of [lines] lines. */
        fun chunk(path: String, text: String, lines: Int = 40): List<Chunk> {
            val all = text.replace("\r\n", "\n").split('\n')
            return all.indices.step(lines).mapNotNull { start ->
                val body = all.subList(start, minOf(start + lines, all.size)).joinToString("\n")
                if (body.isBlank()) null else Chunk(path, start + 1, body)
            }
        }
    }
}
