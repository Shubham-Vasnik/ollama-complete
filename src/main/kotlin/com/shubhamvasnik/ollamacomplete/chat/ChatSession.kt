package com.shubhamvasnik.ollamacomplete.chat

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.shubhamvasnik.ollamacomplete.api.ChatMessage
import kotlinx.coroutines.CoroutineScope
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Per-project chat state. It outlives the tool window UI so the conversation survives re-opening it. */
@Service(Service.Level.PROJECT)
class ChatSession(val project: Project, val scope: CoroutineScope) {

    /** [message] is what the model sees; [display] is what the chat shows (without attached file context). */
    data class Entry(val message: ChatMessage, val display: String, val model: String? = null)

    val entries = CopyOnWriteArrayList<Entry>()

    /** Identifies the current conversation in [ChatHistory]. */
    var conversationId: String = newId()
        private set

    /** The chat UI, while the tool window content exists. */
    var panel: ChatPanel? = null

    /** Starts a new conversation; the current one stays in the history. */
    fun clear() {
        save()
        entries.clear()
        conversationId = newId()
    }

    /** Stores the current conversation in the history. */
    fun save() = ChatHistory.getInstance(project).save(conversationId, entries.toList())

    /** Switches to a conversation from the history. Returns false if it no longer exists. */
    fun load(id: String): Boolean {
        val loaded = ChatHistory.getInstance(project).load(id) ?: return false
        save()
        entries.clear()
        entries.addAll(loaded)
        conversationId = id
        return true
    }

    /** Opens the chat tool window and runs [action] on its panel. */
    fun withPanel(action: (ChatPanel) -> Unit) {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return
        toolWindow.show { panel?.let(action) }
    }

    companion object {
        const val TOOL_WINDOW_ID = "OllamaComplete"

        fun getInstance(project: Project): ChatSession = project.service()

        private fun newId() = UUID.randomUUID().toString()
    }
}
