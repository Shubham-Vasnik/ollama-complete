package com.shubhamvasnik.ollamacomplete.chat.commands

import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.readAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.shubhamvasnik.ollamacomplete.chat.workspace.WorkspaceIndex
import com.shubhamvasnik.ollamacomplete.completion.context.ContextCollector

/** Turns what the user typed into the message the model sees: command prompt, references and attached code. */
class ChatContextResolver(private val project: Project) {

    /** The editor as it was when the message was sent. Must be captured on the EDT. */
    data class EditorState(val file: VirtualFile?, val document: Document?, val selection: String?, val selectionLines: IntRange?)

    data class Prepared(val content: String, val display: String)

    /** Built-in commands plus the `.md` files in `.ollamacomplete/prompts`. Must be called in a read action. */
    fun commands(): List<SlashCommand> {
        val dir = project.guessProjectDir()?.findFileByRelativePath(PROMPTS_DIR)
        val custom = dir?.children.orEmpty()
            .filter { !it.isDirectory && it.extension.equals("md", ignoreCase = true) }
            .mapNotNull { file -> runCatching { VfsUtilCore.loadText(file) }.getOrNull()?.let { ChatCommands.fromPromptFile(file.name, it) } }
            .sortedBy { it.name }
        return ChatCommands.merge(ChatCommands.BUILT_IN, custom)
    }

    suspend fun prepare(input: String, editor: EditorState, includeCurrentFile: Boolean): Prepared {
        val parsed = ChatCommands.parse(input, readAction { commands() })
        val command = parsed?.first
        val text = parsed?.second ?: input
        val references = ChatReferences.parse(text)
        val attachments = mutableListOf<Attachment>()
        val notes = mutableListOf<String>()

        // Like Copilot, a command works on the selection, or on the whole file when nothing is selected.
        val commandNeedsCode = command != null && references.none { it is ChatReference.File || it is ChatReference.Selection }
        if (includeCurrentFile || (commandNeedsCode && editor.selection == null)) {
            currentFile(editor)?.let(attachments::add)
        }
        if (ChatReference.Selection in references || (commandNeedsCode && editor.selection != null)) {
            val selected = selection(editor)
            if (selected != null) attachments += selected else notes += "nothing is selected"
        }
        for (reference in references) {
            when (reference) {
                is ChatReference.File -> {
                    val found = file(reference.path)
                    if (found != null) attachments += found else notes += "file not found: ${reference.path}"
                }
                ChatReference.Problems -> attachments += problems(editor) ?: continue
                ChatReference.Workspace -> {
                    val query = ChatReferences.stripReferences(text).ifBlank { command?.prompt.orEmpty() }
                    val hits = WorkspaceIndex.getInstance(project).search(query, WORKSPACE_RESULTS)
                    if (hits.isEmpty()) notes += "no matching code found in the project"
                    hits.forEach { chunk ->
                        val end = chunk.startLine + chunk.text.count { it == '\n' }
                        attachments += Attachment(
                            "From `${chunk.path}` (lines ${chunk.startLine}-$end)", chunk.text,
                            chunk.path.substringAfterLast('.', ""), "${chunk.path.substringAfterLast('/')}:${chunk.startLine}",
                        )
                    }
                }
                ChatReference.Selection -> Unit
            }
        }

        val content = ChatReferences.buildMessage(attachments.distinct(), command?.prompt, text)
        val display = buildString {
            append(input)
            if (attachments.isNotEmpty()) append("\n\n_Attached: ").append(attachments.distinct().joinToString(", ") { it.name }).append('_')
            if (notes.isNotEmpty()) append("\n\n_Note: ").append(notes.joinToString("; ")).append('_')
        }
        return Prepared(content, display)
    }

    private suspend fun currentFile(editor: EditorState): Attachment? {
        val file = editor.file ?: return null
        val text = readAction { editor.document?.text } ?: return null
        val path = path(file)
        return Attachment("The file `$path` open in my editor", truncate(text), file.extension.orEmpty(), file.name)
    }

    private fun selection(editor: EditorState): Attachment? {
        val code = editor.selection ?: return null
        val name = editor.file?.name ?: "editor"
        val lines = editor.selectionLines?.let { " (lines ${it.first}-${it.last})" }.orEmpty()
        val path = editor.file?.let(::path) ?: name
        return Attachment("Selected code from `$path`$lines", truncate(code), editor.file?.extension.orEmpty(), "$name selection")
    }

    private suspend fun file(reference: String): Attachment? {
        val file = readAction { findFile(reference) } ?: return null
        val text = readAction { FileDocumentManager.getInstance().getCachedDocument(file)?.text }
            ?: runCatching { VfsUtilCore.loadText(file) }.getOrNull() ?: return null
        return Attachment("File `${path(file)}`", truncate(text), file.extension.orEmpty(), file.name)
    }

    private fun findFile(reference: String): VirtualFile? {
        val relative = reference.trimStart('/').replace('\\', '/')
        project.guessProjectDir()?.findFileByRelativePath(relative)?.takeIf { !it.isDirectory }?.let { return it }
        val name = relative.substringAfterLast('/')
        val candidates = runCatching {
            FilenameIndex.getVirtualFilesByName(name, GlobalSearchScope.projectScope(project))
        }.getOrDefault(emptyList())
        return candidates.filter { !it.isDirectory }.let { found -> found.firstOrNull { it.path.endsWith(relative) } ?: found.firstOrNull() }
    }

    private suspend fun problems(editor: EditorState): Attachment? {
        val file = editor.file ?: return null
        val document = editor.document ?: return null
        val lines = readAction {
            val found = mutableListOf<String>()
            DaemonCodeAnalyzerEx.processHighlights(document, project, HighlightSeverity.WEAK_WARNING, 0, document.textLength) { info: HighlightInfo ->
                val description = info.description
                if (description != null) {
                    val line = document.getLineNumber(info.startOffset.coerceIn(0, document.textLength)) + 1
                    found += "Line $line: ${info.severity.name}: $description"
                }
                found.size < MAX_PROBLEMS
            }
            found.distinct()
        }
        val content = lines.joinToString("\n").ifEmpty { "No problems are reported in this file." }
        return Attachment("Problems the IDE reports in `${path(file)}`", content, "", "${file.name} problems")
    }

    private fun path(file: VirtualFile) = ContextCollector.path(file, project.guessProjectDir())

    private fun truncate(text: String) = if (text.length > MAX_CHARS) text.take(MAX_CHARS) + "\n… (truncated)" else text

    companion object {
        const val PROMPTS_DIR = ".ollamacomplete/prompts"
        private const val MAX_CHARS = 30_000
        private const val MAX_PROBLEMS = 50
        private const val WORKSPACE_RESULTS = 6

        /** Must be called on the EDT. */
        fun captureEditor(project: Project): EditorState {
            val editor = FileEditorManager.getInstance(project).selectedTextEditor
                ?: return EditorState(null, null, null, null)
            val document = editor.document
            val file = FileDocumentManager.getInstance().getFile(document)
            val selectionModel = editor.selectionModel
            val selection = selectionModel.selectedText?.takeIf { it.isNotBlank() }
            val lines = if (selection != null) {
                (document.getLineNumber(selectionModel.selectionStart) + 1)..(document.getLineNumber(selectionModel.selectionEnd) + 1)
            } else null
            return EditorState(file, document, selection, lines)
        }
    }
}
