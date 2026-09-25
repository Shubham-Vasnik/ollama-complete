package com.shubhamvasnik.ollamacomplete.completion.context

/** A piece of another file that looks related to the code around the caret. */
data class Snippet(val path: String, val text: String, val score: Double)

/** Another file that may contain useful context, e.g. an open editor tab. */
data class CandidateFile(val path: String, val text: String)

/**
 * Picks the parts of other files that are most similar to the code before the caret, the way Copilot's
 * "neighboring tabs" do: every file is cut into overlapping windows of lines, each window is scored by the
 * Jaccard similarity of its identifiers with the caret's surroundings, and the best window of each file competes
 * for a place in the prompt.
 */
object SnippetRanker {
    const val WINDOW_LINES = 60
    const val WINDOW_STEP = 10
    const val MIN_SCORE = 0.03

    private val identifier = Regex("[A-Za-z_][A-Za-z0-9_]*")

    /** Keywords shared by most languages. They make every window look alike, so they are not counted. */
    private val stopWords = setOf(
        "if", "else", "for", "while", "do", "return", "val", "var", "let", "const", "fun", "function", "def", "class",
        "interface", "public", "private", "protected", "internal", "static", "final", "void", "new", "this", "self",
        "true", "false", "null", "nil", "None", "import", "package", "from", "as", "in", "is", "and", "or", "not",
        "int", "string", "String", "override", "the", "a", "to", "of",
    )

    fun tokenize(text: CharSequence): Set<String> =
        identifier.findAll(text).map { it.value }.filterTo(HashSet()) { it.length > 1 && it !in stopWords }

    fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val (small, large) = if (a.size <= b.size) a to b else b to a
        val intersection = small.count { it in large }
        return intersection.toDouble() / (a.size + b.size - intersection)
    }

    /** The last [WINDOW_LINES] lines before the caret, which the windows of other files are compared against. */
    fun queryText(prefix: String): String {
        val lines = prefix.split('\n')
        return lines.takeLast(WINDOW_LINES).joinToString("\n")
    }

    /**
     * Returns at most [maxSnippets] snippets, best first, whose total length stays within [maxChars].
     * Each file contributes at most its best window.
     */
    fun rank(query: String, files: List<CandidateFile>, maxSnippets: Int, maxChars: Int): List<Snippet> {
        val queryTokens = tokenize(query)
        if (queryTokens.isEmpty() || maxSnippets <= 0 || maxChars <= 0) return emptyList()
        val best = files.mapNotNull { bestWindow(it, queryTokens) }.sortedByDescending { it.score }
        return fitToBudget(best, maxSnippets, maxChars)
    }

    internal fun bestWindow(file: CandidateFile, queryTokens: Set<String>): Snippet? {
        val lines = file.text.replace("\r\n", "\n").split('\n')
        val lineTokens = lines.map(::tokenize)
        var bestScore = 0.0
        var bestStart = -1
        var start = 0
        while (true) {
            val end = minOf(start + WINDOW_LINES, lines.size)
            val tokens = HashSet<String>()
            for (i in start until end) tokens += lineTokens[i]
            val score = jaccard(queryTokens, tokens)
            if (score > bestScore) {
                bestScore = score
                bestStart = start
            }
            if (end >= lines.size) break
            start += WINDOW_STEP
        }
        if (bestStart < 0 || bestScore < MIN_SCORE) return null
        val text = lines.subList(bestStart, minOf(bestStart + WINDOW_LINES, lines.size)).joinToString("\n").trim('\n')
        return if (text.isBlank()) null else Snippet(file.path, text, bestScore)
    }

    /** Keeps the best snippets that fit; a snippet that is too long is cut at a line boundary rather than dropped. */
    internal fun fitToBudget(ranked: List<Snippet>, maxSnippets: Int, maxChars: Int): List<Snippet> {
        val result = mutableListOf<Snippet>()
        var remaining = maxChars
        for (snippet in ranked) {
            if (result.size >= maxSnippets || remaining <= 0) break
            if (snippet.text.length <= remaining) {
                result += snippet
                remaining -= snippet.text.length
            } else {
                val cut = snippet.text.take(remaining).substringBeforeLast('\n', "")
                if (cut.isNotBlank()) {
                    result += snippet.copy(text = cut)
                    remaining -= cut.length
                }
                break
            }
        }
        return result
    }
}
