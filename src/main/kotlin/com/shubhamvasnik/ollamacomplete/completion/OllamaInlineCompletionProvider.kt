package com.shubhamvasnik.ollamacomplete.completion

import com.intellij.codeInsight.inline.completion.DebouncedInlineCompletionProvider
import com.intellij.codeInsight.inline.completion.InlineCompletionEvent
import com.intellij.codeInsight.inline.completion.InlineCompletionProviderID
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionGrayTextElement
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSuggestion
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionVariant
import com.intellij.lang.LanguageCommenters
import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiComment
import com.intellij.psi.util.PsiTreeUtil
import com.shubhamvasnik.ollamacomplete.OllamaNotifier
import com.shubhamvasnik.ollamacomplete.ProjectInstructions
import com.shubhamvasnik.ollamacomplete.api.OllamaException
import com.shubhamvasnik.ollamacomplete.completion.context.ContextCollector
import com.shubhamvasnik.ollamacomplete.completion.context.SnippetRanker
import com.shubhamvasnik.ollamacomplete.settings.OllamaSettings
import java.util.Collections
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Copilot-style gray-text completions from the Ollama model selected in the settings. */
class OllamaInlineCompletionProvider : DebouncedInlineCompletionProvider() {

    override val id: InlineCompletionProviderID get() = ID

    override suspend fun getDebounceDelay(request: InlineCompletionRequest): Duration =
        if (request.event is InlineCompletionEvent.DirectCall) Duration.ZERO
        else OllamaSettings.getInstance().debounceMs.coerceAtLeast(0).milliseconds

    override fun isEnabled(event: InlineCompletionEvent): Boolean {
        val settings = OllamaSettings.getInstance()
        if (!settings.completionEnabled || settings.completionModel.isBlank()) return false
        // Typing, or the platform's "Call Inline Completion" action (Shift+Alt+\).
        return event is InlineCompletionEvent.DocumentChange || event is InlineCompletionEvent.DirectCall
    }

    override suspend fun getSuggestionDebounced(request: InlineCompletionRequest): InlineCompletionSuggestion {
        val settings = OllamaSettings.getInstance()
        val useSnippets = settings.useOpenFilesContext && settings.maxSnippetChars > 0
        val (base, candidates) = readAction {
            val file = request.file
            val virtualFile = file.virtualFile
            val project = file.project
            val document = request.document
            if (shouldSkip(request, settings)) return@readAction null
            val context = CompletionPromptBuilder.extract(
                text = document.immutableCharSequence,
                offset = request.endOffset,
                maxPrefixChars = settings.maxPrefixChars,
                maxSuffixChars = settings.maxSuffixChars,
                fileName = virtualFile?.name ?: file.name,
                language = file.language.displayName,
            ).copy(
                filePath = virtualFile?.let { ContextCollector.path(it, project.guessProjectDir()) } ?: file.name,
                commentPrefix = LanguageCommenters.INSTANCE.forLanguage(file.language)?.lineCommentPrefix,
                instructions = ProjectInstructions.read(project),
            )
            val candidates = if (useSnippets && virtualFile != null && context.prefix.isNotBlank()) {
                ContextCollector.collect(project, virtualFile)
            } else emptyList()
            context to candidates
        } ?: return InlineCompletionSuggestion.Empty
        if (base.prefix.isBlank()) return InlineCompletionSuggestion.Empty
        val snippets = SnippetRanker.rank(SnippetRanker.queryText(base.prefix), candidates, MAX_SNIPPETS, settings.maxSnippetChars)
        val context = base.copy(snippets = snippets)

        val service = OllamaCompletionService.getInstance()
        val project = request.editor.project
        val count = settings.suggestionCount.coerceIn(1, MAX_VARIANTS)
        // The platform computes the variants one after another, so alternatives only start once the first is complete.
        val results = Collections.synchronizedList(mutableListOf<String>())
        val variants = (0 until count).map { variant ->
            InlineCompletionVariant.build {
                reportingErrors(project) {
                    if (variant == 0) {
                        // Streamed, so the first lines appear while the model is still writing.
                        val text = StringBuilder()
                        service.stream(context, variant).collect { piece ->
                            text.append(piece)
                            emit(InlineCompletionGrayTextElement(piece))
                        }
                        results += text.toString()
                    } else {
                        val text = service.complete(context, variant)
                        if (text.isNotBlank() && text !in results) {
                            results += text
                            emit(InlineCompletionGrayTextElement(text))
                        }
                    }
                }
            }
        }
        return object : InlineCompletionSuggestion {
            override suspend fun getVariants(): List<InlineCompletionVariant> = variants
        }
    }

    /** Called in a read action. */
    private fun shouldSkip(request: InlineCompletionRequest, settings: OllamaSettings): Boolean {
        val file = request.file
        val virtualFile = file.virtualFile
        if (settings.isLanguageDisabled(file.language.id, virtualFile?.extension)) return true
        val document = request.document
        if (document.textLength > MAX_DOCUMENT_CHARS) return true
        val line = document.getLineNumber(request.endOffset.coerceIn(0, document.textLength))
        // Minified code: a suggestion there is never useful and the prompt would be mostly one line.
        if (document.getLineEndOffset(line) - document.getLineStartOffset(line) > MAX_LINE_CHARS) return true
        if (virtualFile != null) {
            val index = ProjectFileIndex.getInstance(file.project)
            if (index.isExcluded(virtualFile) || index.isUnderIgnored(virtualFile)) return true
        }
        if (!settings.completeInComments) {
            val element = file.findElementAt((request.endOffset - 1).coerceAtLeast(0))
            if (PsiTreeUtil.getParentOfType(element, PsiComment::class.java, false) != null) return true
        }
        return false
    }

    private suspend fun reportingErrors(project: Project?, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: OllamaException) {
            OllamaNotifier.errorOnce(project, "OllamaComplete: completion failed: ${e.message}")
        } catch (e: java.io.IOException) {
            LOG.debug(e)
            OllamaNotifier.errorOnce(project, "OllamaComplete: completion failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    companion object {
        val ID = InlineCompletionProviderID("com.shubhamvasnik.ollamacomplete.inline")
        private const val MAX_SNIPPETS = 4
        private const val MAX_VARIANTS = 5
        private const val MAX_DOCUMENT_CHARS = 1_000_000
        private const val MAX_LINE_CHARS = 2_000
        private val LOG = logger<OllamaInlineCompletionProvider>()
    }
}
