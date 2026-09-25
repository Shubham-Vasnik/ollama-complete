package com.shubhamvasnik.ollamacomplete.completion

import com.shubhamvasnik.ollamacomplete.ProjectInstructions
import com.shubhamvasnik.ollamacomplete.completion.context.Snippet

/** Text around the caret that is sent to the model. */
data class CompletionContext(
    val prefix: String,
    val suffix: String,
    val fileName: String,
    val language: String,
    /** Path of the file relative to the project, shown to the model. */
    val filePath: String = fileName,
    /** Related code from other files, best first. */
    val snippets: List<Snippet> = emptyList(),
    /** Line comment prefix of the file's language (`//`, `#`), used to show snippets to FIM models. */
    val commentPrefix: String? = null,
    /** Project instructions for instruct models, see [com.shubhamvasnik.ollamacomplete.ProjectInstructions]. */
    val instructions: String? = null,
) {
    /** Text of the caret line before the caret. */
    val linePrefix: String get() = prefix.substringAfterLast('\n')

    /** Text of the caret line after the caret. */
    val lineSuffix: String get() = suffix.substringBefore('\n')

    /** Completing in the middle of a line (code after the caret) should only produce a single line. */
    val isMidLine: Boolean get() = lineSuffix.isNotBlank() && lineSuffix.trim().any { it.isLetterOrDigit() }

    /**
     * Like Copilot, several lines are only suggested where a block or a new statement starts: on an empty line or
     * after an opening bracket, `:`, `=>` or `->`. In the middle of a statement one line is enough, and it is faster.
     */
    val allowsMultiLine: Boolean
        get() {
            if (isMidLine) return false
            val code = linePrefix.trim()
            return code.isEmpty() || MULTI_LINE_OPENERS.any { code.endsWith(it) }
        }

    /** All code the model was shown, to check whether an identifier it wrote exists. */
    fun knownCode(): String = buildString {
        append(prefix).append('\n').append(suffix)
        snippets.forEach { append('\n').append(it.text) }
    }
}

private val MULTI_LINE_OPENERS = listOf("{", "(", "[", ":", "=>", "->")

object CompletionPromptBuilder {
    const val CURSOR = "<CURSOR>"

    const val INSTRUCT_SYSTEM_PROMPT =
        "You are a code completion engine inside an IDE. You receive a source file containing a $CURSOR marker. " +
            "Reply with ONLY the exact text to insert at $CURSOR so the code compiles and the current statement or block " +
            "is complete, including punctuation such as semicolons or closing brackets that are missing. " +
            "Do not repeat any code that appears before or after $CURSOR. " +
            "No explanations, no comments about the task, no Markdown code fences. " +
            "If nothing should be inserted, reply with an empty response."

    fun extract(
        text: CharSequence,
        offset: Int,
        maxPrefixChars: Int,
        maxSuffixChars: Int,
        fileName: String,
        language: String,
    ): CompletionContext {
        val caret = offset.coerceIn(0, text.length)
        var prefixStart = (caret - maxPrefixChars.coerceAtLeast(0)).coerceAtLeast(0)
        if (prefixStart > 0) {
            // Start at a line boundary so the model never sees half a line.
            val nextLine = text.indexOf('\n', prefixStart)
            if (nextLine in 0 until caret) prefixStart = nextLine + 1
        }
        var suffixEnd = (caret + maxSuffixChars.coerceAtLeast(0)).coerceAtMost(text.length)
        if (suffixEnd < text.length) {
            val lastLine = text.lastIndexOf('\n', suffixEnd)
            if (lastLine > caret) suffixEnd = lastLine
        }
        return CompletionContext(
            prefix = text.subSequence(prefixStart, caret).toString(),
            suffix = text.subSequence(caret, suffixEnd).toString(),
            fileName = fileName,
            language = language,
        )
    }

    /**
     * The FIM prompt: [CompletionContext.prefix], preceded by the related snippets written as line comments, the way
     * Copilot does it. Without a line comment syntax the snippets can't be shown without breaking the code, so they are left out.
     */
    fun fimPrompt(context: CompletionContext): String {
        val comment = context.commentPrefix?.trimEnd()?.takeIf { it.isNotEmpty() }
        if (context.snippets.isEmpty() || comment == null) return context.prefix
        return buildString {
            append(comment).append(" Path: ").append(context.filePath).append('\n')
            for (snippet in context.snippets) {
                append(comment).append(" Compare this snippet from ").append(snippet.path).append(":\n")
                snippet.text.split('\n').forEach { line ->
                    append(comment)
                    if (line.isNotEmpty()) append(' ').append(line)
                    append('\n')
                }
            }
            append(context.prefix)
        }
    }

    fun instructSystemPrompt(context: CompletionContext): String =
        ProjectInstructions.appendTo(INSTRUCT_SYSTEM_PROMPT, context.instructions)

    fun instructPrompt(context: CompletionContext): String = buildString {
        if (context.snippets.isNotEmpty()) {
            append("Related code from other files (for reference only, do not repeat it):\n\n")
            for (snippet in context.snippets) {
                append("--- ").append(snippet.path).append(" ---\n").append(snippet.text).append("\n\n")
            }
        }
        append("File: ").append(context.filePath)
        if (context.language.isNotBlank()) append(" (").append(context.language).append(')')
        append("\n\n")
        append(context.prefix).append(CURSOR).append(context.suffix)
    }

    fun stopSequences(singleLine: Boolean): List<String> =
        if (singleLine) listOf("\n") else listOf("\n\n\n")
}
