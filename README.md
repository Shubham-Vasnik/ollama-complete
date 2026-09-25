# OllamaComplete

An IntelliJ plugin for code completion and chat that uses local models served by [Ollama](https://ollama.com).
No code leaves your machine.

## Features

- **Inline code completion**: suggestions appear as gray text while you type.
  - **Tab** accepts, **Esc** dismisses, **Shift+Alt+\\** (*Call Inline Completion*) asks for a suggestion immediately.
  - **Ctrl+→** accepts the next word and **End** the rest of the line (the IDE's *Next Word* / *Line End* shortcuts).
  - **Alt+]** / **Alt+[** cycle through alternative suggestions, which are generated after the first one is shown.
  - Suggestions stream in line by line, and the request stops as soon as the block at the caret ends.
  - Keep typing what the suggestion says and it stays; the rest is reused without asking the model again.
  - Several lines are only suggested on an empty line or after a block opener (`{`, `(`, `:`, `=>`, `->`), like Copilot.
  - The completion model is loaded when a project opens, so the first suggestion is not slowed down by loading it.
  - Excluded folders (e.g. `build/`), minified files and very large files are skipped.
  - Uses fill-in-the-middle (FIM) with models that support it, such as `qwen2.5-coder`. Other chat/instruct models (qwen3, gemma, gpt-oss…) are sent an instruct prompt instead.
  - Thinking is turned off for completion requests, so reasoning models reply quickly.
  - **Related code from other files**: like Copilot's "neighboring tabs", open tabs, recently edited files and files in the same
    folder are cut into 60-line windows, and the windows most similar to the code at the caret are added to the prompt.
- **Project instructions**: put conventions in `.ollamacomplete/instructions.md` (or `.github/copilot-instructions.md`)
  and they are added to chat and instruct-mode completion prompts.
- **Model selection**
  - Status bar: click `OllamaComplete: <model>` to switch the completion model or turn completion on/off.
  - Chat window: a model dropdown at the top.
  - **Settings | Tools | OllamaComplete**: server URL, completion and chat models, and tuning options.
- **OllamaComplete chat tool window** (right sidebar)
  - Answers stream in and can be stopped. Markdown is rendered.
  - Code blocks are syntax-highlighted and have **Copy**, **Insert at Caret** and **Apply to File** buttons.
    *Apply* shows an editable diff before changing the file. It replaces the selection if there is one, otherwise the
    declaration with the same signature (method, function, class), otherwise it asks the chat model to merge the code.
  - **Slash commands** at the start of a message: `/explain`, `/fix`, `/tests`, `/doc`, `/simplify`. They work on the
    selection, or on the whole file when nothing is selected. Add your own as `.ollamacomplete/prompts/<name>.md`
    (an optional first line `# Description`, then the prompt); they appear as `/<name>`.
  - **Context references** anywhere in a message: `#file:path/Foo.kt`, `#selection`, `#problems` (errors and warnings
    in the current file) and `@workspace` (searches the project's files for code related to the question).
    Typing `/`, `#` or `@` shows suggestions.
  - An **Include current file** option sends the open file along with your question.
  - **History**: conversations are saved per project; the clock button reopens an earlier one.
  - An optional "Thoughts" section shows the reasoning of models that support thinking.
- **Editor right-click menu → OllamaComplete**: *Explain Selection*, *Generate Tests*, *Generate Documentation* and *Add Selection to Chat*.
- **Alt+Enter on an error → Fix with OllamaComplete** sends the error and the code to the chat with `/fix`.
- **Commit message**: the OllamaComplete button in the commit window writes the message from the included changes
  with the chat model.
- **Tools → OllamaComplete Code Completion** toggles completion.

## Requirements

- IntelliJ IDEA 2026.1 or newer (it also works in other JetBrains IDEs built on 2026.1+).
- Ollama running locally (default `http://localhost:11434`).

For fast, accurate completions, pull a small coder model that supports FIM:

```
ollama pull qwen2.5-coder:1.5b     # very fast
ollama pull qwen2.5-coder:7b       # better quality
```

Bigger general models work for completion too, but each suggestion takes longer.

## Build

JDK 21 is required. The Gradle wrapper downloads everything else.

```
./gradlew buildPlugin        # -> build/distributions/OllamaComplete-0.1.0.zip
./gradlew test               # unit tests
./gradlew runIde             # start a sandbox IDE with the plugin installed
./gradlew verifyPlugin       # JetBrains Plugin Verifier
```

Live tests run the real completion, the inline gray text in an editor, and the chat panel against a running Ollama.
The chat panel screenshot is saved to `build/ui-test/chat.png`.

```
OLLAMA_IT=1 OLLAMA_IT_MODEL=qwen3:8b ./gradlew test
```

## Install

**Settings | Plugins | ⚙ | Install Plugin from Disk…**, select `build/distributions/OllamaComplete-0.1.0.zip`,
then restart if the IDE asks you to.

## Settings

| Setting | Default | Notes |
|---|---|---|
| Server URL | `http://localhost:11434` | Use **Test Connection** to check it. |
| Completion / Chat model | – | Filled from `/api/tags`. Embedding-only models are hidden. |
| Prompt mode | Auto | Auto uses FIM when the model has the `insert` capability and an instruct prompt otherwise. |
| Multi-line completions | on | Only on empty lines and after block openers; a completion in the middle of a line is always one line. |
| Suggestions per request | 2 | 1 turns alternatives off. |
| Complete inside comments | on | |
| Disabled for languages | – | Language IDs or file extensions, e.g. `Markdown, txt`. |
| Delay after typing | 300 ms | The wait before a request is sent. |
| Max tokens | 128 | `num_predict` for completion requests. |
| Context before/after caret | 4000 / 1000 chars | How much of the file is sent to the model. |
| Use related code from other files | on | Adds up to 4 snippets from open, recently edited and sibling files of the same type. |
| Related code budget | 3000 chars | Total size of those snippets. FIM prompts need a language with line comments. |
| Keep model loaded for | 10m | Ollama `keep_alive`. |

## Project layout

```
src/main/kotlin/com/shubhamvasnik/ollamacomplete/
  api/          OllamaClient (HTTP + NDJSON streaming), DTOs
  settings/     persistent settings + Settings page
  completion/   inline completion provider, prompt builder, output post-processing
  chat/         tool window, chat panel, message rendering, code blocks, history
    commands/   slash commands, #/@ references, message preparation
    workspace/  @workspace keyword search (BM25)
    apply/      Apply to File: code placement and diff dialog
  actions/      editor / Tools menu actions, Fix with OllamaComplete intention
  vcs/          commit message generation (loaded only when VCS support is present)
  statusbar/    status bar model switcher
```
