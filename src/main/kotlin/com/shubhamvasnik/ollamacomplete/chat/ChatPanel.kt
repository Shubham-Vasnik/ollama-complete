package com.shubhamvasnik.ollamacomplete.chat

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.ui.CollectionComboBoxModel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.shubhamvasnik.ollamacomplete.OllamaIcons
import com.shubhamvasnik.ollamacomplete.api.ChatMessage
import com.shubhamvasnik.ollamacomplete.api.ChatRequest
import com.shubhamvasnik.ollamacomplete.api.OllamaClient
import com.shubhamvasnik.ollamacomplete.settings.OllamaConfigurable
import com.shubhamvasnik.ollamacomplete.settings.OllamaSettings
import com.shubhamvasnik.ollamacomplete.settings.OllamaSettingsListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Rectangle
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.Scrollable
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.text.DefaultEditorKit

/** Content of the "OllamaComplete" chat tool window. */
class ChatPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {

    private val session = ChatSession.getInstance(project)
    private val settings = OllamaSettings.getInstance()
    private val client = OllamaClient.getInstance()

    private val messages = ScrollablePanel().apply {
        layout = VerticalLayout(0)
        isOpaque = true
        background = UIUtil.getPanelBackground()
    }
    private val scrollPane = JBScrollPane(messages).apply {
        border = JBUI.Borders.empty()
        horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
    }
    private val emptyState = JBLabel(
        "<html><center>Chat with a local Ollama model.<br>Pick a model above, then ask a question.</center></html>",
        SwingConstants.CENTER,
    ).apply {
        foreground = UIUtil.getContextHelpForeground()
        border = JBUI.Borders.empty(24, 12)
    }

    private val modelModel = CollectionComboBoxModel<String>()
    private val modelCombo = ComboBox(modelModel).apply {
        isEditable = false
        toolTipText = "Chat model"
    }
    private var updatingCombo = false

    private val input = JBTextArea(3, 20).apply {
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(6)
        emptyText.text = "Ask OllamaComplete… (Enter to send, Shift+Enter for a new line)"
    }
    private val includeFile = JBCheckBox("Include current file")
    private val sendButton = JButton("Send", AllIcons.Actions.Execute)

    private var job: Job? = null
    /** Incremented by "New Chat" so a cancelled answer is not added to the next conversation. */
    private var conversation = 0
    private var messageDisposable = Disposer.newDisposable(this, "OllamaComplete chat messages")

    init {
        session.panel = this
        toolbar = createHeader()
        setContent(JPanel(BorderLayout()).apply {
            add(scrollPane, BorderLayout.CENTER)
            add(createInputArea(), BorderLayout.SOUTH)
        })

        modelCombo.addActionListener {
            if (updatingCombo) return@addActionListener
            val model = modelModel.selected ?: return@addActionListener
            if (model != settings.chatModel) {
                settings.chatModel = model
                settings.fireChanged()
            }
        }
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(OllamaSettingsListener.TOPIC, OllamaSettingsListener { selectModel(settings.chatModel) })

        rebuildMessages()
        refreshModels()
    }

    // ---- public API used by editor actions ----

    fun sendPrompt(prompt: String) {
        input.text = prompt
        send()
    }

    fun appendToInput(text: String) {
        input.text = if (input.text.isBlank()) text else input.text.trimEnd() + "\n\n" + text
        input.caretPosition = input.document.length
        input.requestFocusInWindow()
    }

    @org.jetbrains.annotations.TestOnly
    internal fun isBusy(): Boolean = job?.isActive == true

    fun newChat() {
        conversation++
        job?.cancel()
        session.clear()
        rebuildMessages()
        input.requestFocusInWindow()
    }

    // ---- UI construction ----

    private fun createHeader(): JPanel {
        val actions = DefaultActionGroup(
            object : DumbAwareAction("Refresh Models", "Reload the list of models from Ollama", AllIcons.Actions.Refresh) {
                override fun actionPerformed(e: AnActionEvent) = refreshModels()
            },
            object : DumbAwareAction("New Chat", "Start a new conversation", AllIcons.General.Add) {
                override fun actionPerformed(e: AnActionEvent) = newChat()
            },
            object : DumbAwareAction("Settings", "Open OllamaComplete settings", AllIcons.General.Settings) {
                override fun actionPerformed(e: AnActionEvent) {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, OllamaConfigurable::class.java)
                }
            },
        )
        val toolbar = ActionManager.getInstance().createActionToolbar("OllamaChat", actions, true).apply {
            targetComponent = this@ChatPanel
        }
        return JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            border = JBUI.Borders.empty(2, 8, 2, 2)
            add(JBLabel("Model:"), BorderLayout.WEST)
            add(modelCombo, BorderLayout.CENTER)
            add(toolbar.component, BorderLayout.EAST)
        }
    }

    private fun createInputArea(): JPanel {
        input.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), SEND_ACTION)
        input.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), DefaultEditorKit.insertBreakAction)
        input.actionMap.put(SEND_ACTION, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) = send()
        })
        sendButton.addActionListener { if (job?.isActive == true) job?.cancel() else send() }

        val inputScroll = JBScrollPane(input).apply {
            preferredSize = Dimension(100, JBUI.scale(90))
            border = JBUI.Borders.customLine(com.intellij.ui.JBColor.border(), 1)
        }
        val bottomRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = JBUI.Borders.emptyTop(4)
            add(includeFile)
            add(javax.swing.Box.createHorizontalGlue())
            add(sendButton)
        }
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.compound(JBUI.Borders.customLineTop(com.intellij.ui.JBColor.border()), JBUI.Borders.empty(6, 8))
            add(inputScroll, BorderLayout.CENTER)
            add(bottomRow, BorderLayout.SOUTH)
        }
    }

    // ---- models ----

    private fun refreshModels() {
        session.scope.launch {
            val names = try {
                client.listModels().map { it.name }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.EDT) { showError(e.message ?: "Cannot load models from Ollama") }
                return@launch
            }
            withContext(Dispatchers.EDT) {
                val current = settings.chatModel
                updatingCombo = true
                try {
                    modelModel.replaceAll(if (current.isNotBlank() && current !in names) listOf(current) + names else names)
                } finally {
                    updatingCombo = false
                }
                if (current.isBlank() && names.isNotEmpty()) {
                    settings.chatModel = names.first()
                    settings.fireChanged()
                }
                selectModel(settings.chatModel)
            }
        }
    }

    private fun selectModel(model: String) {
        updatingCombo = true
        try {
            if (model.isNotBlank() && !modelModel.contains(model)) modelModel.add(model)
            modelModel.selectedItem = model.ifBlank { null }
        } finally {
            updatingCombo = false
        }
    }

    // ---- messages ----

    private fun rebuildMessages() {
        Disposer.dispose(messageDisposable)
        messageDisposable = Disposer.newDisposable(this, "OllamaComplete chat messages")
        messages.removeAll()
        if (session.entries.isEmpty()) {
            messages.add(emptyState)
        } else {
            for (entry in session.entries) {
                val view = addMessage(entry.message.role, entry.model)
                view.setContent(entry.display)
            }
        }
        messages.revalidate()
        messages.repaint()
    }

    private fun addMessage(role: String, model: String?): MessageComponent {
        if (emptyState.parent != null) messages.remove(emptyState)
        val view = if (role == "user") {
            MessageComponent(project, MessageComponent.Role.USER, "You", AllIcons.General.User, messageDisposable)
        } else {
            MessageComponent(project, MessageComponent.Role.ASSISTANT, model ?: "OllamaComplete", OllamaIcons.Ollama, messageDisposable)
        }
        messages.add(view)
        messages.revalidate()
        return view
    }

    private fun showError(message: String) {
        val view = addMessage("assistant", null)
        view.setError(message)
        scrollToBottom(force = true)
    }

    private fun send() {
        if (job?.isActive == true) return
        val prompt = input.text.trim()
        if (prompt.isEmpty()) return
        val model = modelModel.selected?.takeIf { it.isNotBlank() } ?: settings.chatModel
        if (model.isBlank()) {
            showError("Select a chat model first (Refresh the list if it is empty).")
            return
        }
        input.text = ""

        val attached = if (includeFile.isSelected) currentFileContext() else null
        val content = if (attached != null) "${attached.second}\n\n$prompt" else prompt
        val display = if (attached != null) "$prompt\n\n_Attached: ${attached.first}_" else prompt
        val userEntry = ChatSession.Entry(ChatMessage("user", content), display)
        session.entries += userEntry
        addMessage("user", null).setContent(display)
        val answerView = addMessage("assistant", model)
        answerView.setContent("", streaming = true)
        scrollToBottom(force = true)

        val history = buildList {
            settings.chatSystemPrompt.takeIf { it.isNotBlank() }?.let { add(ChatMessage("system", it)) }
            session.entries.forEach { add(it.message) }
        }
        setBusy(true)
        val startedIn = conversation
        job = session.scope.launch {
            val answer = StringBuilder()
            val thoughts = StringBuilder()
            var lastRender = 0L
            try {
                val capabilities = runCatching { client.capabilities(model) }.getOrDefault(emptySet())
                val request = ChatRequest(
                    model = model,
                    messages = history,
                    think = if ("thinking" in capabilities) settings.chatThinking else null,
                    keepAlive = settings.keepAlive.ifBlank { null },
                )
                client.chat(request).collect { chunk ->
                    chunk.message?.content?.let(answer::append)
                    chunk.message?.thinking?.let(thoughts::append)
                    val now = System.currentTimeMillis()
                    if (now - lastRender >= RENDER_INTERVAL_MS) {
                        lastRender = now
                        val text = answer.toString()
                        val thinking = thoughts.toString()
                        withContext(Dispatchers.EDT) { render(answerView, text, thinking, streaming = true) }
                    }
                }
                session.entries += ChatSession.Entry(ChatMessage("assistant", answer.toString()), answer.toString(), model)
                withContext(Dispatchers.EDT) { render(answerView, answer.toString(), thoughts.toString(), streaming = false) }
            } catch (e: CancellationException) {
                withContext(NonCancellable + Dispatchers.EDT) {
                    if (answer.isNotEmpty() && startedIn == conversation) {
                        session.entries += ChatSession.Entry(ChatMessage("assistant", answer.toString()), answer.toString(), model)
                    }
                    render(answerView, answer.toString(), thoughts.toString(), streaming = false)
                    answerView.setStatus("Stopped")
                }
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.EDT) {
                    session.entries.remove(userEntry)
                    render(answerView, answer.toString(), thoughts.toString(), streaming = false)
                    answerView.setError(e.message ?: e.javaClass.simpleName)
                }
            } finally {
                withContext(NonCancellable + Dispatchers.EDT) { setBusy(false) }
            }
        }
    }

    private fun render(view: MessageComponent, text: String, thinking: String, streaming: Boolean) {
        val stick = isScrolledToBottom()
        view.setContent(text, if (settings.chatThinking) thinking else "", streaming)
        if (stick) scrollToBottom(force = true)
    }

    /** Returns the file name and a Markdown block with the content of the file open in the editor. */
    private fun currentFileContext(): Pair<String, String>? {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return null
        return runReadActionBlocking {
            val file = FileDocumentManager.getInstance().getFile(editor.document)
            val name = file?.name ?: "current file"
            val language = file?.extension.orEmpty()
            val text = editor.document.text.let { if (it.length > MAX_FILE_CHARS) it.take(MAX_FILE_CHARS) + "\n… (truncated)" else it }
            name to "Here is the file `$name` currently open in my editor:\n```$language\n$text\n```"
        }
    }

    private fun setBusy(busy: Boolean) {
        sendButton.text = if (busy) "Stop" else "Send"
        sendButton.icon = if (busy) AllIcons.Actions.Suspend else AllIcons.Actions.Execute
    }

    private fun isScrolledToBottom(): Boolean {
        val bar = scrollPane.verticalScrollBar
        return bar.value + bar.visibleAmount >= bar.maximum - JBUI.scale(40)
    }

    private fun scrollToBottom(force: Boolean) {
        if (!force && !isScrolledToBottom()) return
        SwingUtilities.invokeLater {
            messages.revalidate()
            val bar = scrollPane.verticalScrollBar
            bar.value = bar.maximum
        }
    }

    override fun dispose() {
        job?.cancel()
        if (session.panel === this) session.panel = null
    }

    /** Tracks the viewport width so HTML text wraps instead of scrolling horizontally. */
    private class ScrollablePanel : JPanel(), Scrollable {
        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = JBUI.scale(16)
        override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = visibleRect.height
        override fun getScrollableTracksViewportWidth() = true
        override fun getScrollableTracksViewportHeight() = false
    }

    companion object {
        private const val SEND_ACTION = "ollama.send"
        private const val RENDER_INTERVAL_MS = 60L
        private const val MAX_FILE_CHARS = 30_000
    }
}
