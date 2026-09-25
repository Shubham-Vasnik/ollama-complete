package com.shubhamvasnik.ollamacomplete.chat.workspace

import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.checkCanceled
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.shubhamvasnik.ollamacomplete.completion.context.ContextCollector
import java.util.concurrent.ConcurrentHashMap

/**
 * Keyword search over the project's source files for `@workspace` questions. Files are chunked on first use and
 * re-chunked only when they change, so later searches are quick.
 */
@Service(Service.Level.PROJECT)
class WorkspaceIndex(private val project: Project) {

    private class Entry(val stamp: Long, val chunks: List<Chunk>)

    private val cache = ConcurrentHashMap<String, Entry>()

    suspend fun search(query: String, limit: Int): List<Chunk> {
        val files = readAction { contentFiles() }
        val root = project.guessProjectDir()
        val chunks = ArrayList<Chunk>()
        val seen = HashSet<String>()
        for (file in files) {
            checkCanceled()
            val path = ContextCollector.path(file, root)
            seen += file.path
            val stamp = file.modificationStamp + (FileDocumentManager.getInstance().getCachedDocument(file)?.modificationStamp ?: 0)
            val entry = cache[file.path]?.takeIf { it.stamp == stamp }
                ?: Entry(stamp, text(file)?.let { Bm25Index.chunk(path, it) }.orEmpty()).also { cache[file.path] = it }
            chunks += entry.chunks
        }
        cache.keys.retainAll(seen)
        return Bm25Index(chunks).search(query, limit).map { it.first }
    }

    /** Text files in the project's content roots, without excluded folders, libraries or huge files. */
    private fun contentFiles(): List<VirtualFile> {
        val result = ArrayList<VirtualFile>()
        var total = 0L
        ProjectFileIndex.getInstance(project).iterateContent { file ->
            if (!file.isDirectory && !file.fileType.isBinary && file.length in 1..MAX_FILE_BYTES) {
                result += file
                total += file.length
            }
            result.size < MAX_FILES && total < MAX_TOTAL_BYTES
        }
        return result
    }

    private suspend fun text(file: VirtualFile): String? {
        readAction { FileDocumentManager.getInstance().getCachedDocument(file)?.text }?.let { return it }
        return runCatching { VfsUtilCore.loadText(file) }.getOrNull()
    }

    companion object {
        private const val MAX_FILES = 5_000
        private const val MAX_FILE_BYTES = 300_000L
        private const val MAX_TOTAL_BYTES = 50_000_000L

        fun getInstance(project: Project): WorkspaceIndex = project.service()
    }
}
