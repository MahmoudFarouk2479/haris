# Haris — a security-first desktop AI assistant

**[العربية](README.ar.md)**

A sci-fi holographic assistant for Windows that can actually do things on your PC — open
apps, manage files, search the web, see your screen, talk and listen — **without trusting the model**.
Every action goes through a permission gate you control, keys live in the OS vault, and the server
is unreachable from anything but its own window.

Java 21 + Spring Boot core · Three.js HUD · your choice of brain:
**Claude**, **Ollama**, **LM Studio**, **any OpenAI-compatible API**, or **Ollama on a free Google Colab GPU**.

> *Haris* (حارس) means *guardian* in Arabic. Built after auditing a popular open-source assistant that opened firewall ports, switched the
> Wi-Fi profile to *Private* and ran model-written code. This project designs every one of those out.

## Features

| | |
|---|---|
| 🧠 **Any model** | Claude (official SDK, tool use, vision), local Ollama / LM Studio, OpenAI-compatible clouds, or Ollama on Colab behind a token-checking proxy. |
| 🛠 **25 tools** | Apps, files, clipboard, reminders, weather, media keys, lock / sleep / restart, message drafts, system status, web, vision, memory. |
| 🎙 **Voice** | "Hey Haris" wake word, push-to-talk, Arabic and English speech, spoken replies sentence by sentence, barge-in, conversation mode. |
| 👁 **Vision** | One screenshot or one webcam photo, on request, previewed before you approve it. |
| 🔎 **Private web search** | A local SearXNG instance; pages reduced to text with hidden elements removed. |
| 🔐 **Encrypted memory** | Facts you approve, AES-256-GCM, key in Windows Credential Manager. |
| 🌅 **Daily briefing** | Date, weather, reminders and headlines on the first launch of the day. |
| 📦 **Installer** | Self-contained app (its own Java runtime), per-user install, no admin rights. |

## Quick start

**Requirements:** Windows 10/11, Microsoft Edge or Chrome. Optional: [Docker Desktop](https://www.docker.com/products/docker-desktop/)
for speech-to-text, spoken replies and web search.

### Install a release

1. Download `Haris-<version>-windows.zip` from *Releases* and unzip it.
2. Right-click `install.ps1` → *Run with PowerShell* (per user, no admin).
3. Start **Haris** from the Start menu, open **⚙ Configure** and choose an engine.

`uninstall.ps1` removes the app; `uninstall.ps1 -RemoveData` also erases settings, memory, stored
keys and engine data.

### Build from source

Requirements: JDK 21, Maven 3.9+, Node 20+.

```powershell
.\run.ps1         # build if needed and run from source
.\package.ps1     # build the installable zip into target\ (runs the tests)
```

Both download the wake-word model once (`models/fetch-models.ps1`, SHA-256 checked).

## Choosing a brain

| Engine | Setup | Where your conversation goes |
|---|---|---|
| **Claude** | Paste an API key from [console.anthropic.com](https://console.anthropic.com). Default `claude-opus-5-5`; *Low* effort is snappiest for voice. | Anthropic |
| **Ollama / LM Studio** | Install, pull a tool-capable model (e.g. `qwen2.5:7b`), pick it in Configure. | Stays on your PC |
| **OpenAI-compatible** | Base URL (HTTPS) + API key. | That provider |
| **Ollama on Colab** | See below. | Your Colab session (Google) |

### Ollama on a free Colab GPU

1. Upload `colab/haris_colab.ipynb` to [Google Colab](https://colab.research.google.com) and
   pick *Runtime → Change runtime type → T4 GPU*.
2. *(Once)* In Colab's 🔑 **Secrets**, add `NGROK_AUTHTOKEN` (free ngrok account) and optionally
   `NGROK_DOMAIN` (your free static domain) — then the address stays the same every session.
   Without them the notebook uses a Cloudflare quick tunnel.
3. *Run all*. Paste the printed **address** and **access token** into Configure → *Ollama on Google
   Colab*. Save the token as the `HARIS_TOKEN` secret to keep it across sessions.

The notebook keeps models on your Google Drive (no re-download next session) and also runs
**speech-to-text on the GPU** (faster-whisper `large-v3-turbo`), which Haris uses automatically,
falling back to local Whisper. Ollama listens only inside Colab; the proxy (`colab/haris_proxy.py`)
admits only requests with the 256-bit token, only to the model list, chat and speech endpoints, and
locks out clients that guess. Colab sessions end (idle ~90 min, max ~12 h) — Haris tells you within
two minutes; *Run all* again.

## Security model

| Threat | Defence |
|---|---|
| Other devices on the network | Server binds to `127.0.0.1` only. No firewall rules, no admin rights, ever. |
| Other programs / web pages on this PC | Per-launch 256-bit session token, handed to the window the core opens itself (URL fragment), required on every request except the HUD's own static files. Paths with tricks Spring would rewrite (`;`, `%`, `\`, `//`, `.` segments) are refused outright. |
| DNS rebinding · cross-site requests | `Host` must be the loopback address; state-changing calls must carry this server's `Origin`. |
| Script injection in the HUD | Strict CSP (`script-src 'self'`, `connect-src 'self'`), model output rendered as text only. |
| Leaked API keys | Keys live in Windows Credential Manager; the UI can write them but never read them back. |
| A "local" engine quietly sending data away | Local engine URLs must be loopback; cloud endpoints must be HTTPS; redirects are never followed. |
| Running model-written code | Impossible: no shell, no exec, no script tool. Every tool is a fixed operation with validated arguments. |
| The model approving its own actions | Approvals come only from a click in the HUD — buttons arm after 0.7 s, Esc and a 2-minute timeout deny. Approvals can't be spoken (a video nearby could say "allow"). |
| Prompt injection (files, clipboard, web, screenshots) | Marked as outside content. Once it is in the conversation, "allow for session" stops working and every action that sends data out shows the exact data for approval. |
| Files outside what you chose | `PathGuard`: real-path checks (no `..`, symlink or junction escapes), no UNC or alternate data streams, secrets (`.env`, keys, `.kdbx` …) unreadable. New files can only be text types (`.txt`, `.md`, `.csv`, `.json` …), a rename may not change a file's type, and nothing is created or renamed to a type Windows would run. |
| SSRF from web tools | Only public addresses on ports 80/443; DNS answers checked inside the resolver and pinned; private, link-local, CGNAT and metadata addresses refused; redirects re-checked. |
| Memory poisoning | Each new memory needs approval; passwords, codes and card numbers are refused. |
| A stalled cloud connection hanging the app | Remote engines are abandoned after two minutes of silence, mid-reply included. |

Risk tiers: **safe** tools run and are logged (`~/.haris/audit.jsonl`); **confirm** tools need a click
(once or for the session); **critical** ones (delete to Recycle Bin, sleep / restart / shut down)
need a click every time. Restart and shutdown wait 60 s and can be cancelled.

Found a hole? See [SECURITY.md](SECURITY.md).

## Privacy: what leaves your PC

| Data | With a local model | With Claude / cloud / Colab |
|---|---|---|
| Wake-word audio | never (Vosk runs inside Haris) | never |
| Your speech | never (local Whisper) | never — except Colab with *speech on Colab GPU* on |
| Conversation text | never | sent to that provider |
| Spoken replies | never (Piper speaks on this PC) | the reply text goes to Google only if you choose the Gemini voice |
| Screenshots / photos | never | sent, only after you approve each picture |
| Web searches | to search engines, via local SearXNG (no cookies or accounts) | same |
| Settings, memory, logs | stay in `~/.haris` | stay in `~/.haris` |

Conversations are kept in memory only and vanish when Haris closes.

## Voice

| Engine | Runs | Job |
|---|---|---|
| Vosk + `vosk-model-small-en-us` | inside Haris | "Hey Haris" — decodes only while someone is speaking |
| Whisper (`small-int8`) | Docker, `127.0.0.1:10300` | speech → text, Arabic and English |
| Piper | Docker, `127.0.0.1:10200` | text → speech (`en_GB-alan-medium`, `ar_JO-kareem-medium`) |
| Gemini TTS *(optional)* | Google, your AI Studio key | natural text → speech, including Egyptian Arabic; Piper takes over if it fails |

Push-to-talk: hold **Ctrl+Space** or click the microphone. A **MIC** badge shows whenever the
microphone is open. Containers are hardened: loopback ports, read-only root filesystem, all
capabilities dropped, no-new-privileges, memory/CPU caps, pinned image versions; they start and stop
with Haris. Set `WHISPER_OFFLINE=true` in `voice/.env` after the first start to forbid model downloads.

**Speed.** On a laptop CPU a voice turn takes several seconds: Whisper ~3–4 s, the first spoken
sentence ~1.5–2.5 s, plus the model. Faster options: Colab speech-to-text, a smaller Whisper
(`WHISPER_MODEL=base-int8`), or a GPU.

## Tests

```powershell
mvn test
```

The permission gate, path guard, settings validation, SSRF rules, encrypted memory, image handling,
the voice protocol, the Colab speech client, stalled-stream handling and the wake-word matcher.
To measure wake-word accuracy on your own recordings, set `HARIS_WAKE_CLIPS` to a folder of
16 kHz 16-bit mono `.pcm` files named `pos*` (should wake) and `neg*` (should not).

## Layout

```
src/main/java/dev/haris/
  security/   LocalAccessFilter, SessionToken, SecretStore (Credential Manager)
  llm/        ChatProvider, AnthropicProvider, OpenAiCompatibleProvider, ProviderFactory
  agent/      AgentService (loop + permission gate), ConfirmationBroker, AuditLog, UndoStack, EventHub
  tools/      Tool, Risk, PathGuard, ToolRegistry, impl/ (the tools)
  voice/      WakeWordDetector (Vosk), VoiceEngines (Whisper/Piper), ColabSpeech, VoiceController
  vision/     screen & camera tools, Images (scale + strip metadata)
  web/        WebSearchTool, FetchPageTool, PageFetcher (pinned DNS), NetGuard (SSRF rules)
  memory/     MemoryStore (AES-256-GCM), remember / recall / forget
  settings/   Settings, validation; system/ briefing, autostart, engines; launcher/ app window
ui/           Vite + Three.js HUD
voice/ search/  Docker Compose files for the local engines
colab/        notebook, its generator, and the token proxy
models/       fetch-models.ps1 (the model itself is downloaded, not committed)
packaging/    icon, install.ps1, uninstall.ps1
```

## Name and history

The project started as "JARVIS" and was renamed to Haris before its first public release. On
the first start, Haris moves data from an earlier JARVIS install (`~/.jarvis`, stored keys, the
Start-with-Windows entry) to its new name; the installer removes the old program files.

## License

[MIT](LICENSE). Third-party components and their licenses: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

