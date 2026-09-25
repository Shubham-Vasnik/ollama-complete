package com.shubhamvasnik.ollamacomplete.chat.commands

/** Context a chat message asks for, written as `#file:Foo.kt`, `#selection`, `#problems` or `@workspace`. */
sealed interface ChatReference {
    data class File(val path: String) : ChatReference
    data object Selection : ChatReference
    data object Problems : ChatReference
    data object Workspace : ChatReference
}

/**
 * Something attached to a chat message: a file, the selection, the problems in a file, workspace search hits.
 * [label] introduces it to the model; [name] is the short form shown in the chat.
 */
data class Attachment(val label: String, val content: String, val language: String = "", val name: String = label)

object ChatReferences {
    private val pattern = Regex("(?<=^|\\s)(#file:([^\\s,;!?]+)|#selection|#problems|@workspace)(?=$|[\\s,.;:!?])")

    fun parse(text: String): List<ChatReference> =
        pattern.findAll(text).map { match ->
            val token = match.groupValues[1]
            when {
                token.startsWith("#file:") -> ChatReference.File(match.groupValues[2].trimEnd('.', ':'))
                token == "#selection" -> ChatReference.Selection
                token == "#problems" -> ChatReference.Problems
                else -> ChatReference.Workspace
            }
        }.distinct().toList()

    /** The question without the reference tokens, e.g. for searching the workspace. */
    fun stripReferences(text: String): String = pattern.replace(text, "").replace(Regex("[ \\t]{2,}"), " ").trim()

    /**
     * The message the model sees: the attached context first, then the command's prompt, then the user's text.
     */
    fun buildMessage(attachments: List<Attachment>, commandPrompt: String?, userText: String): String = buildString {
        for (attachment in attachments) {
            append(attachment.label).append(":\n")
            append("```").append(attachment.language).append('\n')
            append(attachment.content.trimEnd()).append("\n```\n\n")
        }
        if (!commandPrompt.isNullOrBlank()) append(commandPrompt.trim()).append("\n\n")
        append(userText.trim())
    }.trim()
}
