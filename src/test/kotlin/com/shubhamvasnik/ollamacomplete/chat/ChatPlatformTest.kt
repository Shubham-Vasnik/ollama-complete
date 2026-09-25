package com.shubhamvasnik.ollamacomplete.chat

import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.shubhamvasnik.ollamacomplete.api.ChatMessage
import com.shubhamvasnik.ollamacomplete.chat.commands.ChatContextResolver
import kotlinx.coroutines.runBlocking
import com.intellij.util.xmlb.XmlSerializer

/** Chat history and message preparation inside a real (light) project, without Ollama. */
class ChatPlatformTest : BasePlatformTestCase() {

    private fun entry(role: String, text: String) = ChatSession.Entry(ChatMessage(role, text), text)

    fun `test history saves, lists and loads conversations`() {
        val history = ChatHistory.getInstance(project)
        history.clear()
        history.save("a", listOf(entry("user", "How do I parse JSON?\nmore"), entry("assistant", "Use kotlinx.serialization.")))
        history.save("b", listOf(entry("user", "Second question")))
        assertEquals(listOf("Second question", "How do I parse JSON?"), history.summaries().map { it.title })
        assertEquals(listOf("user", "assistant"), history.load("a")!!.map { it.message.role })
        history.delete("a")
        assertNull(history.load("a"))
    }

    fun `test history survives serialization`() {
        val history = ChatHistory.getInstance(project)
        history.clear()
        val code = "Fix this:\n```kotlin\nval x = \"a<b>&\"\n```"
        history.save("x", listOf(entry("user", code), ChatSession.Entry(ChatMessage("assistant", "Done."), "Done.", "qwen")))
        val element = XmlSerializer.serialize(history.state)
        val restored = ChatHistory()
        restored.loadState(XmlSerializer.deserialize(element, ChatHistory.HistoryState::class.java))
        val entries = restored.load("x")!!
        assertEquals(code, entries[0].message.content)
        assertEquals("qwen", entries[1].model)
        assertEquals("Fix this:", restored.summaries().single().title)
    }

    fun `test session keeps the old conversation when starting a new one`() {
        val session = ChatSession.getInstance(project)
        session.entries.clear()
        ChatHistory.getInstance(project).clear()
        session.entries += entry("user", "First chat")
        val firstId = session.conversationId
        session.clear()
        assertTrue(session.entries.isEmpty())
        assertTrue(session.load(firstId))
        assertEquals("First chat", session.entries.single().display)
    }

    fun `test command attaches the selection`() {
        myFixture.configureByText(PlainTextFileType.INSTANCE, "fun a() = 1\n<selection>fun b() = 2</selection>\nfun c() = 3\n")
        val editor = ChatContextResolver.captureEditor(project)
        val prepared = runBlocking { ChatContextResolver(project).prepare("/explain briefly", editor, includeCurrentFile = false) }
        assertTrue(prepared.content, prepared.content.contains("```txt\nfun b() = 2\n```"))
        assertFalse(prepared.content, prepared.content.contains("fun a()"))
        assertTrue(prepared.content, prepared.content.contains("Explain what the following code does"))
        assertTrue(prepared.content, prepared.content.endsWith("briefly"))
        assertTrue(prepared.display, prepared.display.startsWith("/explain briefly\n\n_Attached:"))
    }

    fun `test command without selection attaches the file`() {
        myFixture.configureByText(PlainTextFileType.INSTANCE, "fun a() = 1\n")
        val prepared = runBlocking {
            ChatContextResolver(project).prepare("/tests", ChatContextResolver.captureEditor(project), includeCurrentFile = false)
        }
        assertTrue(prepared.content, prepared.content.contains("open in my editor"))
        assertTrue(prepared.content, prepared.content.contains("fun a() = 1"))
    }

    fun `test file references and missing files`() {
        myFixture.addFileToProject("src/Billing.txt", "fun charge(amount: Int) = amount * 2")
        myFixture.configureByText(PlainTextFileType.INSTANCE, "x")
        val prepared = runBlocking {
            ChatContextResolver(project).prepare("Compare #file:Billing.txt with #file:Nope.txt", ChatContextResolver.captureEditor(project), false)
        }
        assertTrue(prepared.content, prepared.content.contains("fun charge(amount: Int)"))
        assertTrue(prepared.display, prepared.display.contains("file not found: Nope.txt"))
    }

    fun `test workspace search attaches matching code`() {
        myFixture.addFileToProject("src/UserRepository.txt", "class UserRepository {\n    fun saveUser(user: User) = db.insert(user)\n}")
        myFixture.addFileToProject("src/Weather.txt", "fun forecast(city: City) = sunny")
        myFixture.configureByText(PlainTextFileType.INSTANCE, "x")
        val prepared = runBlocking {
            ChatContextResolver(project).prepare("@workspace where is the user saved?", ChatContextResolver.captureEditor(project), false)
        }
        assertTrue(prepared.content, prepared.content.contains("db.insert(user)"))
        assertFalse(prepared.content, prepared.content.contains("forecast"))
    }

    fun `test prompt files become commands`() {
        myFixture.addFileToProject(".ollamacomplete/prompts/review.md", "# Review our way\nCheck logging.")
        val commands = com.intellij.openapi.application.runReadAction { ChatContextResolver(project).commands() }
        assertTrue(commands.map { it.name }.toString(), commands.any { it.name == "review" && it.prompt == "Check logging." })
    }
}
