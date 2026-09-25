package com.shubhamvasnik.ollamacomplete.chat

import com.intellij.icons.AllIcons
import com.intellij.lang.Language
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.fileTypes.UnknownFileType
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.shubhamvasnik.ollamacomplete.OllamaNotifier
import com.shubhamvasnik.ollamacomplete.chat.apply.ApplyCode
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.SwingUtilities

/** A fenced code block rendered in a read-only, syntax-highlighted IDE editor with Copy / Insert / Apply actions. */
class CodeBlockView(private val project: Project, val language: String) : Disposable {

    private val document = EditorFactory.getInstance().createDocument("")
    private val editor = EditorFactory.getInstance().createViewer(document, project, EditorKind.PREVIEW) as EditorEx

    val component: JPanel = JPanel(BorderLayout())

    init {
        editor.settings.apply {
            isLineNumbersShown = false
            isLineMarkerAreaShown = false
            isFoldingOutlineShown = false
            isRightMarginShown = false
            isIndentGuidesShown = false
            isCaretRowShown = false
            isUseSoftWraps = true
            additionalLinesCount = 0
            additionalColumnsCount = 0
        }
        editor.setVerticalScrollbarVisible(false)
        editor.setHorizontalScrollbarVisible(false)
        editor.scrollPane.isWheelScrollingEnabled = false
        editor.setBorder(JBUI.Borders.empty(6, 8))
        editor.highlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(project, fileTypeFor(language))
        // Let the chat scroll when the mouse wheel is used over a code block.
        editor.contentComponent.addMouseWheelListener { e ->
            val outer = SwingUtilities.getAncestorOfClass(JScrollPane::class.java, editor.component.parent) ?: return@addMouseWheelListener
            outer.dispatchEvent(SwingUtilities.convertMouseEvent(e.component, e, outer))
        }

        val header = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = HEADER_BACKGROUND
            border = JBUI.Borders.empty(2, 8, 2, 2)
            add(JBLabel(language.ifBlank { "code" }).apply {
                foreground = UIUtil.getContextHelpForeground()
                font = JBUI.Fonts.smallFont()
            }, BorderLayout.WEST)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 2, 0)).apply {
                isOpaque = false
                add(smallButton("Copy", AllIcons.Actions.Copy) { copy() })
                add(smallButton("Insert at Caret", AllIcons.Actions.MenuPaste) { insertAtCaret() })
                add(smallButton("Apply to File (shows a diff first)", AllIcons.Actions.Diff) { ApplyCode.apply(project, document.text) })
            }, BorderLayout.EAST)
        }
        component.border = JBUI.Borders.customLine(JBColor.border(), 1)
        component.add(header, BorderLayout.NORTH)
        component.add(editor.component, BorderLayout.CENTER)
    }

    fun update(code: String) {
        if (document.text == code) return
        ApplicationManager.getApplication().runWriteAction { document.setText(code) }
    }

    private fun copy() {
        CopyPasteManager.getInstance().setContents(StringSelection(document.text))
    }

    private fun insertAtCaret() {
        val target = FileEditorManager.getInstance(project).selectedTextEditor
        if (target == null) {
            OllamaNotifier.info(project, "Open a file in the editor to insert the code.")
            return
        }
        val code = document.text
        WriteCommandAction.runWriteCommandAction(project, "Insert Code from OllamaComplete", null, {
            val caret = target.caretModel.primaryCaret
            if (caret.hasSelection()) {
                target.document.replaceString(caret.selectionStart, caret.selectionEnd, code)
                caret.removeSelection()
            } else {
                target.document.insertString(caret.offset, code)
            }
        })
        target.contentComponent.requestFocusInWindow()
    }

    private fun smallButton(tooltip: String, icon: javax.swing.Icon, action: () -> Unit) = JButton(icon).apply {
        toolTipText = tooltip
        isBorderPainted = false
        isContentAreaFilled = false
        isFocusable = false
        margin = JBUI.emptyInsets()
        preferredSize = JBUI.size(24, 22)
        addActionListener { action() }
    }

    override fun dispose() {
        EditorFactory.getInstance().releaseEditor(editor)
    }

    companion object {
        private val HEADER_BACKGROUND = JBColor.namedColor("OllamaComplete.codeHeaderBackground", JBColor(0xEBECF0, 0x2B2D30))

        private val aliases = mapOf(
            "kt" to "kotlin", "kts" to "kotlin", "py" to "python", "js" to "javascript", "ts" to "typescript",
            "sh" to "shell script", "bash" to "shell script", "zsh" to "shell script", "shell" to "shell script",
            "yml" to "yaml", "rs" to "rust", "c++" to "c++", "cs" to "c#", "csharp" to "c#", "golang" to "go",
            "md" to "markdown", "html" to "html", "xml" to "xml",
        )

        fun fileTypeFor(tag: String): FileType {
            if (tag.isBlank()) return PlainTextFileType.INSTANCE
            val name = aliases[tag.lowercase()] ?: tag.lowercase()
            Language.getRegisteredLanguages()
                .firstOrNull { it.id.equals(name, ignoreCase = true) || it.displayName.equals(name, ignoreCase = true) }
                ?.associatedFileType
                ?.let { return it }
            val byExtension = FileTypeManager.getInstance().getFileTypeByExtension(tag.lowercase())
            return if (byExtension is UnknownFileType) PlainTextFileType.INSTANCE else byExtension
        }
    }
}
