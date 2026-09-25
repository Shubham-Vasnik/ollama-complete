package com.shubhamvasnik.ollamacomplete.chat.apply

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.util.ui.JBUI
import com.shubhamvasnik.ollamacomplete.OllamaNotifier
import com.shubhamvasnik.ollamacomplete.api.ChatMessage
import com.shubhamvasnik.ollamacomplete.api.ChatRequest
import com.shubhamvasnik.ollamacomplete.api.OllamaClient
import com.shubhamvasnik.ollamacomplete.chat.ChatSession
import com.shubhamvasnik.ollamacomplete.chat.MarkdownSegments
import com.shubhamvasnik.ollamacomplete.chat.Segment
import com.shubhamvasnik.ollamacomplete.settings.OllamaSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.swing.JComponent

/**
 * "Apply" on a chat code block: works out the new content of the file open in the editor and shows it as a diff
 * the user can edit and accept. The selection is replaced if there is one; otherwise [CodeMerger] finds the matching
 * declaration, and if that fails the chat model is asked to merge the code into the file.
 */
object ApplyCode {
    private const val MAX_MODEL_MERGE_LINES = 800

    private const val MERGE_SYSTEM_PROMPT =
        "You apply code edits. You get a source file and a code snippet from a chat answer that changes part of it. " +
            "Reply with the complete updated file in a single fenced code block. Change only what the snippet changes " +
            "and keep everything else exactly as it is. No explanations."

    /** Must be called on the EDT. */
    fun apply(project: Project, code: String) {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        val document = editor?.document
        val file = document?.let { FileDocumentManager.getInstance().getFile(it) }
        if (editor == null || document == null || file == null) {
            OllamaNotifier.info(project, "Open the file to change in the editor, then click Apply again.")
            return
        }
        val original = document.text
        val selection = editor.selectionModel
        val proposed = if (selection.hasSelection()) {
            original.replaceRange(selection.selectionStart, selection.selectionEnd, code)
        } else {
            CodeMerger.merge(original, code)
        }
        if (proposed != null) {
            showDiff(project, file, document, original, proposed)
            return
        }
        if (original.count { it == '\n' } > MAX_MODEL_MERGE_LINES) {
            OllamaNotifier.info(project, "Couldn't tell where this code goes in ${file.name}. Select the code it replaces and click Apply again.")
            return
        }
        mergeWithModel(project, file, document, original, code)
    }

    private fun mergeWithModel(project: Project, file: VirtualFile, document: Document, original: String, code: String) {
        val settings = OllamaSettings.getInstance()
        val model = settings.chatModel
        if (model.isBlank()) {
            OllamaNotifier.info(project, "Select a chat model first, or select the code this block replaces and click Apply again.")
            return
        }
        val session = ChatSession.getInstance(project)
        session.scope.launch {
            try {
                val merged = withBackgroundProgress(project, "Applying code to ${file.name} with $model") {
                    val client = OllamaClient.getInstance()
                    val thinking = runCatching { "thinking" in client.capabilities(model) }.getOrDefault(false)
                    val user = "File `${file.name}`:\n```${file.extension.orEmpty()}\n$original\n```\n\n" +
                        "Snippet to apply:\n```${file.extension.orEmpty()}\n$code\n```"
                    val request = ChatRequest(
                        model = model,
                        messages = listOf(ChatMessage("system", MERGE_SYSTEM_PROMPT), ChatMessage("user", user)),
                        think = if (thinking) false else null,
                        keepAlive = settings.keepAlive.ifBlank { null },
                    )
                    val answer = StringBuilder()
                    client.chat(request).collect { chunk -> chunk.message?.content?.let(answer::append) }
                    largestCodeBlock(answer.toString())
                }
                withContext(Dispatchers.EDT) {
                    if (merged.isNullOrBlank()) {
                        OllamaNotifier.info(project, "The model did not return the updated file. Select the code to replace and click Apply again.")
                    } else {
                        val withNewline = if (original.endsWith("\n") && !merged.endsWith("\n")) merged + "\n" else merged
                        showDiff(project, file, document, original, withNewline)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                OllamaNotifier.error(project, "OllamaComplete: applying the code failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    internal fun largestCodeBlock(markdown: String): String? =
        MarkdownSegments.split(markdown).filterIsInstance<Segment.Code>().maxByOrNull { it.code.length }?.code

    private fun showDiff(project: Project, file: VirtualFile, document: Document, original: String, proposed: String) {
        if (proposed == original) {
            OllamaNotifier.info(project, "${file.name} already contains this code.")
            return
        }
        val dialog = ApplyDiffDialog(project, file, original, proposed)
        if (!dialog.showAndGet()) return
        if (document.text != original) {
            OllamaNotifier.info(project, "${file.name} changed while the diff was open. Click Apply again.")
            return
        }
        val result = dialog.result
        // Replace only the changed middle part so the caret, folding and undo stay sensible.
        val prefix = original.commonPrefixWith(result).length
        val suffix = original.substring(prefix).commonSuffixWith(result.substring(prefix)).length
        WriteCommandAction.runWriteCommandAction(project, "Apply Code from OllamaComplete", null, {
            document.replaceString(prefix, original.length - suffix, result.substring(prefix, result.length - suffix))
        })
    }

    private class ApplyDiffDialog(project: Project, file: VirtualFile, original: String, proposed: String) : DialogWrapper(project) {
        private val proposedDocument = EditorFactory.getInstance().createDocument(proposed)
        private val panel = DiffManager.getInstance().createRequestPanel(project, disposable, null)

        val result: String get() = proposedDocument.text

        init {
            title = "Apply Code to ${file.name}"
            setOKButtonText("Apply")
            val factory = DiffContentFactory.getInstance()
            panel.setRequest(
                SimpleDiffRequest(
                    "Apply code to ${file.name}",
                    factory.create(project, original, file.fileType),
                    factory.create(project, proposedDocument, file.fileType),
                    "Current",
                    "After applying (you can edit this side)",
                ),
            )
            init()
        }

        override fun createCenterPanel(): JComponent = panel.component.apply { preferredSize = JBUI.size(900, 600) }

        override fun getPreferredFocusedComponent(): JComponent? = panel.preferredFocusedComponent
    }
}
