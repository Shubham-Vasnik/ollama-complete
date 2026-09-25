package com.shubhamvasnik.ollamacomplete.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.shubhamvasnik.ollamacomplete.chat.ChatPanel
import com.shubhamvasnik.ollamacomplete.chat.ChatSession
import com.shubhamvasnik.ollamacomplete.settings.OllamaSettings

/** Base for editor actions that send the selected code to the chat. */
abstract class SelectionToChatAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = e.project != null && editor != null && editor.selectionModel.hasSelection()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val code = editor.selectionModel.selectedText ?: return
        val block = "```${language(editor)}\n${code.trimEnd()}\n```"
        ChatSession.getInstance(project).withPanel { panel -> perform(panel, block) }
    }

    protected abstract fun perform(panel: ChatPanel, codeBlock: String)

    private fun language(editor: Editor): String =
        FileDocumentManager.getInstance().getFile(editor.document)?.extension.orEmpty()
}

class ExplainSelectionAction : SelectionToChatAction() {
    // The /explain command attaches the selection itself.
    override fun perform(panel: ChatPanel, codeBlock: String) = panel.sendPrompt("/explain")
}

/**
 * Runs a chat command on the selection, or on the whole file when nothing is selected, like Copilot's
 * Generate Tests / Generate Docs.
 */
abstract class CommandOnCodeAction(private val command: String) : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && e.getData(CommonDataKeys.EDITOR) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ChatSession.getInstance(project).withPanel { panel -> panel.sendPrompt("/$command") }
    }
}

class GenerateTestsAction : CommandOnCodeAction("tests")

class GenerateDocsAction : CommandOnCodeAction("doc")

class AddSelectionToChatAction : SelectionToChatAction() {
    override fun perform(panel: ChatPanel, codeBlock: String) = panel.appendToInput(codeBlock)
}

class ToggleCompletionAction : DumbAwareToggleAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun isSelected(e: AnActionEvent) = OllamaSettings.getInstance().completionEnabled

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        val settings = OllamaSettings.getInstance()
        settings.completionEnabled = state
        settings.fireChanged()
    }
}
