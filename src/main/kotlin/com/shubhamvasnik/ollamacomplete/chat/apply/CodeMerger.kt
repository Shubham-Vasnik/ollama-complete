package com.shubhamvasnik.ollamacomplete.chat.apply

/**
 * Works out where a code block from the chat belongs in a file, without asking the model: either the block is the
 * whole file, or it starts with a declaration (`fun foo(`, `def foo(`, `class Foo`) that exists in the file, and
 * then that declaration is replaced. Returns null when neither applies.
 */
object CodeMerger {

    fun merge(file: String, block: String): String? {
        val eol = if (file.contains("\r\n")) "\r\n" else "\n"
        val fileLines = file.replace("\r\n", "\n").split('\n')
        val blockLines = block.replace("\r\n", "\n").trimEnd('\n').split('\n')
        if (blockLines.all { it.isBlank() }) return null

        val merged = if (isWholeFile(fileLines, blockLines)) {
            blockLines + if (fileLines.last().isEmpty()) listOf("") else emptyList()
        } else {
            val (start, end) = findDeclaration(fileLines, blockLines) ?: return null
            fileLines.subList(0, start) + reindent(blockLines, indentOf(fileLines[start])) + fileLines.subList(end, fileLines.size)
        }
        return merged.joinToString(eol)
    }

    private fun isWholeFile(fileLines: List<String>, blockLines: List<String>): Boolean {
        val fileFirst = fileLines.firstOrNull { it.isNotBlank() }?.trim() ?: return true
        val blockFirst = blockLines.first { it.isNotBlank() }.trim()
        return fileFirst == blockFirst && blockLines.size * 2 >= fileLines.count { it.isNotBlank() }
    }

    /** Returns the lines [start, end) of the declaration in the file that the block replaces. */
    internal fun findDeclaration(fileLines: List<String>, blockLines: List<String>): Pair<Int, Int>? {
        val headerIndex = blockLines.indexOfFirst { it.isNotBlank() && !isPreamble(it) }
        if (headerIndex < 0) return null
        val key = declarationKey(blockLines[headerIndex]) ?: return null
        val matches = fileLines.indices.filter { declarationKey(fileLines[it]) == key }
        if (matches.size != 1) return null
        val header = matches.single()
        val end = declarationEnd(fileLines, header) ?: return null
        // The block's own doc comment or annotations replace the ones in the file.
        var start = header
        if (headerIndex > 0) {
            while (start > 0 && fileLines[start - 1].isNotBlank() && isPreamble(fileLines[start - 1])) start--
        }
        return start to end
    }

    /** `  public static int max(int a, int b) {` → `public static int max`; null for lines that aren't declarations. */
    internal fun declarationKey(line: String): String? {
        val code = line.trim()
        val cut = code.indexOfFirst { it == '(' || it == '{' || it == ':' || it == '=' || it == '<' }
        val key = (if (cut < 0) code else code.substring(0, cut)).trim()
        val words = key.split(Regex("\\s+"))
        if (words.size < 2 || words.last().none { it.isLetter() }) return null
        if (!words.last().all { it.isLetterOrDigit() || it == '_' || it == '$' || it == '.' }) return null
        if (words.first() in NOT_DECLARATIONS) return null
        return key
    }

    /** End (exclusive) of the declaration starting at [header]: its braces, or its indented body after a `:`. */
    private fun declarationEnd(lines: List<String>, header: Int): Int? {
        var depth = 0
        var opened = false
        for (i in header until lines.size) {
            for (c in codeChars(lines[i])) {
                if (c == '{') {
                    depth++
                    opened = true
                } else if (c == '}') {
                    depth--
                }
            }
            if (opened && depth <= 0) return i + 1
            if (!opened && i == header && lines[i].trimEnd().endsWith(":")) {
                val indent = indentOf(lines[i]).length
                var end = i + 1
                while (end < lines.size && (lines[end].isBlank() || indentOf(lines[end]).length > indent)) end++
                while (end > i + 1 && lines[end - 1].isBlank()) end--
                return end
            }
            if (!opened && i - header >= MAX_HEADER_LINES) return null
        }
        return null
    }

    /** The characters of a line outside string literals and `//` comments. */
    private fun codeChars(line: String): Sequence<Char> = sequence {
        var quote: Char? = null
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quote != null && c == '\\' -> i++
                quote != null -> if (c == quote) quote = null
                c == '"' || c == '\'' || c == '`' -> quote = c
                c == '/' && line.getOrNull(i + 1) == '/' -> return@sequence
                c == '#' && line.trimStart().startsWith("#") -> return@sequence
                else -> yield(c)
            }
            i++
        }
    }

    private fun isPreamble(line: String): Boolean {
        val t = line.trim()
        return t.startsWith("//") || t.startsWith("/*") || t.startsWith("*") || t.startsWith("@") || t.startsWith("#[")
    }

    private fun reindent(lines: List<String>, indent: String): List<String> {
        val base = lines.filter { it.isNotBlank() }.minOfOrNull { indentOf(it).length } ?: 0
        return lines.map { if (it.isBlank()) "" else indent + it.substring(minOf(base, indentOf(it).length)) }
    }

    private fun indentOf(line: String) = line.takeWhile { it == ' ' || it == '\t' }

    private const val MAX_HEADER_LINES = 5
    private val NOT_DECLARATIONS = setOf("return", "if", "else", "for", "while", "when", "switch", "case", "throw", "import", "package", "from")
}
