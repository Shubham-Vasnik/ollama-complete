package com.shubhamvasnik.ollamacomplete.chat

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser

sealed interface Segment {
    data class Text(val markdown: String) : Segment
    data class Code(val language: String, val code: String, val closed: Boolean) : Segment
}

object MarkdownSegments {
    private val fenceOpen = Regex("^ {0,3}(```+|~~~+)\\s*([^`\\s]*).*$")

    /**
     * Splits a (possibly still streaming) Markdown message into prose and fenced code blocks.
     * An unterminated fence produces a code segment with `closed = false`.
     */
    fun split(markdown: String): List<Segment> {
        val segments = mutableListOf<Segment>()
        val text = StringBuilder()
        val code = StringBuilder()
        var fence: String? = null
        var language = ""

        fun flushText() {
            if (text.isNotBlank()) segments += Segment.Text(text.toString().trim('\n'))
            text.setLength(0)
        }

        val lines = markdown.replace("\r\n", "\n").split('\n')
        lines.forEachIndexed { index, line ->
            val isLast = index == lines.lastIndex
            val currentFence = fence
            if (currentFence == null) {
                val match = fenceOpen.matchEntire(line)
                // While streaming, the opening line is only trusted once its newline has arrived.
                if (match != null && !isLast) {
                    flushText()
                    fence = match.groupValues[1]
                    language = match.groupValues[2]
                    code.setLength(0)
                } else {
                    text.append(line).append('\n')
                }
            } else {
                val trimmed = line.trim()
                if (trimmed.startsWith(currentFence) && trimmed.trimStart(currentFence[0]).isEmpty()) {
                    segments += Segment.Code(language, code.toString().removeSuffix("\n"), closed = true)
                    fence = null
                    code.setLength(0)
                } else {
                    code.append(line).append('\n')
                }
            }
        }
        if (fence != null) {
            segments += Segment.Code(language, code.toString().removeSuffix("\n"), closed = false)
        } else {
            flushText()
        }
        return segments
    }

    private val flavour = GFMFlavourDescriptor()

    fun toHtml(markdown: String): String {
        val tree = MarkdownParser(flavour, false)
            .parse(MarkdownElementTypes.MARKDOWN_FILE, markdown, true)
        return HtmlGenerator(markdown, tree, flavour).generateHtml()
    }
}
