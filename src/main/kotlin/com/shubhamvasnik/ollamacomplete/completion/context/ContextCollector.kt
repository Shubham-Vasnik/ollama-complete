package com.shubhamvasnik.ollamacomplete.completion.context

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.ex.IdeDocumentHistory
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile

/** Gathers the files whose code may help complete the current file: open tabs, recently edited files and siblings. */
object ContextCollector {
    const val MAX_FILES = 20
    const val MAX_FILE_CHARS = 200_000
    private const val MAX_SIBLINGS = 10

    /** Must be called inside a read action. Only files of the same type as [current] are returned. */
    fun collect(project: Project, current: VirtualFile): List<CandidateFile> {
        val candidates = LinkedHashSet<VirtualFile>()
        candidates += FileEditorManager.getInstance(project).openFiles
        candidates += IdeDocumentHistory.getInstance(project).changedFiles.asReversed()
        current.parent?.children?.asSequence()
            ?.filter { !it.isDirectory && it.fileType == current.fileType }
            ?.take(MAX_SIBLINGS)
            ?.forEach { candidates += it }

        val root = project.guessProjectDir()
        return candidates.asSequence()
            .filter { it != current && it.isValid && !it.isDirectory && it.fileType == current.fileType }
            .mapNotNull { file -> text(file)?.let { CandidateFile(path(file, root), it) } }
            .take(MAX_FILES)
            .toList()
    }

    /** Unsaved editor content wins over the file on disk. */
    private fun text(file: VirtualFile): String? {
        FileDocumentManager.getInstance().getCachedDocument(file)?.let { document ->
            return document.text.takeIf { it.length <= MAX_FILE_CHARS }
        }
        if (file.length > MAX_FILE_CHARS || file.fileType.isBinary) return null
        return runCatching { VfsUtilCore.loadText(file) }.getOrNull()
    }

    fun path(file: VirtualFile, root: VirtualFile?): String =
        root?.let { VfsUtilCore.getRelativePath(file, it) } ?: file.name
}
