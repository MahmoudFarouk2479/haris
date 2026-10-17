# Threat model

What Haris defends against, how, and what it does **not** protect you from. It is written for anyone
deciding whether to trust Haris, and for anyone trying to break it ([SECURITY.md](SECURITY.md)
says how to report what you find).

## What Haris is

A desktop assistant for Windows. A language model reads your request and proposes **tool calls**
from a fixed list of 25. The core (Java, Spring Boot) decides whether each call runs. The model never
runs anything itself, and there is no shell, exec or script tool.

```
You (click / type / voice) ──► HUD window ──► Core on 127.0.0.1 ──► Permission gate ──► 25 fixed tools
                                                   │
                                                   └──► The engine you chose: local (Ollama, LM Studio)
                                                        or remote (Claude, an OpenAI-compatible API, Colab)
```

## What we protect

| Asset | Where it lives |
|---|---|
| Your files | Only folders you add in Settings are visible to file tools. |
| Your API keys | Windows Credential Manager. The HUD can write or remove a key, never read it back. |
| Memory facts | `~\.haris`, AES-256-GCM, key in Credential Manager. Each fact is approved. |
| Your screen and camera | Captured only after you approve each picture, which you see first. |
| Your PC's state | Apps, files, clipboard and power change only after a click. |
| Your network setup | Haris never changes the firewall, network profiles or anything that needs administrator rights. |

## Who might attack

1. **Content the model reads.** Files, web pages, search results, clipboard text, screenshots, and
   folder, app or place names. This is *prompt injection*, and it is the main threat.
2. **Other programs and web pages on the same PC**, trying to reach the local server.
3. **Other devices on your network.**
4. **A compromised or curious engine provider** (when you choose a remote engine).
5. **Someone talking near the microphone**, including a video or another speaker.

Out of scope: an attacker who already runs code as your Windows user (they can read Credential
Manager anyway), and what a cloud provider you chose does with the data you send it.

## Design rules

- **The model proposes; the core decides.** Every tool call passes one gate in `AgentService`. Risk
  is set by the tool's code, never by the model.
- **No general-purpose power.** There are no shell, script, plugin, skill or marketplace extensions.
  Each tool does one thing, with checked arguments.
- **Only a click approves.** Approval buttons arm after 0.7 s. Esc and a 2-minute timeout deny.
  Approvals can never be given by voice, and are never decided by another model.
- **Outside content is data.** Tool results that come from outside are wrapped in
  `<external_content>` and marked as data. From then on, "allow for session" is off, and every tool
  that sends data out asks first and shows the exact data.
- **Stop is always there.** The Stop button ends a reply. An open approval counts as denied,
  nothing new runs, and a tool that is already running finishes.

## Risk tiers

| Tier | Tools | Rule |
|---|---|---|
| safe | `read_file`, `list_folder`, `search_files`, `web_search`, `fetch_page`, `weather`, `system_status`, `recall`, `reminders`, `media_control`, `list_apps`, power "cancel shutdown" | Runs without a click and is logged. After outside content arrives, the ones that send data out (`web_search`, `fetch_page`, `weather`) ask. |
| confirm | `open_app`, `open_url`, `write_file`, `create_folder`, `move_file`, `clipboard_write`, `compose_message`, `remember`, `forget` | A click, once or for the session. Clipboard read, screenshot, camera and power "lock" ask every time. |
| critical | `delete_file` (to the Recycle Bin), sleep, restart, shutdown | A click every time. Restart and shutdown wait 60 s and can be cancelled. |

Read-only tools don't ask on purpose. Asking for everything trains people to click "allow" without
reading, so Haris asks only when something changes or leaves the PC.

## Mapping to the OWASP Top 10 for Agentic Applications (2026)

| Risk | How Haris answers it | What remains |
|---|---|---|
| ASI01 Agent goal hijack | Outside content is fenced and marks the conversation; data-sending tools then ask and show the data. | A model can still be talked into *proposing* a bad action. The click is the defence, so read the card. |
| ASI02 Tool misuse | 25 fixed tools with argument checks. `PathGuard` limits files to chosen folders: no `..`, symlink, junction, UNC or alternate-stream escapes, and secrets are unreadable. New files can only be text types. | A tool you approve does what the card says. If you approve deleting a file, it goes to the Recycle Bin. |
| ASI03 Identity and privilege abuse | Runs as your user and never asks for admin. Keys are in Credential Manager and can't be read back by the HUD. | Haris has your user's rights inside the folders you chose. |
| ASI04 Agentic supply chain | No plugins, skills or marketplace. Docker images are pinned. The wake-word model is SHA-256-checked. | Java, npm and Docker dependencies are third-party code. |
| ASI05 Unexpected code execution | No shell, exec or script tool. Files can't be created or renamed to a type Windows would run. | Opening an app or link you approve starts that app. |
| ASI06 Memory and context poisoning | Each memory fact is approved. Facts that look like passwords, codes, keys or card numbers are refused. Memory is encrypted. | An approved fact that is wrong stays until you delete it in the Memory panel. |
| ASI07 Insecure inter-agent communication | There is one agent, with no agent-to-agent channels. | — |
| ASI08 Cascading failures | At most 12 steps per message. Remote engines are abandoned after 2 minutes of silence. Stop ends a reply. | — |
| ASI09 Human-agent trust exploitation | The card shows the exact arguments and, for pictures, the exact image. Buttons arm after 0.7 s. No voice approvals. The audit log has every call. | People can still approve without reading. Fewer, clearer prompts are the mitigation. |
| ASI10 Rogue agents | No background autonomy. The model acts only while answering: your message, or the optional daily briefing you turn on, which goes through the same gate. Reminders you set only notify. | — |

## The "Rule of Two"

Meta's *Agents Rule of Two* says an agent should hold at most two of these three:
- untrusted input;
- access to private data;
- the power to change things or send data out.

A desktop assistant needs all three. Haris therefore does what the rule asks for in that case: once
untrusted input is in the conversation, every action that changes something or sends data out
needs a human click, with the exact data shown.

## Local server

| Threat | Defence |
|---|---|
| Other devices on the network | Binds to `127.0.0.1` only. No firewall rules. |
| Other programs or web pages on this PC | A per-launch 256-bit token on every request except the HUD's static files. It is handed to the window the core opens, in the URL fragment. Tricky paths (`;`, `%`, `\`, `//`, `.` segments) are refused. |
| DNS rebinding, cross-site requests | `Host` must be loopback. State-changing calls must carry this server's `Origin`. |
| Script injection in the HUD | Strict CSP. Model output is rendered as text, never HTML. |
| A "local" engine that isn't local | Local engine URLs must be loopback. Cloud endpoints must be HTTPS. Redirects are not followed. |
| SSRF from web tools | Public addresses on ports 80/443 only. DNS answers are checked and pinned. Private, link-local, CGNAT and metadata addresses are refused. Redirects are re-checked. |

## Residual risks, plainly

- **Prompt injection is reduced, not solved.** Fencing and approvals limit what injected text can
  do without you, but a model can still be misled into proposing something harmful. Read the card
  before you click.
- **Remote engines see your conversation.** With Claude, an OpenAI-compatible API or Colab, that
  provider receives what you type or say, file contents Haris reads for you, approved clipboard
  text, web results, your newest memory facts and pictures you approve. Use a local engine to keep
  everything on the PC.
- **Read-only file tools run without a click.** Inside the folders you chose, `read_file`,
  `list_folder` and `search_files` run freely. With a remote engine, their results reach that
  provider. Add only folders you are happy for the engine to see.
- **The installer is not code-signed yet.** Check the SHA-256 against the release notes before you
  run it ([how](docs/guides/en/getting-started.md#check-the-file-and-why-windows-may-warn-you)).
- **Voice containers are third-party images.** They are pinned and hardened: loopback ports,
  read-only root, all capabilities dropped, no-new-privileges. They are still other people's code.

More detail: [Security and privacy guide](docs/guides/en/security-and-privacy.md).
