package com.shubhamvasnik.ollamacomplete.completion

/** Text around the caret that is sent to the model. */
data class CompletionContext(
    val prefix: String,
    val suffix: String,
    val fileName: String,
    val language: String,
) {
    /** Text of the caret line before the caret. */
    val linePrefix: String get() = prefix.substringAfterLast('\n')

    /** Text of the caret line after the caret. */
    val lineSuffix: String get() = suffix.substringBefore('\n')

    /** Completing in the middle of a line (code after the caret) should only produce a single line. */
    val isMidLine: Boolean get() = lineSuffix.isNotBlank() && lineSuffix.trim().any { it.isLetterOrDigit() }
}

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

    fun instructPrompt(context: CompletionContext): String = buildString {
        append("File: ").append(context.fileName)
        if (context.language.isNotBlank()) append(" (").append(context.language).append(')')
        append("\n\n")
        append(context.prefix).append(CURSOR).append(context.suffix)
    }

    fun stopSequences(singleLine: Boolean): List<String> =
        if (singleLine) listOf("\n") else listOf("\n\n\n")
}
