package com.shubhamvasnik.ollamacomplete.completion

/** Turns raw model output into text that can be inserted at the caret as-is. */
object CompletionPostProcessor {
    private val thinkBlock = Regex("(?s)<think>.*?</think>")
    private val fence = Regex("(?s)```[\\w+#.-]*[^\\S\\n]*\\n?(.*?)(?:\\n?```|$)")

    fun process(raw: String, context: CompletionContext, singleLine: Boolean, instructMode: Boolean): String {
        var text = prepare(raw, context, instructMode)
        text = if (singleLine) text.substringBefore('\n') else truncateAtBlockEnd(text, context.linePrefix)
        text = text.trimEnd()
        if (text.isBlank()) return ""
        text = trimSuffixOverlap(text, context.suffix)
        return if (text.isBlank()) "" else text
    }

    /** The clean-up that does not depend on where the completion ends: think blocks, fences, echoes, indentation. */
    internal fun prepare(raw: String, context: CompletionContext, instructMode: Boolean): String {
        var text = raw.replace("\r\n", "\n")
        text = thinkBlock.replace(text, "")
        if (instructMode) {
            text = stripFences(text)
            text = text.replace(CompletionPromptBuilder.CURSOR, "")
            text = removeEchoedLinePrefix(text, context.linePrefix, context.knownCode())
            text = reindent(text, context.linePrefix)
        }
        return text
    }

    /**
     * Cuts a multi-line completion where the block at the caret ends, like Copilot does, so the model can't go on to
     * write the next method. A line indented less than the caret line ends the block; after a block opener
     * (`{`, `:`) a line at the caret line's own indentation does too. A line of only closing brackets is kept,
     * [trimSuffixOverlap] drops it again if the editor already has it.
     */
    internal fun truncateAtBlockEnd(text: String, linePrefix: String): String {
        val base = indentWidth(linePrefix)
        val code = linePrefix.trimEnd()
        val opensBlock = code.endsWith('{') || code.endsWith(':')
        if (base == 0 && !opensBlock) return text
        val lines = text.split('\n')
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isBlank()) continue
            val indent = indentWidth(line)
            if (indent < base || (opensBlock && indent <= base)) {
                val keep = if (line.trim().all { it in CLOSING }) i + 1 else i
                return lines.take(keep).joinToString("\n")
            }
        }
        return text
    }

    private const val CLOSING = "})];,"

    /** Tabs count as 4 columns so mixed indentation still compares sensibly. */
    private fun indentWidth(line: String): Int =
        line.takeWhile { it == ' ' || it == '\t' }.sumOf { if (it == '\t') 4 else 1 }

    /** Instruct models like to wrap answers in ``` fences even when told not to. */
    internal fun stripFences(text: String): String {
        if (!text.contains("```")) return text
        val match = fence.find(text) ?: return text
        return match.groupValues[1]
    }

    /**
     * Instruct models sometimes repeat the start of the caret line (`int x = 5;` when the line already has `int x = `)
     * or the partially typed word (`getName()` after `user.get`).
     */
    internal fun removeEchoedLinePrefix(text: String, linePrefix: String, knownCode: String = ""): String {
        val code = linePrefix.trimStart()
        val trimmed = text.trimStart()
        if (code.length >= 2 && trimmed.startsWith(code)) return trimmed.removePrefix(code)
        val word = linePrefix.takeLastWhile(::isIdentifierChar)
        if (word.isEmpty() || !text.startsWith(word)) return text
        if (word.length >= 2) return text.removePrefix(word)
        // A single typed letter is only an echo if the identifier the model wrote exists and the doubled one doesn't,
        // e.g. `MathHelpers.z` + `zorbleTriple`.
        val identifier = text.takeWhile(::isIdentifierChar)
        return if (isKnownIdentifier(identifier, knownCode) && !isKnownIdentifier(word + identifier, knownCode)) {
            text.removePrefix(word)
        } else text
    }

    private fun isIdentifierChar(c: Char) = c.isLetterOrDigit() || c == '_'

    private fun isKnownIdentifier(identifier: String, code: String): Boolean =
        identifier.length >= 2 && Regex("(?<![\\w])${Regex.escape(identifier)}(?![\\w])").containsMatchIn(code)

    /**
     * The caret already sits after the line's indentation, so the first line must not be indented again,
     * and following lines must carry the caret line's base indentation.
     */
    internal fun reindent(text: String, linePrefix: String): String {
        val lines = text.split('\n')
        val first = lines.first()
        val baseIndent = linePrefix.takeWhile { it == ' ' || it == '\t' }
        val newFirst = if (linePrefix.isBlank()) first.trimStart(' ', '\t') else first
        if (lines.size == 1) return newFirst

        val rest = lines.drop(1)
        val minRestIndent = rest.filter { it.isNotBlank() }
            .minOfOrNull { line -> line.takeWhile { it == ' ' || it == '\t' }.length } ?: 0
        // If the model already wrote absolute indentation keep it, otherwise shift the block to the caret line's indentation.
        val newRest = if (minRestIndent >= baseIndent.length) rest else rest.map { if (it.isBlank()) it else baseIndent + it }
        return (listOf(newFirst) + newRest).joinToString("\n")
    }

    /**
     * Removes the part of the completion that duplicates text already after the caret, e.g. a closing `)` inserted
     * by the editor, or closing braces the model re-generated.
     */
    internal fun trimSuffixOverlap(text: String, suffix: String): String {
        if (suffix.isBlank()) return text
        val completion = text.trim()
        if (completion.isNotEmpty() && suffix.trimStart().startsWith(completion)) return ""

        var result = text
        // Trailing lines that repeat the first non-blank lines after the caret.
        val suffixLines = suffix.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        val resultLines = result.split('\n')
        if (resultLines.size > 1 && suffixLines.isNotEmpty()) {
            val maxK = minOf(resultLines.size - 1, suffixLines.size, 8)
            for (k in maxK downTo 1) {
                val tail = resultLines.takeLast(k).map { it.trim() }
                if (tail == suffixLines.take(k)) {
                    result = resultLines.dropLast(k).joinToString("\n").trimEnd()
                    break
                }
            }
        }

        // End of the completion overlapping the rest of the caret line, e.g. `foo(a, b)` + existing `)`.
        // Only punctuation is trimmed so identifiers that merely end with the same letters are left alone.
        val lineSuffix = suffix.substringBefore('\n').trim()
        if (lineSuffix.isNotEmpty()) {
            for (k in minOf(lineSuffix.length, result.length) downTo 1) {
                val overlap = lineSuffix.substring(0, k)
                if (overlap.none { it.isLetterOrDigit() || it == '_' } && result.endsWith(overlap)) {
                    result = result.dropLast(k)
                    break
                }
            }
        }
        return result.trimEnd()
    }
}
