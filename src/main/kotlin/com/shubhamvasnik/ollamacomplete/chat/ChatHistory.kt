package com.shubhamvasnik.ollamacomplete.chat

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.annotations.XCollection
import com.shubhamvasnik.ollamacomplete.api.ChatMessage

/** Past conversations of a project, kept in the project's workspace file (not shared through VCS). */
@Service(Service.Level.PROJECT)
@State(name = "OllamaCompleteChatHistory", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class ChatHistory : PersistentStateComponent<ChatHistory.HistoryState> {

    class HistoryState {
        @XCollection
        var conversations: MutableList<ConversationState> = mutableListOf()
    }

    class ConversationState {
        var id: String = ""
        var title: String = ""
        var updated: Long = 0
        @XCollection
        var messages: MutableList<MessageState> = mutableListOf()
    }

    class MessageState {
        var role: String = ""
        var content: String = ""
        var display: String = ""
        var model: String? = null
    }

    data class Summary(val id: String, val title: String, val updated: Long)

    private var state = HistoryState()

    override fun getState(): HistoryState = synchronized(this) { state }

    override fun loadState(loaded: HistoryState) = synchronized(this) { state = loaded }

    fun summaries(): List<Summary> = synchronized(this) {
        state.conversations.sortedByDescending { it.updated }.map { Summary(it.id, it.title, it.updated) }
    }

    fun load(id: String): List<ChatSession.Entry>? = synchronized(this) {
        val conversation = state.conversations.firstOrNull { it.id == id } ?: return null
        conversation.messages.map { ChatSession.Entry(ChatMessage(it.role, it.content), it.display, it.model?.ifEmpty { null }) }
    }

    /** Stores [entries] as conversation [id], replacing the previous version of it. */
    fun save(id: String, entries: List<ChatSession.Entry>) = synchronized(this) {
        if (entries.isEmpty()) return@synchronized
        val conversation = state.conversations.firstOrNull { it.id == id }
            ?: ConversationState().also {
                it.id = id
                state.conversations.add(it)
            }
        conversation.title = titleOf(entries)
        // Strictly increasing, so the newest conversation is first even when two are saved in the same millisecond.
        conversation.updated = maxOf(System.currentTimeMillis(), (state.conversations.maxOfOrNull { it.updated } ?: 0) + 1)
        val kept = ArrayList<MessageState>()
        var size = 0
        // Keep the newest messages when a conversation is very long.
        for (entry in entries.asReversed()) {
            size += entry.message.content.length + entry.display.length
            if (size > MAX_CONVERSATION_CHARS && kept.isNotEmpty()) break
            kept += MessageState().also {
                it.role = entry.message.role
                it.content = entry.message.content
                it.display = entry.display
                it.model = entry.model
            }
        }
        conversation.messages = kept.asReversed().toMutableList()
        state.conversations.sortByDescending { it.updated }
        while (state.conversations.size > MAX_CONVERSATIONS) state.conversations.removeAt(state.conversations.lastIndex)
    }

    fun delete(id: String) = synchronized(this) { state.conversations.removeIf { it.id == id } }

    fun clear() = synchronized(this) { state.conversations.clear() }

    companion object {
        const val MAX_CONVERSATIONS = 50
        private const val MAX_CONVERSATION_CHARS = 200_000

        fun getInstance(project: Project): ChatHistory = project.service()

        fun titleOf(entries: List<ChatSession.Entry>): String {
            val first = entries.firstOrNull { it.message.role == "user" }?.display.orEmpty()
            val line = first.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("```") }.orEmpty()
            return if (line.length > 60) line.take(57) + "…" else line.ifEmpty { "Conversation" }
        }
    }
}
