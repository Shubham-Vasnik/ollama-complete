package com.shubhamvasnik.ollamacomplete

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.shubhamvasnik.ollamacomplete.chat.ChatPanel
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
