package com.shubhamvasnik.ollamacomplete

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import java.util.concurrent.ConcurrentHashMap

object OllamaNotifier {
    private const val GROUP_ID = "OllamaComplete"
    private const val REPEAT_INTERVAL_MS = 60_000L
    private val lastShown = ConcurrentHashMap<String, Long>()

    fun info(project: Project?, message: String) = notify(project, message, NotificationType.INFORMATION)

    fun error(project: Project?, message: String) = notify(project, message, NotificationType.ERROR)

    /** Completion runs on every keystroke, so the same error is shown at most once a minute. */
    fun errorOnce(project: Project?, message: String) {
        val now = System.currentTimeMillis()
        val last = lastShown[message]
        if (last != null && now - last < REPEAT_INTERVAL_MS) return
        lastShown[message] = now
        notify(project, message, NotificationType.WARNING)
    }

    private fun notify(project: Project?, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification(StringUtil.escapeXmlEntities(message), type)
            .notify(project)
    }
}
