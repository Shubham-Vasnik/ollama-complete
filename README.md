# OllamaComplete

An IntelliJ plugin for code completion and chat that uses local models served by [Ollama](https://ollama.com).
No code leaves your machine.

## Features

- **Inline code completion**: suggestions appear as gray text while you type.
  - **Tab** accepts, **Esc** dismisses, **Shift+Alt+\\** (*Call Inline Completion*) asks for a suggestion immediately.
  - Uses fill-in-the-middle (FIM) with models that support it, such as `qwen2.5-coder`. Other chat/instruct models (qwen3, gemma, gpt-oss…) are sent an instruct prompt instead.
  - Thinking is turned off for completion requests, so reasoning models reply quickly.
- **Model selection**
  - Status bar: click `OllamaComplete: <model>` to switch the completion model or turn completion on/off.
  - Chat window: a model dropdown at the top.
  - **Settings | Tools | OllamaComplete**: server URL, completion and chat models, and tuning options.
- **OllamaComplete chat tool window** (right sidebar)
  - Answers stream in and can be stopped. Markdown is rendered.
  - Code blocks are syntax-highlighted and have **Copy** and **Insert at Caret** buttons.
  - An **Include current file** option sends the open file along with your question.
  - An optional "Thoughts" section shows the reasoning of models that support thinking.
- **Editor right-click menu → OllamaComplete**: *Explain Selection* and *Add Selection to Chat*.
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
| Multi-line completions | on | A completion in the middle of a line is always limited to one line. |
| Delay after typing | 300 ms | The wait before a request is sent. |
| Max tokens | 128 | `num_predict` for completion requests. |
| Context before/after caret | 4000 / 1000 chars | How much of the file is sent to the model. |
| Keep model loaded for | 10m | Ollama `keep_alive`. |

## Project layout

```
src/main/kotlin/com/shubhamvasnik/ollamacomplete/
  api/          OllamaClient (HTTP + NDJSON streaming), DTOs
  settings/     persistent settings + Settings page
  completion/   inline completion provider, prompt builder, output post-processing
  chat/         tool window, chat panel, message rendering, code blocks
  actions/      editor / Tools menu actions
  statusbar/    status bar model switcher
```
