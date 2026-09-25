package com.shubhamvasnik.ollamacomplete.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.CollectionComboBoxModel
import com.intellij.ui.JBColor
import javax.swing.JLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.COLUMNS_MEDIUM
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.rows
import com.intellij.ui.dsl.builder.toNullableProperty
import com.shubhamvasnik.ollamacomplete.api.OllamaClient
import com.shubhamvasnik.ollamacomplete.completion.OllamaCompletionService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings > Tools > OllamaComplete */
class OllamaConfigurable : BoundConfigurable("OllamaComplete") {

    private val settings = OllamaSettings.getInstance()
    private val completionModels = CollectionComboBoxModel<String>()
    private val chatModels = CollectionComboBoxModel<String>()
    private lateinit var urlField: JBTextField
    private lateinit var statusLabel: JLabel
    private val jobs = mutableListOf<Job>()

    override fun createPanel(): DialogPanel {
        val panel = panel {
            group("Server") {
                row("Server URL:") {
                    urlField = textField()
                        .bindText(settings::serverUrl)
                        .columns(COLUMNS_LARGE)
                        .comment("Default: ${OllamaSettings.DEFAULT_URL}")
                        .component
                    button("Test Connection") { testConnection() }
                }
                row {
                    statusLabel = label("").component
                }
            }
            group("Models") {
                row("Completion model:") {
                    modelCombo(completionModels)
                        .bindItem({ settings.completionModel }, { settings.completionModel = it.orEmpty() })
                        .comment("Coder models with fill-in-the-middle support (e.g. qwen2.5-coder) are fastest and most accurate.")
                    button("Refresh") { loadModels() }.applyToComponent { icon = AllIcons.Actions.Refresh }
                }
                row("Chat model:") {
                    modelCombo(chatModels)
                        .bindItem({ settings.chatModel }, { settings.chatModel = it.orEmpty() })
                }
            }
            group("Code Completion") {
                row {
                    checkBox("Enable inline code completion").bindSelected(settings::completionEnabled)
                }
                row("Prompt mode:") {
                    comboBox(CompletionMode.entries).bindItem(settings::completionMode.toNullableProperty())
                        .comment("Auto uses fill-in-the-middle when the model supports it and an instruct prompt otherwise.")
                }
                row {
                    checkBox("Allow multi-line completions").bindSelected(settings::multiLine)
                }
                row("Delay after typing (ms):") {
                    intTextField(0..5000).bindIntText(settings::debounceMs)
                }
                row("Max tokens:") {
                    intTextField(1..4096).bindIntText(settings::maxTokens)
                }
                row("Temperature:") {
                    textField()
                        .bindText({ settings.temperature.toString() }, { settings.temperature = it.toFloatOrNull()?.coerceIn(0f, 2f) ?: 0.2f })
                        .columns(6)
                }
                row("Context before caret (chars):") {
                    intTextField(100..100_000).bindIntText(settings::maxPrefixChars)
                }
                row("Context after caret (chars):") {
                    intTextField(0..100_000).bindIntText(settings::maxSuffixChars)
                }
                row {
                    checkBox("Use related code from open and recently edited files").bindSelected(settings::useOpenFilesContext)
                        .comment("Like Copilot's neighboring tabs: the parts of those files most similar to the code at the caret are added to the prompt.")
                }
                row("Related code budget (chars):") {
                    intTextField(0..50_000).bindIntText(settings::maxSnippetChars)
                }
            }
            group("Chat") {
                row {
                    checkBox("Show model thinking (models with the thinking capability)").bindSelected(settings::chatThinking)
                }
                row("System prompt:") {}
                row {
                    textArea().rows(4).align(AlignX.FILL).bindText(settings::chatSystemPrompt)
                }
            }
            group("Advanced") {
                row("Keep model loaded for:") {
                    textField().bindText(settings::keepAlive).columns(8)
                        .comment("Ollama keep_alive value, e.g. 10m, 1h or -1 to keep the model loaded")
                }
                row("Request timeout (s):") {
                    intTextField(5..3600).bindIntText(settings::requestTimeoutSec)
                }
            }
        }
        loadModels()
        return panel
    }

    private fun com.intellij.ui.dsl.builder.Row.modelCombo(model: CollectionComboBoxModel<String>) =
        comboBox(model).columns(COLUMNS_MEDIUM).applyToComponent { isEditable = true }

    private fun loadModels() {
        val url = urlField.text
        launchUi {
            withEdt { setStatus("Loading models…", error = false) }
            try {
                val names = OllamaClient.getInstance().listModels(url).map { it.name }
                withEdt {
                    fill(completionModels, names)
                    fill(chatModels, names)
                    setStatus("Found ${names.size} model(s)", error = false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withEdt { setStatus(e.message ?: "Failed to load models", error = true) }
            }
        }
    }

    private fun testConnection() {
        val url = urlField.text
        launchUi {
            try {
                val version = OllamaClient.getInstance().version(url)
                withEdt { setStatus("Connected to Ollama $version", error = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withEdt { setStatus(e.message ?: "Connection failed", error = true) }
            }
        }
    }

    /** Keeps the current (possibly typed) selection while replacing the list of choices. */
    private fun fill(model: CollectionComboBoxModel<String>, names: List<String>) {
        val selected = model.selected
        val items = if (!selected.isNullOrBlank() && selected !in names) listOf(selected) + names else names
        model.replaceAll(items)
        model.selectedItem = selected ?: ""
    }

    private fun setStatus(text: String, error: Boolean) {
        statusLabel.text = text
        statusLabel.foreground = if (error) JBColor.RED else JBColor.foreground()
    }

    private fun launchUi(block: suspend () -> Unit) {
        jobs += OllamaClient.getInstance().scope.launch { block() }
    }

    private suspend fun withEdt(block: () -> Unit) =
        withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) { block() }

    override fun apply() {
        super.apply()
        OllamaClient.getInstance().clearCaches()
        OllamaCompletionService.getInstance().clearCache()
        settings.fireChanged()
    }

    override fun disposeUIResources() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        super.disposeUIResources()
    }
}
