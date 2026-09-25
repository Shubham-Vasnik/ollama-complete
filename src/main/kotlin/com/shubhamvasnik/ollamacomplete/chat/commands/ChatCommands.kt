package com.shubhamvasnik.ollamacomplete.chat.commands

/** A `/name` shortcut at the start of a chat message that expands to a prompt, like Copilot's `/fix` or `/tests`. */
data class SlashCommand(val name: String, val description: String, val prompt: String)

object ChatCommands {
    val BUILT_IN = listOf(
        SlashCommand(
            "explain", "Explain how the code works",
            "Explain what the following code does, step by step. Mention anything surprising or risky.",
        ),
        SlashCommand(
            "fix", "Find and fix problems",
            "Find the bugs or problems in the following code and fix them. " +
                "Reply with the corrected code in one code block, then briefly explain each change.",
        ),
        SlashCommand(
            "tests", "Generate unit tests",
            "Write unit tests for the following code. Use the test framework and style the project already uses when it " +
                "is obvious from the code. Cover normal cases, edge cases and errors. Reply with the test code in one code block.",
        ),
        SlashCommand(
            "doc", "Write documentation comments",
            "Add documentation comments to the following code in the usual style for its language (KDoc, Javadoc, " +
                "docstrings…). Don't change the code itself. Reply with the documented code in one code block.",
        ),
        SlashCommand(
            "simplify", "Simplify the code",
            "Simplify the following code without changing its behavior. " +
                "Reply with the simplified code in one code block, then a short list of what changed.",
        ),
    )

    private val commandPattern = Regex("^/([\\w-]+)(?:\\s+|$)")

    /** Returns the command at the start of [input] and the rest of the text, or null if there is none. */
    fun parse(input: String, commands: List<SlashCommand>): Pair<SlashCommand, String>? {
        val match = commandPattern.find(input.trimStart()) ?: return null
        val command = commands.firstOrNull { it.name.equals(match.groupValues[1], ignoreCase = true) } ?: return null
        return command to input.trimStart().substring(match.value.length).trim()
    }

    /**
     * A prompt file from `.ollamacomplete/prompts/<name>.md` becomes `/<name>`. A first line starting with `#`
     * is its description; the rest is the prompt.
     */
    fun fromPromptFile(fileName: String, text: String): SlashCommand? {
        val name = fileName.substringBeforeLast('.').trim().replace(' ', '-')
        if (name.isEmpty() || text.isBlank()) return null
        val lines = text.trim().lines()
        val first = lines.first()
        return if (first.startsWith("#")) {
            val prompt = lines.drop(1).joinToString("\n").trim()
            if (prompt.isEmpty()) null else SlashCommand(name, first.trimStart('#').trim(), prompt)
        } else {
            SlashCommand(name, "Prompt from $fileName", text.trim())
        }
    }

    /** Project prompt files override built-in commands of the same name. */
    fun merge(builtIn: List<SlashCommand>, custom: List<SlashCommand>): List<SlashCommand> {
        val names = custom.map { it.name.lowercase() }.toSet()
        return builtIn.filter { it.name.lowercase() !in names } + custom
    }
}
