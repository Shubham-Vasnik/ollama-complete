package com.shubhamvasnik.ollamacomplete.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.messages.Topic

enum class CompletionMode(val displayName: String) {
    AUTO("Auto (FIM if the model supports it)"),
    FIM("Fill-in-the-middle"),
    INSTRUCT("Instruct prompt");

    override fun toString() = displayName
}

@Service(Service.Level.APP)
@State(name = "OllamaCompleteSettings", storages = [Storage("ollama-complete.xml")])
class OllamaSettings : SimplePersistentStateComponent<OllamaSettings.SettingsState>(SettingsState()) {

    class SettingsState : BaseState() {
        var serverUrl by string(DEFAULT_URL)
        var completionModel by string("")
        var chatModel by string("")
        var completionEnabled by property(true)
        var completionMode by enum(CompletionMode.AUTO)
        var debounceMs by property(300)
        var maxPrefixChars by property(4000)
        var maxSuffixChars by property(1000)
        var maxTokens by property(128)
        var temperature by property(0.2f)
        var multiLine by property(true)
        var useOpenFilesContext by property(true)
        var maxSnippetChars by property(3000)
        var suggestionCount by property(2)
        var completeInComments by property(true)
        var disabledLanguages by string("")
        var chatSystemPrompt by string(DEFAULT_CHAT_SYSTEM_PROMPT)
        var chatThinking by property(false)
        var keepAlive by string("10m")
        var requestTimeoutSec by property(120)
    }

    var serverUrl: String
        get() = state.serverUrl?.takeIf { it.isNotBlank() } ?: DEFAULT_URL
        set(value) { state.serverUrl = value.trim() }

    var completionModel: String
        get() = state.completionModel.orEmpty()
        set(value) { state.completionModel = value.trim() }

    var chatModel: String
        get() = state.chatModel.orEmpty()
        set(value) { state.chatModel = value.trim() }

    var completionEnabled by state::completionEnabled
    var completionMode by state::completionMode
    var debounceMs by state::debounceMs
    var maxPrefixChars by state::maxPrefixChars
    var maxSuffixChars by state::maxSuffixChars
    var maxTokens by state::maxTokens
    var temperature by state::temperature
    var multiLine by state::multiLine
    var useOpenFilesContext by state::useOpenFilesContext
    var maxSnippetChars by state::maxSnippetChars
    var suggestionCount by state::suggestionCount
    var completeInComments by state::completeInComments

    /** Language IDs or file extensions, separated by commas, where inline completion is off. */
    var disabledLanguages: String
        get() = state.disabledLanguages.orEmpty()
        set(value) { state.disabledLanguages = value.trim() }

    fun isLanguageDisabled(languageId: String, extension: String?): Boolean =
        disabledLanguages.split(',').map { it.trim().removePrefix(".") }.filter { it.isNotEmpty() }
            .any { it.equals(languageId, ignoreCase = true) || it.equals(extension, ignoreCase = true) }
    var chatThinking by state::chatThinking
    var requestTimeoutSec by state::requestTimeoutSec

    var chatSystemPrompt: String
        get() = state.chatSystemPrompt.orEmpty()
        set(value) { state.chatSystemPrompt = value }

    var keepAlive: String
        get() = state.keepAlive.orEmpty()
        set(value) { state.keepAlive = value.trim() }

    /** Tells the status bar widget, chat window and settings page to refresh their view of the settings. */
    fun fireChanged() {
        ApplicationManager.getApplication().messageBus.syncPublisher(OllamaSettingsListener.TOPIC).settingsChanged()
    }

    companion object {
        const val DEFAULT_URL = "http://localhost:11434"
        const val DEFAULT_CHAT_SYSTEM_PROMPT =
            "You are a helpful programming assistant running inside a JetBrains IDE. " +
                "Answer concisely and use fenced Markdown code blocks with a language tag for code."

        fun getInstance(): OllamaSettings = service()
    }
}

fun interface OllamaSettingsListener {
    fun settingsChanged()

    companion object {
        @Topic.AppLevel
        val TOPIC = Topic.create("OllamaComplete settings changed", OllamaSettingsListener::class.java)
    }
}
