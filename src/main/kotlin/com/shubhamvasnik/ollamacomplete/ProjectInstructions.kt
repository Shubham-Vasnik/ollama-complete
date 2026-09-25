package com.shubhamvasnik.ollamacomplete

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtilCore

/**
 * Project-specific instructions for the models, like Copilot's `.github/copilot-instructions.md`.
 * `.ollamacomplete/instructions.md` wins; the Copilot file is used when it is missing.
 */
object ProjectInstructions {
    val PATHS = listOf(".ollamacomplete/instructions.md", ".github/copilot-instructions.md")
    const val MAX_CHARS = 4000

    /** Must be called inside a read action. */
    fun read(project: Project): String? {
        val dir = project.guessProjectDir() ?: return null
        for (path in PATHS) {
            val file = dir.findFileByRelativePath(path) ?: continue
            if (file.isDirectory) continue
            val text = runCatching { VfsUtilCore.loadText(file) }.getOrNull()?.trim().orEmpty()
            if (text.isNotEmpty()) return text.take(MAX_CHARS)
        }
        return null
    }

    fun appendTo(systemPrompt: String, instructions: String?): String {
        if (instructions.isNullOrBlank()) return systemPrompt
        val base = systemPrompt.trimEnd()
        val section = "Project instructions:\n$instructions"
        return if (base.isEmpty()) section else "$base\n\n$section"
    }
}
