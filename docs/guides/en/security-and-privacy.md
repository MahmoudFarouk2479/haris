# Security and privacy

**[العربية](../ar/security-and-privacy.md)** · [All guides](README.md)

Haris is built on one idea: **the model is not trusted.** It can only *propose* actions. Haris
decides, by fixed rules, which actions run on their own, which need your click, and what the model
may see. This page explains those rules in plain words, what leaves your PC, and where Haris keeps
its files.

## How Haris protects you

| Risk | What Haris does |
|---|---|
| Other devices on your network reaching Haris | The core listens on `127.0.0.1` only. It never opens firewall ports, never changes your network profile, and never needs administrator rights. |
| Other programs or web pages on your PC talking to Haris | Every launch creates a new random 256-bit session token. Haris hands it to the window it opens itself (in the part of the address that browsers never send over the network) and requires it on every request except the HUD's own files. Requests must also name the right host and, when they change something, come from Haris's own page. Odd-looking paths are refused outright. |
| Injected scripts in the HUD | A strict Content Security Policy allows only Haris's own scripts, and model output is always shown as plain text. |
| Leaked API keys | Keys live in Windows Credential Manager. The window can store them but never read them back. |
| A "local" engine quietly sending data away | Local engine addresses must be on this PC; cloud addresses must use HTTPS; redirects are never followed. |
| Running code the model wrote | Impossible: there is no shell, script or "run program" tool. Every tool is a fixed operation with checked arguments. Apps open only from your Start menu list. |
| The model approving its own actions | Approvals come only from a click in the HUD. Buttons arm after 0.7 s; Esc and a two-minute timeout deny; voice cannot approve. |
| Planted instructions in files, the clipboard, web pages or screenshots | That text is marked as outside content and fenced off for the model as data. From then on, "Allow for session" stops working and anything that sends data out asks you first, showing the exact data. |
| Files outside what you chose | File tools reach only your allowed folders (see [Files](#files)). |
| Web tools reaching your local network | Only public addresses on ports 80 and 443; private, local and cloud-metadata addresses are refused, and redirects are re-checked (see [Web](#web)). |
| Poisoned memory | Every new memory needs your approval; passwords, codes, keys and card or account numbers are refused. |
| A stalled cloud connection | Remote engines are dropped after two minutes of silence. |

## Risk levels and approvals

| Level | What happens | Examples |
|---|---|---|
| **Safe** | Runs and is logged. | Reading files in allowed folders, listing apps, weather, web search, reminders, volume. |
| **Confirm** | Needs a click: **Allow once**, or for some tools **Allow for session**. | Opening apps or links, writing or moving files, clipboard, message drafts, screenshots, camera, remembering. |
| **Critical** | Needs a click every time. | Deleting (to the Recycle Bin), sleep, restart, shut down. |

The full list per tool, and how the approval card works, is in
[Using Haris](using-haris.md#what-you-can-ask).

## What leaves your PC

| Data | With a local engine (Ollama, LM Studio) | With Claude, an OpenAI-compatible cloud or Colab |
|---|---|---|
| Wake-word audio | Never (Vosk runs inside Haris) | Never |
| Your recorded speech | Never (local Whisper) | Never, except to your Colab session with *speech on Colab* on |
| What you type, and the transcript of what you say | Never | Sent to that provider |
| Text Haris reads for you: files in allowed folders, the clipboard (after approval), web pages, search results | Never | Sent to that provider as part of the conversation |
| Screenshots and camera photos | Never | Sent, only after you approve each one |
| Your newest memory facts | Never | Part of each conversation's instructions |
| Spoken replies | Never (Piper on your PC) | Never, unless you choose the Gemini voice: then every spoken sentence goes to Google as text |
| Web searches | The query goes to public search engines through the local SearXNG (no cookies, accounts or browser history) | Same. With *Allow Claude to search the web* on, Claude can also search through Anthropic. |
| Pages Haris reads | The page's address goes to that website | Same |
| Weather | The city name goes to Open-Meteo | Same |
| Links you approve, message drafts | Opened in your browser or messaging app; nothing is sent until you press Send there | Same |
| Settings, memory, reminders, logs | Stay in `~\.haris` | Stay in `~\.haris` |

Conversations are kept in memory only and vanish when you press **Clear** or close Haris. Voice
recordings are kept in memory only for the moment they are transcribed.

## Where Haris keeps its data

Everything Haris writes lives in `~\.haris` (for example `C:\Users\you\.haris`):

| File or folder | Contents |
|---|---|
| `settings.json` | Your settings, as plain JSON. No keys. |
| `memory.enc` | Your memory facts, encrypted (below). |
| `audit.jsonl` | The audit log (below). |
| `audit.redacted-v1` | An empty marker: older audit entries have been cleaned once. |
| `reminders.json` | Pending reminders. |
| `logs\haris.log` | The app log, up to 2 MB per file, three old files kept. Start-up, engine and error events only, never conversation text, file contents or keys. |
| `hud-profile\` | The HUD window's private browser profile (microphone permission, pinned drawer, chat column choice). |
| `port` | The local port Haris reuses, so the browser remembers the microphone permission. |
| `last-greeting`, `last-briefing` | The date of the last status report and briefing. |
| `whisper-gpu-failed` | Present only when the GPU speech-to-text failed recently. |

Elsewhere:

| Where | What |
|---|---|
| Windows Credential Manager, entries starting with `Haris\|` | Your API keys and tokens (`anthropic`, `openai-compatible`, `colab`, `gemini`), the memory key (`memory-key`) and SearXNG's internal secret (`searxng-secret`). |
| Docker volumes `haris-voice_whisper-data`, `haris-voice_piper-data`, `haris-voice_whisper-gpu-data`, `haris-search_searxng-cache` | Downloaded speech models, voices and the search cache. |
| `HKCU\Software\Microsoft\Windows\CurrentVersion\Run`, value `Haris` | Only if you turned on *Start with Windows*. |
| `%LOCALAPPDATA%\Programs\Haris` | The installed program. |

`uninstall.ps1 -RemoveData` removes all of this (see [Getting started](getting-started.md#uninstall)).

## The audit log

Every tool call, approved or not, gets one line in `~\.haris\audit.jsonl`, for example:

```json
{"time":"2026-10-16T08:12:03.512Z","tool":"open_app","summary":"Open app: Spotify","risk":"CONFIRM","decision":"approved","outcome":"ok"}
```

| Field | Values |
|---|---|
| `decision` | `auto` (safe, ran without asking), `session` (under "Allow for session"), `approved`, `approved (session)` (the click that granted it), `denied`, `user` (something you did: undo, deleting memory, autostart), `rejected` (the model named a tool that does not exist) |
| `outcome` | `ok`, `not run`, or `error: …` |

The log never stores private content: clipboard text, message bodies and memory facts appear only
as a length, for example *Copy 120 characters to clipboard*. The approval card shows you the
content; the file on disk does not keep it. The last 50 entries also feed the Activity drawer.

## Memory encryption

- Facts are stored in `~\.haris\memory.enc`, encrypted with **AES-256-GCM**.
- The key is a random 256-bit key Haris creates and keeps in Windows Credential Manager
  (`Haris|memory-key`). It is never shown to the window and cannot be replaced from it.
- If the file is changed outside Haris, or the key no longer matches, Haris refuses to read it
  rather than use tampered data.
- Every fact was approved by you. Up to 500 facts of up to 300 characters each.
- The newest facts (about 2,500 characters) are included in each new conversation; older ones are
  found with the `recall` tool. With a cloud engine, those facts therefore reach the provider.
- You can see and delete everything in the **Memory** dialog.

## Files

File tools follow these rules (`PathGuard`):

- Only inside your **allowed folders**, checked on the real path: no `..` tricks, symbolic links
  or junctions that lead outside.
- No network paths (`\\server\share`), alternate data streams or device paths.
- Never read or written: `.env` files, private keys (`id_rsa`, `.pem`, `.key`, `.pfx`, `.ppk` …),
  password databases (`.kdbx`, `.kdb`), files named like a secret, password or credential,
  browser `Login Data` and `Cookies`, `wallet.dat`, and folders such as `.ssh`, `.gnupg`, `.aws`,
  `.azure`, `.kube`, `.docker`, `.haris`.
- New files can only be text: `.txt .md .markdown .csv .tsv .json .log .yaml .yml .xml .srt`.
  Hidden files and folders are never modified.
- Nothing is created or renamed to a type Windows would run (`.exe`, `.bat`, `.ps1`, `.lnk`,
  `.js`, `.py`, macro-enabled Office files, disk images and many more), and a rename may not change
  a file's type.
- Moves never overwrite; deletes go to the Recycle Bin.

## Web

- `web_search` goes to the local SearXNG container (`127.0.0.1:10500`), which asks public search
  engines. They see the query and your internet address, but never your browser, cookies or
  accounts.
- `fetch_page` reads only public `http(s)` pages on ports 80 and 443. Addresses are checked when
  they are resolved and then pinned; private, local, carrier-grade NAT and cloud-metadata addresses
  are refused; every redirect (at most three) is checked again. Hidden elements are removed and the
  text is limited to 15,000 characters.

## Voice and search containers

- Voice (Whisper and Piper): ports on `127.0.0.1` only, read-only root filesystem, all Linux
  capabilities dropped, no privilege escalation, memory and CPU caps, pinned image versions.
- Search (SearXNG): port on `127.0.0.1` only, no privilege escalation, memory and CPU caps, pinned
  image; it keeps four capabilities its start-up script needs to switch to its own user. Its secret
  comes from Credential Manager and is never written to a file.
- All of them start and stop with Haris.

## What Haris cannot protect against

- Someone who already runs programs as your Windows user. They could read your keys from Credential
  Manager anyway.
- What a cloud provider you chose does with the data you send it.
- Approving something you did not read. The card shows exactly what will happen: take the second.

## Found a vulnerability?

Please **do not open a public issue**. Use GitHub's **Security → Report a vulnerability**
(private advisory) on the repository, and include what an attacker can do and from where, steps to
reproduce and the Haris version (`pom.xml`). You should get an answer within a week; fixes are
released before details are published. The full policy, including what is in scope, is in
[SECURITY.md](../../../SECURITY.md).
