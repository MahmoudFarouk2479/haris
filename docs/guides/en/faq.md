# FAQ

**[العربية](../ar/faq.md)** · [All guides](README.md)

### Does Haris run on macOS or Linux?

No. Haris is a Windows 10/11 app: it uses Windows Credential Manager for keys, the Windows Start
menu to find apps, the Recycle Bin, Windows media keys and power functions, and opens its window
with Edge or Chrome from their Windows install folders.

### Does it work offline?

Mostly, with a local engine. Use Ollama or LM Studio, let Docker download the speech models once,
then set `WHISPER_OFFLINE=true` in `voice\.env` ([Voice](voice.md#advanced-voice-settings)).
Conversation, voice, files, apps, reminders and memory then work without the internet. Web search,
reading pages, weather, and a cloud or Colab engine of course need it.

### What does it cost?

Haris is free and open source (MIT). What you may pay for is outside Haris: a cloud engine's API
usage (Anthropic or another provider bills you directly), or a Google AI Studio key beyond its free
quota for the Gemini voice. Local engines, Piper, Whisper and SearXNG cost nothing. Colab has a free
tier with session limits.

### Which model is best?

It depends on your hardware and privacy needs. Claude handles tools and pictures without extra
setup. Locally, pick a tool-capable model such as `qwen2.5:7b`, and a vision model (for example
`qwen2.5vl:7b`) if you want Haris to see your screen. If your PC is slow, run the same models on
Colab's GPU. See [Engines](engines.md#choosing-a-model).

### Do I need a GPU?

No. Everything runs on a CPU. An NVIDIA GPU makes speech-to-text faster, and Haris uses it
automatically when Docker can reach it. A GPU also helps local models, but that is up to Ollama or
LM Studio.

### Why Docker?

The voice engines (Whisper, Piper) and the search engine (SearXNG) are separate programs with their
own dependencies. Docker runs them isolated and hardened (local ports only, read-only, no
capabilities for the voice containers), with pinned versions, and Haris starts and stops them with
itself. Docker is optional: without it you can still type.

### Why Java?

The core is a Java 21 / Spring Boot application, which also uses Anthropic's official Java SDK for
Claude. You do not need Java installed: the release bundles its own Java runtime.

### Does Haris send my data anywhere?

With a local engine, nothing about your conversation leaves your PC; only web searches (through the
local SearXNG), pages you ask it to read and weather lookups go out. With a cloud or Colab engine,
your conversation goes to that provider. Screenshots and photos are sent only after you approve
each one. The full table is in [Security and privacy](security-and-privacy.md#what-leaves-your-pc).

### Can it run code or commands on my PC?

No. There is no shell, script or "run program" tool. Haris can only open apps that appear in your
Start menu, and every tool is a fixed operation with checked arguments.

### Can it send messages or emails for me?

No. It opens a draft in WhatsApp, Telegram or your mail app; you review it and press Send yourself.

### Is my screen or camera watched?

No. Haris takes one screenshot or one webcam photo only when you ask and approve it. You see the
screenshot before it is shared; camera photos appear in the conversation. A **CAMERA ON** badge
shows while the camera is on.

### Can I approve actions by voice?

No, on purpose. A video or someone nearby could say "allow". Approvals need a click.

### Does it understand and speak Arabic?

Yes. Speak Arabic (choose **I speak → Arabic** for Egyptian Arabic), and Haris answers in Egyptian
Arabic when you write or speak Arabic, or always if you choose so. The interface can be fully
Arabic and right-to-left. For the most natural Egyptian voice, use the Gemini voice.

### Can I rename the assistant?

Yes, in **Settings → Identity → Assistant name**. The wake word stays "Hey Haris".

### Where are my conversations saved?

Nowhere on disk. They are kept in memory and vanish when you press **Clear** or close Haris. What
Haris remembers long-term is only the facts you approved, in the encrypted memory.

### Does Haris start with Windows?

Only if you turn on **Settings → Identity → Start Haris when I sign in to Windows** (installed app).

### How do I update Haris?

Close it and run the new release's `install.ps1`. Your data in `~\.haris` and your keys stay; only
a `voice\.env` inside the program folder is replaced ([Getting started](getting-started.md#upgrading)).

### What happens when I close the window?

About 20 seconds later the core shuts down and stops its Docker engines. Nothing keeps listening or
running in the background.

### I used JARVIS before. What happens to my data?

Haris was called JARVIS before its first public release. On its first start it moves `~\.jarvis` to
`~\.haris`, moves your stored keys to the new name, and removes the old JARVIS Start-with-Windows entry
(a Haris one is added if that setting is on); the installer removes the old program files.
