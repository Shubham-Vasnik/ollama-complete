package com.shubhamvasnik.ollamacomplete.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownSegmentsTest {

    @Test
    fun `splits prose and code blocks`() {
        val md = "Here you go:\n\n```kotlin\nfun a() = 1\n```\n\nDone."
        assertEquals(
            listOf(
                Segment.Text("Here you go:"),
                Segment.Code("kotlin", "fun a() = 1", closed = true),
                Segment.Text("Done."),
            ),
            MarkdownSegments.split(md),
        )
    }

    @Test
    fun `unterminated fence while streaming is an open code block`() {
        val md = "Text\n```java\nclass A {"
        assertEquals(
            listOf(Segment.Text("Text"), Segment.Code("java", "class A {", closed = false)),
            MarkdownSegments.split(md),
        )
    }

    @Test
    fun `fence opening line without newline is still text`() {
        assertEquals(listOf(Segment.Text("Text\n```ja")), MarkdownSegments.split("Text\n```ja"))
    }

    @Test
    fun `markdown renders to html`() {
        val html = MarkdownSegments.toHtml("**bold** and `code`")
        assertTrue(html, html.contains("<strong>bold</strong>"))
        assertTrue(html, html.contains("<code>code</code>"))
    }
}
