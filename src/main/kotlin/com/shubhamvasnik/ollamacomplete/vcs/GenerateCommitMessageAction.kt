package com.shubhamvasnik.ollamacomplete.vcs

import com.intellij.diff.comparison.ComparisonManager
import com.intellij.diff.comparison.ComparisonPolicy
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.progress.DumbProgressIndicator
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vcs.CommitMessageI
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.BinaryContentRevision
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ContentRevision
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.shubhamvasnik.ollamacomplete.OllamaNotifier
import com.shubhamvasnik.ollamacomplete.api.ChatMessage
import com.shubhamvasnik.ollamacomplete.api.ChatRequest
import com.shubhamvasnik.ollamacomplete.api.OllamaClient
import com.shubhamvasnik.ollamacomplete.chat.ChatSession
import com.shubhamvasnik.ollamacomplete.settings.OllamaSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Commit dialog / tool window button that writes the commit message from the included changes, like Copilot. */
class GenerateCommitMessageAction : DumbAwareAction() {

    @Volatile
    private var running: Job? = null

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val control = e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL)
        e.presentation.isVisible = e.project != null && control != null
        e.presentation.isEnabled = e.presentation.isVisible && running?.isActive != true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val control = e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL) ?: return
        val changes = e.getData(VcsDataKeys.COMMIT_WORKFLOW_UI)?.getIncludedChanges()
            ?: e.getData(VcsDataKeys.CHANGES)?.toList().orEmpty()
        if (changes.isEmpty()) {
            OllamaNotifier.info(project, "Select the changes to commit first.")
            return
        }
        val settings = OllamaSettings.getInstance()
        val model = settings.chatModel
        if (model.isBlank()) {
            OllamaNotifier.info(project, "Select a chat model in Settings | Tools | OllamaComplete first.")
            return
        }
        running = ChatSession.getInstance(project).scope.launch {
            try {
                withBackgroundProgress(project, "Generating commit message with $model") {
                    generate(project, model, changes, control)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                OllamaNotifier.error(project, "OllamaComplete: generating the commit message failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private suspend fun generate(project: Project, model: String, changes: List<Change>, control: CommitMessageI) {
        val diff = buildDiff(project, changes)
        val settings = OllamaSettings.getInstance()
        val client = OllamaClient.getInstance()
        val thinking = runCatching { "thinking" in client.capabilities(model) }.getOrDefault(false)
        val request = ChatRequest(
            model = model,
            messages = listOf(ChatMessage("system", SYSTEM_PROMPT), ChatMessage("user", diff)),
            think = if (thinking) false else null,
            keepAlive = settings.keepAlive.ifBlank { null },
        )
        val answer = StringBuilder()
        var lastUpdate = 0L
        client.chat(request).collect { chunk ->
            chunk.message?.content?.let(answer::append)
            val now = System.currentTimeMillis()
            if (now - lastUpdate > UPDATE_INTERVAL_MS) {
                lastUpdate = now
                val text = CommitMessages.clean(answer.toString())
                withContext(Dispatchers.EDT) { control.setCommitMessage(text) }
            }
        }
        val message = CommitMessages.clean(answer.toString())
        withContext(Dispatchers.EDT) { control.setCommitMessage(message) }
    }

    /** A `git diff`-style text of the changes, cut short for very large commits. */
    private fun buildDiff(project: Project, changes: List<Change>): String {
        val root = project.guessProjectDir()
        val parts = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        var size = 0
        for (change in changes) {
            val filePath = (change.afterRevision ?: change.beforeRevision)?.file ?: continue
            val path = filePath.virtualFile?.let { file -> root?.let { VfsUtilCore.getRelativePath(file, it) } } ?: filePath.path
            if (size > MAX_DIFF_CHARS) {
                skipped += path
                continue
            }
            val part = when {
                change.beforeRevision is BinaryContentRevision || change.afterRevision is BinaryContentRevision -> "Binary file $path changed\n"
                else -> {
                    val before = content(change.beforeRevision)
                    val after = content(change.afterRevision)
                    val ranges = when {
                        before == null && after == null -> emptyList()
                        before == null -> listOf(LineRange(0, 0, 0, after!!.lines().size))
                        after == null -> listOf(LineRange(0, before.lines().size, 0, 0))
                        else -> ComparisonManager.getInstance()
                            .compareLines(before, after, ComparisonPolicy.DEFAULT, DumbProgressIndicator.INSTANCE)
                            .map { LineRange(it.startLine1, it.endLine1, it.startLine2, it.endLine2) }
                    }
                    val moved = change.beforeRevision?.file?.let { it != change.afterRevision?.file } == true && change.afterRevision != null
                    val renamed = if (moved) "Renamed from ${change.beforeRevision?.file?.name}\n" else ""
                    renamed + UnifiedDiff.format(path, before, after, ranges)
                }
            }
            val trimmed = if (part.length > MAX_FILE_DIFF_CHARS) part.take(MAX_FILE_DIFF_CHARS) + "\n… (diff truncated)\n" else part
            parts += trimmed
            size += trimmed.length
        }
        if (skipped.isNotEmpty()) parts += "Also changed (diff not shown): ${skipped.joinToString(", ")}\n"
        return parts.joinToString("\n")
    }

    private fun content(revision: ContentRevision?): String? = revision?.let { runCatching { it.content }.getOrNull() }

    companion object {
        private const val MAX_DIFF_CHARS = 16_000
        private const val MAX_FILE_DIFF_CHARS = 6_000
        private const val UPDATE_INTERVAL_MS = 100L

        const val SYSTEM_PROMPT =
            "You write git commit messages. You get the diff of a commit. Reply with the commit message only: a summary " +
                "line in the imperative mood (\"Add\", \"Fix\", not \"Added\"), at most 72 characters, without a trailing " +
                "period. If the change needs explaining, add a blank line and a short body wrapped at 72 characters that " +
                "says what changed and why. No Markdown, code fences or quotes."
    }
}

object CommitMessages {
    private val think = Regex("(?s)<think>.*?(</think>|$)")

    /** Removes what models add around a commit message: thinking, code fences, quotes, a "Commit message:" label. */
    fun clean(raw: String): String {
        var text = think.replace(raw, "").trim()
        if (text.startsWith("```")) text = text.substringAfter('\n', "").substringBeforeLast("```").trim()
        text = text.removePrefix("Commit message:").trim()
        if (text.length > 1 && text.first() == text.last() && text.first() in "\"'`") text = text.substring(1, text.length - 1).trim()
        return text
    }
}
