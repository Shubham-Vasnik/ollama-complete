package com.shubhamvasnik.ollamacomplete.statusbar

import com.intellij.icons.AllIcons
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.Consumer
import com.shubhamvasnik.ollamacomplete.api.OllamaClient
import com.shubhamvasnik.ollamacomplete.chat.ChatSession
import com.shubhamvasnik.ollamacomplete.settings.OllamaConfigurable
import com.shubhamvasnik.ollamacomplete.settings.OllamaSettings
import com.shubhamvasnik.ollamacomplete.settings.OllamaSettingsListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Component
import java.awt.Point
import java.awt.event.MouseEvent

class OllamaStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId() = ID
    override fun getDisplayName() = "OllamaComplete"
    override fun isAvailable(project: Project) = true
    override fun createWidget(project: Project): StatusBarWidget = OllamaStatusBarWidget(project)
    override fun canBeEnabledOn(statusBar: StatusBar) = true

    companion object {
        const val ID = "com.shubhamvasnik.ollamacomplete.statusBar"
    }
}

/** Shows the completion model in the status bar; clicking it switches models or toggles completion. */
private class OllamaStatusBarWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.TextPresentation {

    override fun ID() = OllamaStatusBarWidgetFactory.ID

    override fun install(statusBar: StatusBar) {
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(OllamaSettingsListener.TOPIC, OllamaSettingsListener { statusBar.updateWidget(ID()) })
    }

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun getText(): String {
        val settings = OllamaSettings.getInstance()
        val model = settings.completionModel
        return when {
            !settings.completionEnabled -> "OllamaComplete: off"
            model.isBlank() -> "OllamaComplete: select model"
            else -> "OllamaComplete: ${shortName(model)}"
        }
    }

    override fun getAlignment() = Component.CENTER_ALIGNMENT

    override fun getTooltipText() = "OllamaComplete code completion. Click to choose the model."

    override fun getClickConsumer() = Consumer<MouseEvent> { event -> showPopup(event.component) }

    private fun showPopup(component: Component) {
        val client = OllamaClient.getInstance()
        client.scope.launch {
            val models = try {
                client.listModels().map { it.name }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            withContext(Dispatchers.EDT) {
                if (project.isDisposed || !component.isShowing) return@withContext
                val popup = JBPopupFactory.getInstance().createActionGroupPopup(
                    "OllamaComplete: Completion Model",
                    buildGroup(models),
                    DataManager.getInstance().getDataContext(component),
                    JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
                    true,
                )
                popup.show(RelativePoint(component, Point(0, -popup.content.preferredSize.height)))
            }
        }
    }

    private fun buildGroup(models: List<String>?): DefaultActionGroup {
        val settings = OllamaSettings.getInstance()
        val group = DefaultActionGroup()
        when {
            models == null -> group.add(disabled("Cannot reach Ollama at ${settings.serverUrl}"))
            models.isEmpty() -> group.add(disabled("No models installed (try: ollama pull qwen2.5-coder:1.5b)"))
            else -> models.forEach { name ->
                val icon = if (name == settings.completionModel) AllIcons.Actions.Checked else null
                group.add(object : DumbAwareAction(name, null, icon) {
                    override fun actionPerformed(e: AnActionEvent) {
                        settings.completionModel = name
                        settings.completionEnabled = true
                        settings.fireChanged()
                    }
                })
            }
        }
        group.add(Separator.getInstance())
        val toggleText = if (settings.completionEnabled) "Disable Completion" else "Enable Completion"
        group.add(object : DumbAwareAction(toggleText) {
            override fun actionPerformed(e: AnActionEvent) {
                settings.completionEnabled = !settings.completionEnabled
                settings.fireChanged()
            }
        })
        group.add(object : DumbAwareAction("Open Chat") {
            override fun actionPerformed(e: AnActionEvent) = ChatSession.getInstance(project).withPanel {}
        })
        group.add(object : DumbAwareAction("Settings…") {
            override fun actionPerformed(e: AnActionEvent) {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, OllamaConfigurable::class.java)
            }
        })
        return group
    }

    private fun disabled(text: String) = object : DumbAwareAction(text) {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = false
        }

        override fun actionPerformed(e: AnActionEvent) = Unit
    }

    override fun dispose() = Unit

    private fun shortName(model: String): String {
        val name = model.substringAfterLast('/')
        return if (name.length > 28) name.take(27) + "…" else name
    }
}
