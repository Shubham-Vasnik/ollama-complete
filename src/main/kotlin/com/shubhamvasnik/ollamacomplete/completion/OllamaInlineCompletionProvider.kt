package com.shubhamvasnik.ollamacomplete.completion

import com.intellij.codeInsight.inline.completion.DebouncedInlineCompletionProvider
import com.intellij.codeInsight.inline.completion.InlineCompletionEvent
import com.intellij.codeInsight.inline.completion.InlineCompletionProviderID
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionGrayTextElement
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSingleSuggestion
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSuggestion
import com.intellij.lang.LanguageCommenters
import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.guessProjectDir
import com.shubhamvasnik.ollamacomplete.OllamaNotifier
import com.shubhamvasnik.ollamacomplete.ProjectInstructions
import com.shubhamvasnik.ollamacomplete.api.OllamaException
import com.shubhamvasnik.ollamacomplete.completion.context.ContextCollector
import com.shubhamvasnik.ollamacomplete.completion.context.SnippetRanker
import com.shubhamvasnik.ollamacomplete.settings.OllamaSettings
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
            val context = CompletionPromptBuilder.extract(
                text = request.document.immutableCharSequence,
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
        }
        if (base.prefix.isBlank()) return InlineCompletionSuggestion.Empty
        val snippets = SnippetRanker.rank(SnippetRanker.queryText(base.prefix), candidates, MAX_SNIPPETS, settings.maxSnippetChars)
        val context = base.copy(snippets = snippets)

        val text = try {
            OllamaCompletionService.getInstance().complete(context)
        } catch (e: OllamaException) {
            OllamaNotifier.errorOnce(request.editor.project, "OllamaComplete: completion failed: ${e.message}")
            return InlineCompletionSuggestion.Empty
        } catch (e: java.io.IOException) {
            LOG.debug(e)
            OllamaNotifier.errorOnce(request.editor.project, "OllamaComplete: completion failed: ${e.message ?: e.javaClass.simpleName}")
            return InlineCompletionSuggestion.Empty
        }
        if (text.isEmpty()) return InlineCompletionSuggestion.Empty
        return InlineCompletionSingleSuggestion.build {
            emit(InlineCompletionGrayTextElement(text))
        }
    }

    companion object {
        val ID = InlineCompletionProviderID("com.shubhamvasnik.ollamacomplete.inline")
        private const val MAX_SNIPPETS = 4
        private val LOG = logger<OllamaInlineCompletionProvider>()
    }
}
