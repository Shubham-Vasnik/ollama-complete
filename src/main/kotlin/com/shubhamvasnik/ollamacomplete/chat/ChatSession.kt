package com.shubhamvasnik.ollamacomplete.chat

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.shubhamvasnik.ollamacomplete.api.ChatMessage
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.CopyOnWriteArrayList

/** Per-project chat state. It outlives the tool window UI so the conversation survives re-opening it. */
@Service(Service.Level.PROJECT)
class ChatSession(val project: Project, val scope: CoroutineScope) {

    /** [message] is what the model sees; [display] is what the chat shows (without attached file context). */
    data class Entry(val message: ChatMessage, val display: String, val model: String? = null)

    val entries = CopyOnWriteArrayList<Entry>()

    /** The chat UI, while the tool window content exists. */
    var panel: ChatPanel? = null

    fun clear() = entries.clear()

    /** Opens the chat tool window and runs [action] on its panel. */
    fun withPanel(action: (ChatPanel) -> Unit) {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return
        toolWindow.show { panel?.let(action) }
    }

    companion object {
        const val TOOL_WINDOW_ID = "OllamaComplete"

        fun getInstance(project: Project): ChatSession = project.service()
    }
}
