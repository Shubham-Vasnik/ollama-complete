package com.shubhamvasnik.ollamacomplete.chat

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.BrowserHyperlinkListener
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JPanel

/** One chat bubble. Assistant messages are re-rendered as the answer streams in. */
class MessageComponent(
    private val project: Project,
    role: Role,
    title: String,
    icon: Icon?,
    parentDisposable: Disposable,
) : JPanel(BorderLayout()), Disposable {

    enum class Role { USER, ASSISTANT }

    private val body = JPanel(VerticalLayout(JBUI.scale(6))).apply { isOpaque = false }
    private val views = mutableListOf<SegmentView>()
    private val thinking = ThinkingView()
    private val status = JBLabel().apply {
        foreground = UIUtil.getContextHelpForeground()
        font = JBUI.Fonts.smallFont()
        isVisible = false
    }

    init {
        Disposer.register(parentDisposable, this)
        isOpaque = true
        background = if (role == Role.USER) USER_BACKGROUND else UIUtil.getPanelBackground()
        border = JBUI.Borders.compound(
            JBUI.Borders.customLineBottom(JBColor.border()),
            JBUI.Borders.empty(8, 10),
        )
        val header = JBLabel(title, icon, JBLabel.LEFT).apply {
            font = JBUI.Fonts.label().asBold()
            border = JBUI.Borders.emptyBottom(4)
        }
        add(header, BorderLayout.NORTH)
        add(JPanel(VerticalLayout(JBUI.scale(4))).apply {
            isOpaque = false
            add(thinking.component)
            add(body)
            add(status)
        }, BorderLayout.CENTER)
    }

    fun setContent(markdown: String, thinkingText: String = "", streaming: Boolean = false) {
        thinking.update(thinkingText, streaming && markdown.isEmpty())
        val segments = MarkdownSegments.split(markdown)
        segments.forEachIndexed { index, segment ->
            val existing = views.getOrNull(index)
            if (existing != null && existing.accepts(segment)) {
                existing.update(segment)
            } else {
                removeViewsFrom(index)
                val view = createView(segment)
                views += view
                body.add(view.component)
                view.update(segment)
            }
        }
        removeViewsFrom(segments.size)
        setStatus(if (streaming && markdown.isEmpty() && thinkingText.isEmpty()) "Waiting for the model…" else null)
        revalidate()
        repaint()
    }

    fun setError(message: String) {
        setStatus(null)
        val label = JBLabel("<html>${com.intellij.openapi.util.text.StringUtil.escapeXmlEntities(message)}</html>").apply {
            foreground = JBColor.RED
        }
        body.add(label)
        revalidate()
        repaint()
    }

    fun setStatus(text: String?) {
        status.text = text.orEmpty()
        status.isVisible = !text.isNullOrEmpty()
    }

    private fun createView(segment: Segment): SegmentView = when (segment) {
        is Segment.Text -> TextView()
        is Segment.Code -> CodeView(CodeBlockView(project, segment.language).also { Disposer.register(this, it) })
    }

    private fun removeViewsFrom(index: Int) {
        while (views.size > index) {
            val view = views.removeAt(views.lastIndex)
            body.remove(view.component)
            view.dispose()
        }
    }

    override fun dispose() {
        views.clear()
    }

    private interface SegmentView {
        val component: JComponent
        fun accepts(segment: Segment): Boolean
        fun update(segment: Segment)
        fun dispose() {}
    }

    private class TextView : SegmentView {
        private var lastMarkdown: String? = null
        override val component = createHtmlPane()
        override fun accepts(segment: Segment) = segment is Segment.Text
        override fun update(segment: Segment) {
            val markdown = (segment as Segment.Text).markdown
            if (markdown == lastMarkdown) return
            lastMarkdown = markdown
            component.text = MarkdownSegments.toHtml(markdown)
        }
    }

    private class CodeView(private val view: CodeBlockView) : SegmentView {
        override val component: JComponent = view.component
        override fun accepts(segment: Segment) = segment is Segment.Code && segment.language == view.language
        override fun update(segment: Segment) = view.update((segment as Segment.Code).code)
        override fun dispose() = Disposer.dispose(view)
    }

    /** Collapsible reasoning trace for models that stream `thinking`. */
    private class ThinkingView {
        private var expanded = false
        private val toggle = JBLabel().apply {
            foreground = UIUtil.getContextHelpForeground()
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }
        private val text = JBTextArea().apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            isOpaque = false
            foreground = UIUtil.getContextHelpForeground()
            isVisible = false
        }
        val component = JPanel(BorderLayout()).apply {
            isOpaque = false
            isVisible = false
            add(toggle, BorderLayout.NORTH)
            add(text, BorderLayout.CENTER)
        }
        private var inProgress = false

        init {
            toggle.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    expanded = !expanded
                    refresh()
                }
            })
        }

        fun update(value: String, inProgress: Boolean) {
            this.inProgress = inProgress
            component.isVisible = value.isNotBlank()
            if (text.text != value.trim()) text.text = value.trim()
            refresh()
        }

        private fun refresh() {
            val arrow = if (expanded) "▾" else "▸"
            toggle.text = "$arrow " + if (inProgress) "Thinking…" else "Thoughts"
            text.isVisible = expanded
            component.revalidate()
        }
    }

    companion object {
        private val USER_BACKGROUND = JBColor.namedColor("OllamaComplete.userMessageBackground", JBColor(0xF0F3F8, 0x2A2D33))

        fun createHtmlPane(): JEditorPane {
            val kit = HTMLEditorKitBuilder().withWordWrapViewFactory().build()
            val editorFont = EditorColorsManager.getInstance().globalScheme.editorFontName
            kit.styleSheet.addRule("code { font-family: '$editorFont'; }")
            kit.styleSheet.addRule("pre { font-family: '$editorFont'; }")
            kit.styleSheet.addRule("p { margin-top: 0; margin-bottom: 6px; }")
            kit.styleSheet.addRule("ul, ol { margin-top: 0; margin-bottom: 6px; }")
            return JEditorPane().apply {
                editorKit = kit
                isEditable = false
                isOpaque = false
                border = JBUI.Borders.empty()
                putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
                font = JBUI.Fonts.label()
                addHyperlinkListener(BrowserHyperlinkListener.INSTANCE)
            }
        }
    }
}
