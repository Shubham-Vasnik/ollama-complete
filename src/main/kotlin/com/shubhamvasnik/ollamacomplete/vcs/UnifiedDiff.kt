package com.shubhamvasnik.ollamacomplete.vcs

/** Changed lines [start1, end1) of the old text replaced by [start2, end2) of the new text (0-based). */
data class LineRange(val start1: Int, val end1: Int, val start2: Int, val end2: Int)

/** Writes changes in the `git diff` format models know best. */
object UnifiedDiff {

    fun format(path: String, before: String?, after: String?, ranges: List<LineRange>, context: Int = 3): String {
        val old = lines(before)
        val new = lines(after)
        val header = when {
            before == null -> "--- /dev/null\n+++ b/$path\n"
            after == null -> "--- a/$path\n+++ /dev/null\n"
            else -> "--- a/$path\n+++ b/$path\n"
        }
        if (ranges.isEmpty()) return ""
        return header + group(ranges.sortedBy { it.start1 }, context).joinToString("") { hunk(it, old, new, context) }
    }

    private fun group(ranges: List<LineRange>, context: Int): List<List<LineRange>> {
        val groups = mutableListOf(mutableListOf(ranges.first()))
        for (range in ranges.drop(1)) {
            if (range.start1 - groups.last().last().end1 > 2 * context) groups += mutableListOf(range) else groups.last() += range
        }
        return groups
    }

    private fun hunk(ranges: List<LineRange>, old: List<String>, new: List<String>, context: Int): String {
        val first = ranges.first()
        val last = ranges.last()
        val start1 = maxOf(0, first.start1 - context)
        val end1 = minOf(old.size, last.end1 + context)
        val start2 = first.start2 - (first.start1 - start1)
        val end2 = last.end2 + (end1 - last.end1)
        val body = StringBuilder()
        var pos = start1
        for (range in ranges) {
            for (i in pos until range.start1) body.append(' ').append(old[i]).append('\n')
            for (i in range.start1 until range.end1) body.append('-').append(old[i]).append('\n')
            for (i in range.start2 until range.end2) body.append('+').append(new[i]).append('\n')
            pos = range.end1
        }
        for (i in pos until end1) body.append(' ').append(old[i]).append('\n')
        return "@@ -${position(start1, end1 - start1)} +${position(start2, end2 - start2)} @@\n$body"
    }

    /** Git numbers lines from 1, and an empty range points at the line before it. */
    private fun position(start: Int, count: Int) = if (count == 0) "$start,0" else "${start + 1},$count"

    private fun lines(text: String?): List<String> =
        if (text.isNullOrEmpty()) emptyList() else text.replace("\r\n", "\n").removeSuffix("\n").split('\n')
}
