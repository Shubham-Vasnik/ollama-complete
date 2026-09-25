package com.shubhamvasnik.ollamacomplete

import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.shubhamvasnik.ollamacomplete.chat.ChatPanel
import com.shubhamvasnik.ollamacomplete.chat.ChatSession
import com.shubhamvasnik.ollamacomplete.chat.apply.ApplyCode
import com.shubhamvasnik.ollamacomplete.chat.apply.CodeMerger
import com.shubhamvasnik.ollamacomplete.settings.CompletionMode
import com.shubhamvasnik.ollamacomplete.settings.OllamaSettings
import java.awt.Container
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JComponent

/**
 * Renders the chat tool window content in a headless IDE against a real Ollama server. Skipped unless OLLAMA_IT=1.
 * The screenshot is written to build/ui-test/chat.png.
 */
class OllamaLiveUiTest : BasePlatformTestCase() {

    private val enabled = System.getenv("OLLAMA_IT") == "1"
    private val model = System.getenv("OLLAMA_IT_MODEL") ?: "qwen3:8b"

    override fun setUp() {
        super.setUp()
        OllamaSettings.getInstance().apply {
            completionModel = model
            chatModel = model
            completionEnabled = true
            completionMode = CompletionMode.AUTO
            debounceMs = 0
            requestTimeoutSec = 300
        }
    }

    fun `test chat streams an answer with a code block`() {
        if (!enabled) return
        val panel = ChatPanel(project)
        Disposer.register(testRootDisposable, panel)
        panel.setSize(560, 900)

        panel.sendPrompt("Write a Kotlin function that reverses a string. Reply with one short sentence and one code block.")
        val deadline = System.currentTimeMillis() + 180_000
        while (panel.isBusy() && System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(50)
        }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertFalse("chat did not finish in time", panel.isBusy())

        val out = File("build/ui-test").apply { mkdirs() }
        render(panel, File(out, "chat.png"))
        println("screenshot: ${File(out, "chat.png").absolutePath}")
    }

    fun `test fix command answers with code that applies to the file`() {
        if (!enabled) return
        val source = "public class Util {\n    public static int max(int a, int b) {\n        return a < b ? a : b;\n    }\n}\n"
        myFixture.configureByText(PlainTextFileType.INSTANCE, source)
        val panel = ChatPanel(project)
        Disposer.register(testRootDisposable, panel)
        panel.setSize(560, 900)

        panel.newChat()
        panel.sendPrompt("/fix max returns the smaller value")
        waitUntilDone(panel)

        val session = ChatSession.getInstance(project)
        val question = session.entries.first { it.message.role == "user" }
        assertTrue(question.message.content, question.message.content.contains("return a < b ? a : b;"))
        assertTrue(question.display, question.display.contains("_Attached:"))
        val answer = session.entries.last { it.message.role == "assistant" }.message.content
        println("fix answer:\n$answer")
        val block = ApplyCode.largestCodeBlock(answer)
        assertNotNull("no code block in: $answer", block)
        val merged = CodeMerger.merge(source, block!!)
        println("merged file:\n$merged")
        assertNotNull("the answer's code block could not be placed in the file", merged)
        assertTrue(merged!!, merged.contains("a > b") || merged.contains("Math.max") || merged.contains("b < a"))

        val out = File("build/ui-test").apply { mkdirs() }
        render(panel, File(out, "chat-fix.png"))
    }

    private fun waitUntilDone(panel: ChatPanel) {
        val deadline = System.currentTimeMillis() + 180_000
        while (panel.isBusy() && System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(50)
        }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertFalse("chat did not finish in time", panel.isBusy())
    }

    private fun render(component: JComponent, file: File) {
        repeat(3) { layout(component) }
        val image = BufferedImage(component.width, component.height, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        component.paint(g)
        g.dispose()
        ImageIO.write(image, "png", file)
    }

    private fun layout(component: Container) {
        component.doLayout()
        component.components.forEach { if (it is Container) layout(it) }
    }
}
