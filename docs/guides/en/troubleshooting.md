# Troubleshooting

**[العربية](../ar/troubleshooting.md)** · [All guides](README.md)

Problems are grouped by what you see. Messages in *italics* are the exact texts Haris shows.

## Where to look first

| Place | What it tells you |
|---|---|
| The **Voice** drawer | Whether speech-to-text (**Hearing**), the voice (**Speech**) and the wake word are online, and where they run. |
| The **Security** drawer | Whether this window has a valid session (**Token verified**). |
| The **System** drawer | Which engine is in use. |
| `~\.haris\logs\haris.log` | The app log: start-up, Docker, engines, errors. Never conversation text, file contents or keys. Up to 2 MB per file; older files sit next to it. |
| `~\.haris\audit.jsonl` | Every tool call and its outcome. |
| `docker logs haris-voice-whisper-1` | Whisper's own log (also `haris-voice-piper-1`, `haris-search-searxng-1`). |

`~` is your user folder: open `%USERPROFILE%\.haris\logs` in File Explorer.

## Haris does not open

| Symptom | Cause and fix |
|---|---|
| The installer says *"Haris (or the older JARVIS) is running. Close its window first, then run the installer again."* | Close the Haris window, wait about 20 seconds for the core to exit, run `install.ps1` again. |
| The installer says *"Install incomplete: … is missing or truncated."* | A file was in use. Close Haris and any Haris window, run `install.ps1` again. |
| Nothing appears after starting Haris | Haris needs Edge or Chrome in their standard install folders. The log then says *"Neither Edge nor Chrome found. Open this address in a browser on this machine:"* followed by the address. Install Edge or Chrome, or open that exact address (including the `#token=…` part) in a browser on this PC. |
| PowerShell will not run `install.ps1` | Run `powershell -ExecutionPolicy Bypass -File install.ps1`. |

## "This window has no session token"

*"This window has no session token. Start Haris from its launcher so it can open its own window."*
The Security drawer shows **NOT AUTHORISED**.

The HUD only works in the window Haris opens itself: that window receives the per-launch session
token. You see this message if you opened the local address yourself (a bookmark, history, a
copied link without the token) or the address is from an earlier launch. Close the window and start
Haris from the Start menu. The token changes at every launch, so an old address never works again.

## The port changed, or the microphone is asked for again

Haris picks a random free port between 41000 and 59000 once and stores it in `~\.haris\port`, so
the browser remembers the microphone permission. If that port is taken by another program at
launch, Haris picks a new one and the browser asks for the microphone again; that is expected.

Only one Haris runs at a time. Starting it again while it runs brings the existing window to the
front (restored if it was minimised) instead of starting a second core; the log says *"Haris is
already running; this second launch brought its window to the front and exited."* If Windows does
not let it take the focus, its taskbar button flashes instead. If you start Haris while it is still
shutting down (in the 20 seconds after you closed the window, or while it stops its engines), the new
launch waits for that to finish and then opens a fresh window. After a crash nothing is left behind
that could block the next start.

## Docker is not running

You hear or see *"Both voice engines are offline — is Docker Desktop running?"* in the status
report, *"I could not speak: the speech engine did not answer. Check that Docker Desktop is running
— the Voice drawer shows each engine."*, or a web search fails. The log says *"Docker is not
running: voice and web search are off until it is started."*

1. Start Docker Desktop and wait until it says it is running.
2. Close Haris and start it again. Haris waits about two minutes for Docker at launch; it does not
   start the engines later on its own.

A failed web search says *"The local search engine is not running. Start Docker Desktop and wait
until it says it is running, then close Haris and start it again: Haris starts web search itself."*
Do not start SearXNG with `docker compose` by hand: it needs a secret that Haris keeps in Credential
Manager and passes only to its own `docker compose` call, so the hand-typed command fails.

## Voice engines stay offline

| Symptom | Cause and fix |
|---|---|
| **○ Offline** for several minutes after the very first start | Docker is downloading the images and the speech model. Wait; `docker ps` shows the containers once they run. |
| *"The speech-to-text engine is not running. Start Docker Desktop and wait until it says it is running, then close Haris and start it again: Haris starts its voice engines itself."* | Whisper did not answer. Check the Voice drawer and the log for *"docker compose up -d failed for …"*. Restarting Haris usually helps; it picks the right compose files (GPU or CPU) itself, so do not start the voice containers by hand. |
| Whisper keeps exiting after you closed Haris during its first model download | Haris detects the half-written model (*"Unable to open file 'model.bin'"*), removes the volume and downloads again by itself, once. If it still fails, close Haris and run `docker volume rm haris-voice_whisper-data`, then start Haris. |
| `WHISPER_OFFLINE=true` and a new `WHISPER_MODEL` | Offline mode forbids downloading the new model. Set `WHISPER_OFFLINE=false` for one start. |
| *"Still working on the previous recording. Say it again in a moment."* | Haris transcribes one recording at a time. Wait for the first one. |
| Haris ignores what you said | Very quiet recordings are dropped before transcription. Speak closer to the microphone; set **I speak** to your language instead of **Detect automatically**. |

## The microphone is blocked

*"Microphone access was refused, so the wake word is off."* or voice input fails at once.

1. In Windows **Settings → Privacy & security → Microphone**, allow microphone access, including
   for desktop apps.
2. Allow the microphone for the Haris window in the browser's site permissions.
3. If you once clicked *Block*, close Haris and delete the folder `~\.haris\hud-profile`. That is
   the HUD's private browser profile; the next launch asks again. (This also forgets a pinned drawer
   and the hidden-conversation choice.)

## Haris does not speak

| Symptom | Cause and fix |
|---|---|
| *"The browser is holding my voice back: click anywhere in this window once to enable it."* | Click once anywhere in the Haris window. |
| *"I could not speak: the speech engine did not answer…"* | Piper is not running (Docker), or Gemini failed and Piper is not running as a backup. Check the **Speech** row. |
| No sound at all, no error | Check **Speak replies aloud** in Settings and try **▶ Test speaker**. |

## The wake word does nothing

- **Listen for "Hey Haris"** is off by default; turn it on in Settings.
- Say a greeting immediately followed by the name: "Hey Haris", "Okay Haris", "Ya Haris". The name
  alone is ignored.
- Haris does not listen for the wake word while it is speaking. Use Ctrl+Space to interrupt.
- **○ No "hey haris" model** in the Voice drawer means the wake-word model is missing from the
  `models` folder. Reinstall, or when running from source run `models\fetch-models.ps1`.

## The GPU is not used

The Voice drawer shows **● Online** instead of **● Online (GPU)**. Check the log for one of these:

| Log line | Meaning and fix |
|---|---|
| *"Speech-to-text device: cpu (HARIS_WHISPER_GPU is off)"* | You switched the GPU off with `HARIS_WHISPER_GPU`. Remove the setting. |
| *"Speech-to-text device: cpu"* with no reason | Docker does not list the `nvidia` runtime, or `nvidia-smi -L` lists no GPU. Update the NVIDIA driver and enable GPU support in Docker Desktop. |
| *"Docker Compose … cannot apply compose.gpu.yaml (2.24.4 or later is needed); speech-to-text uses the CPU."* | Update Docker Desktop. |
| *"Downloading the GPU speech-to-text image … in the background (several GB, one time)..."* | First time: Haris uses the CPU now and the GPU from the next start. Progress appears in the log; an interrupted download continues next time. |
| *"Speech-to-text did not start on the GPU recently; using the CPU for now (delete … to try the GPU again)."* | The GPU container failed within the last three days. Fix the cause, then delete `~\.haris\whisper-gpu-failed` and restart Haris. |

Then look at `docker logs haris-voice-whisper-1` for the GPU container's own error. See
[Voice](voice.md#nvidia-gpu-speech-to-text).

## Gemini voice stops

- **● Piper (Gemini limit, back HH:MM)**: Google's rate limit, often the free daily quota. Piper
  speaks until that time, then Gemini again. Nothing to do, or use a key with a higher quota.
- The log says *"Gemini speech failed, using the local voice: Gemini speech answered HTTP 4xx"*:
  for 400, 401 or 403, check the key in **Settings → Voice → Google AI Studio key**. After any
  failure Piper speaks for one minute before Gemini is tried again.

## Colab session ended

| Message | Fix |
|---|---|
| *"Colab is not answering (HTTP 502…). If it runs in Colab, the session may have ended: run the notebook again."* | The Colab session ended (idle ~90 min, at most ~12 h). **Run all** in the notebook again. |
| *"Colab stopped answering for 120 s. The Colab session may have ended: run the notebook again."* | Same. |
| *"Colab rejected the API key or access token. Check it in Settings."* | The token changed: without the `HARIS_TOKEN` secret, each session prints a new one. Paste it, or save it as `HARIS_TOKEN` in Colab. |
| *"Colab is not reachable at …. Is it running?"* | Without an ngrok domain, the Cloudflare address changes every session. Paste the new **Colab address**. |
| *"Colab refused the request (HTTP 403)."* | A tunnel straight to Ollama (without the notebook) sends a host name Ollama refuses. Use the Haris notebook. |
| *"No Colab address yet. Run the notebook and paste its https:// link in Settings."* | Fill in **Colab address**. |

If speech on Colab fails, Haris uses your local Whisper for a minute and tries Colab again.

## The model list is empty

*"No models found. Is the engine running?"* after **Save & detect models**.

| Engine | Check |
|---|---|
| Ollama | Ollama is running and you pulled a model (`ollama list`). The URL is `http://127.0.0.1:11434`. |
| LM Studio | The local server is started in LM Studio and a model is loaded. The URL is `http://127.0.0.1:1234`. |
| OpenAI-compatible | The base URL is right and an API key is saved. Some providers do not list models: type the model name yourself. |
| Colab | The notebook is running, and the address and token are current. |

You can always type a model name into **Model** yourself.

## Errors in the conversation

| Message | Meaning |
|---|---|
| *"Ollama is not reachable at http://127.0.0.1:11434. Is it running?"* | Start Ollama (or LM Studio). |
| *"No model selected for Ollama. Pick one in Settings."* | Choose a model. |
| *"Ollama does not know the model "…"."* | The name is wrong or the model is not pulled. |
| *"No Claude API key saved. Add it in Settings."* · *"Claude rejected the API key. Check it in Settings."* | Paste a valid key. |
| *"Claude rate limit reached. Try again in a moment."* | Wait a little. |
| *"Could not reach Claude. Check your internet connection."* | Network problem. |
| *"No API key saved for the cloud provider. Add it in Settings."* | OpenAI-compatible needs a key. |
| *"This model does not support tools, so I can only talk…"* | Pick a tool-capable model ([Engines](engines.md#choosing-a-model)). |
| *"Still working on the previous message."* | One request at a time; wait for the reply. |
| *"The core is not responding."* | The core stopped. Close the window and start Haris again. |
| *"Something went wrong in the core. Details are in the log: %USERPROFILE%\\.haris\\logs\\haris.log"* | An unexpected error in the core. Open that file (paste the path into File Explorer); the last lines name the error. The same path is shown at the bottom of Settings. |
| *"I stopped after 12 steps to stay on the safe side. Ask me to continue if needed."* | A request needed many tool steps. Say "continue". |
| *"This conversation got very long, so I started a fresh one."* | Automatic after a very long conversation. |

## Tools refuse

| Message | Fix |
|---|---|
| *"No folders are granted. Add folders in Settings → Allowed folders."* | Add a folder in **Settings → Access**. |
| *"Outside the folders you granted: …"* | Add that folder, or move the file into an allowed one. |
| *"This file looks like it holds secrets, so it is off-limits."* · *"This location holds credentials and is off-limits."* | By design. |
| *"Only text files can be written (…)."* | Haris writes only text types. |
| *"No installed app is called "…". Use list_apps to see what is installed."* | Use the name as it appears in the Start menu. |
| *"The local search engine is not running…"* | See [Docker is not running](#docker-is-not-running). |
| *"The camera photo could not be taken."* · *"The camera did not respond (is the HUD open and the camera allowed?)."* | Allow the camera for the Haris window, and check that no other app holds it. |
| *"The memory file was modified outside Haris or the key changed; refusing to read it."* | `memory.enc` no longer matches the key in Credential Manager (for example after deleting `Haris\|memory-key`). The old facts cannot be read. To start a new memory, close Haris and move `~\.haris\memory.enc` elsewhere. |

## Reminders did not pop up

Reminders fire only while Haris is running. One that was missed by less than an hour is delivered
at the next start; older ones are dropped.

## Settings will not save

The red message at the bottom of the dialog names the problem:

| Message | Fix |
|---|---|
| *"Ollama must run on this machine (127.0.0.1 or localhost)"* (same for LM Studio) | Local engines must use a local address. For a remote server use **OpenAI-compatible cloud**. |
| *"OpenAI-compatible endpoint must use https://"* | Use the HTTPS address. |
| *"The Colab address must start with https:// (copy it from the notebook)."* | Copy the full address. |
| *"That is a local address; for a local Ollama choose the Ollama provider."* | Use **Ollama (local)**. |
| *"Folder does not exist: …"* | Check the path; one folder per line. |
| *"A whole drive cannot be granted: …"* · *"The whole home folder cannot be granted; pick folders inside it."* · *"This folder is off-limits for safety: …"* | Pick a narrower folder. |
| *"At most 10 folders can be granted."* | Remove some. |
| *"Voice names look like en_GB-alan-medium."* | Fix the Piper voice name. |

## Start with Windows is greyed out

*"Available in the installed app (package.ps1), not when running from source."* Use the installed
app.

## Reset settings

1. Close Haris.
2. Delete (or rename) `~\.haris\settings.json`.
3. Start Haris. It uses the defaults and asks you to choose an engine.

Your keys, memory, reminders and audit log are not affected. If `settings.json` cannot be read,
Haris already falls back to the defaults on its own (the log says *"settings.json unreadable or
unsafe … using defaults"*).

| To also reset | Delete (with Haris closed) |
|---|---|
| The window's permissions, pinned drawer, hidden conversation | `~\.haris\hud-profile` |
| Today's status report (to hear it again) | `~\.haris\last-greeting` |
| Everything | Run `uninstall.ps1 -RemoveData`, then install again. |
