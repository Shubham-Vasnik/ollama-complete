package com.shubhamvasnik.ollamacomplete.completion

/**
 * Turns the chunks of a streamed model answer into gray text that can be shown while the model is still writing.
 *
 * Gray text can only grow, so only complete lines are shown and they are run through [CompletionPostProcessor]
 * first. Its clean-up only ever shortens the end of the text (duplicated closing lines, punctuation already after
 * the caret), so what is shown early stays a prefix of the final answer. If it ever isn't, nothing more is shown.
 */
class StreamingCompletion(
    private val context: CompletionContext,
    private val singleLine: Boolean,
    private val instructMode: Boolean,
) {
    private val raw = StringBuilder()

    /** The text shown so far. */
    var shown: String = ""
        private set

    /** True once the rest of the answer can't change the result; the request should then be stopped. */
    var finished: Boolean = false
        private set

    /** Adds a chunk of the answer and returns the text to add to the gray text (possibly empty). */
    fun append(chunk: String): String {
        if (finished) return ""
        raw.append(chunk)
        val lastNewline = raw.lastIndexOf("\n")
        if (lastNewline < 0) return ""
        val lines = raw.substring(0, lastNewline)
        val prepared = CompletionPostProcessor.prepare(lines, context, instructMode)
        finished = if (singleLine) {
            prepared.substringBefore('\n').isNotBlank()
        } else {
            CompletionPostProcessor.truncateAtBlockEnd(prepared, context.linePrefix).length < prepared.length
        }
        return advance(CompletionPostProcessor.process(lines, context, singleLine, instructMode))
    }

    /** Call when the answer is complete (or [finished]); returns the rest of the gray text. */
    fun finish(): String {
        val text = CompletionPostProcessor.process(raw.toString(), context, singleLine, instructMode)
        finished = true
        return advance(text)
    }

    /** The final text, once [finish] was called. */
    val result: String get() = shown

    private fun advance(text: String): String {
        if (!text.startsWith(shown) || text.length == shown.length) return ""
        val delta = text.substring(shown.length)
        shown = text
        return delta
    }
}
