package com.shubhamvasnik.ollamacomplete.actions

import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.LowPriorityAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.shubhamvasnik.ollamacomplete.chat.ChatSession
import com.shubhamvasnik.ollamacomplete.settings.OllamaSettings

/**
 * Alt+Enter on an error: "Fix with OllamaComplete" asks the chat to fix it, like Copilot's "Fix using Copilot".
 * The error messages go into a `/fix` prompt, which attaches the selection or the whole file.
 */
class FixWithOllamaIntention : IntentionAction, LowPriorityAction {

    override fun getText() = "Fix with OllamaComplete"

    override fun getFamilyName() = text

    override fun startInWriteAction() = false

    // The chat does the work, so there is nothing to preview (and the preview must not open the chat).
    override fun generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo = IntentionPreviewInfo.EMPTY

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean =
        editor != null && OllamaSettings.getInstance().chatModel.isNotBlank() && errorsOnCaretLine(project, editor).isNotEmpty()

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        editor ?: return
        val errors = errorsOnCaretLine(project, editor)
        if (errors.isEmpty()) return
        val prompt = "/fix " + errors.joinToString("\n") { "Line ${it.first}: ${it.second}" }
        ChatSession.getInstance(project).withPanel { it.sendPrompt(prompt) }
    }

    private fun errorsOnCaretLine(project: Project, editor: Editor): List<Pair<Int, String>> {
        val document = editor.document
        val line = document.getLineNumber(editor.caretModel.offset)
        val errors = mutableListOf<Pair<Int, String>>()
        DaemonCodeAnalyzerEx.processHighlights(
            document, project, HighlightSeverity.ERROR, document.getLineStartOffset(line), document.getLineEndOffset(line),
        ) { info: HighlightInfo ->
            info.description?.let { errors += (line + 1) to it }
            true
        }
        return errors.distinct()
    }
}
