# Engines

**[العربية](../ar/engines.md)** · [All guides](README.md)

The engine is the language model that understands you and decides which tools to use. Haris
itself (the approvals, the tools, the voice, your files) always runs on your PC; only the engine
changes. You choose it in **Settings → Engine → Provider** (Arabic: **الإعدادات ← المحرك ← المزوّد**).

## At a glance

| Provider in Settings | Runs on | Your conversation goes to | Needs |
|---|---|---|---|
| **Ollama (local)** | Your PC | Nowhere: it stays on your PC | Ollama installed, a model pulled |
| **LM Studio (local)** | Your PC | Nowhere: it stays on your PC | LM Studio's local server running, a model loaded |
| **Ollama on Google Colab (free GPU)** | A Google Colab GPU | Your Colab session (Google's servers) | A Google account, the Haris notebook |
| **Claude (Anthropic API)** | Anthropic | Anthropic | An Anthropic API key |
| **OpenAI-compatible cloud** | That provider | That provider | A base URL (HTTPS) and an API key |

The default provider on a fresh install is **Ollama (local)** with no model chosen, which is why
Haris asks you to pick an engine first.

Rules Haris enforces for every engine:

- **Local means local.** The Ollama and LM Studio URLs must point at this PC (`127.0.0.1` or
  `localhost`); anything else is refused when you save. A "local" engine can never quietly send
  your conversation away.
- **Clouds use HTTPS.** The OpenAI-compatible URL must start with `https://` (a loopback
  `http://` address is also accepted); the Colab address must be `https://`.
- **No redirects** are followed when talking to an engine.
- **Stalled remote engines are dropped.** If a cloud or Colab engine stays silent for two minutes,
  even in the middle of a reply, Haris gives up and tells you. Local engines get up to ten minutes,
  because a big model on a CPU can really be that slow.

After you change the engine (or any setting other than the interface language), Haris starts a
fresh conversation with the new rules.

## Claude (Anthropic API)

1. Create an API key at [console.anthropic.com](https://console.anthropic.com).
2. In Settings choose **Claude (Anthropic API)**, paste the key into **API key** and click **Save**.
   The hint changes to **● Key stored in vault**.
3. Pick a **Model** and an **Effort** (below).

| Field | Default | Notes |
|---|---|---|
| **Model** | `claude-opus-5-5` | Suggestions: `claude-opus-5-5`, `claude-sonnet-5-5`, `claude-haiku-5-5`, `claude-fable-5-1`. You can type another model name. |
| **Effort** | Medium | How much the model thinks before answering: **Low — fastest**, **Medium**, **High**, **Extra high**, **Max**. |
| **API key** | — | Write-only. Leave it empty to keep the saved key. |
| **Allow Claude to search the web** | Off | Lets Claude use Anthropic's own web search (up to 5 searches per request). Results are treated as outside content. This is separate from Haris's local `web_search` tool, which Claude can always use. |

Claude handles screenshots and camera photos (vision).
If one of the listed models' safety checks declines a request, Haris asks Anthropic to route it to
a suitable model instead of returning nothing.

### Effort

Effort applies only to Claude. Lower effort answers sooner; higher effort reasons more on hard
requests. For voice, **Low** is the snappiest; **Medium** (the default) suits most typed use.
Effort does not change what Haris is allowed to do.

## Ollama (local)

1. Install [Ollama](https://ollama.com) and pull a model that supports tools, for example:
   ```powershell
   ollama pull qwen2.5:7b
   ```
2. In Settings choose **Ollama (local)**. The **Ollama URL** is already `http://127.0.0.1:11434`.
3. Click **Save & detect models**, pick the model in **Model**, and click **Save**.

Haris talks to Ollama through its OpenAI-compatible API (`/v1/models`, `/v1/chat/completions`).

## LM Studio (local)

1. In LM Studio, load a model and start the local server.
2. In Settings choose **LM Studio (local)**. The **LM Studio URL** is already `http://127.0.0.1:1234`.
3. Click **Save & detect models**, pick the model, click **Save**.

## OpenAI-compatible cloud

For any provider that offers an OpenAI-style chat completions API.

| Field | Default | Notes |
|---|---|---|
| **Base URL** | `https://api.openai.com` | Must be `https://` (or a loopback address). A trailing `/v1` is fine: Haris removes it and adds its own `/v1/...` paths. |
| **Model** | — | Use **Save & detect models** to list what the provider offers. |
| **API key** | — | Required. Write-only, stored in Windows Credential Manager. |

In the HUD this engine is labelled **Cloud**.

## Ollama on Google Colab

Runs the model, and optionally your speech-to-text, on a free Colab GPU. Useful when your PC is
too slow for a local model. **Your conversations and, with speech on Colab, your voice are
processed on Google's servers while you use it.**

### Set up the notebook

1. Open [Google Colab](https://colab.research.google.com) and upload `haris_colab.ipynb` (it is in
   the release zip, and in the repository's `colab` folder).
2. Choose **Runtime → Change runtime type → T4 GPU**.
3. *(Once, recommended)* In Colab's 🔑 **Secrets** panel add, with *Notebook access* switched on:

   | Secret | What it is |
   |---|---|
   | `NGROK_AUTHTOKEN` | Your authtoken from a free ngrok account. Without it, the notebook uses a Cloudflare quick tunnel with a new address every session. |
   | `NGROK_DOMAIN` | *(optional)* Your free static ngrok domain, so the address stays the same. |
   | `HARIS_TOKEN` | *(optional)* The access token printed by the first run, so the token stays the same. |

4. In cell **1 · Settings** you can change:

   | Setting | Default | Choices |
   |---|---|---|
   | `MODEL` | `qwen2.5:7b` | `qwen2.5:7b`, `qwen2.5:14b`, `llama3.1:8b`, `qwen2.5vl:7b` (can see screenshots), `gemma3:12b`, or type another |
   | `STT_MODEL` | `large-v3-turbo` | `large-v3-turbo`, `medium`, `small`, `off` (keeps speech on your PC) |
   | `USE_DRIVE` | on | Keeps models in `MyDrive/haris/` so the next session does not download them again |

5. **Runtime → Run all.** At the end the notebook prints:
   ```
   Colab address : https://….ngrok-free.dev   (or …trycloudflare.com)
   Model         : qwen2.5:7b
   Access token  : …
   ```
   Keep the Colab tab open while you use Haris. Treat the token like a password.

### Connect Haris

1. In Settings choose **Ollama on Google Colab (free GPU)**.
2. Paste the **Colab address** and the **Access token**, and type (or detect) the **Model**.
3. Leave **Also recognise speech on Colab's GPU** on if you want faster speech-to-text (below).
4. Click **Save**. The hint shows **● Token stored in vault — protected by the notebook proxy**.

You can paste the address with or without a path: Haris keeps only `https://host[:port]`.

### How the Colab link is protected

- Ollama listens only inside the Colab machine.
- A small proxy (`colab/haris_proxy.py`) sits in front of it. It accepts only requests carrying
  your 256-bit access token, and only three routes: the model list, chat, and speech-to-text.
  Nobody can pull, delete or copy models through it, even with the address.
- A client that sends 20 wrong tokens within a minute is locked out for that minute.
- Speech is transcribed in memory and never written to disk.

You can also paste a Colab port address (`https://11434-….colab.dev/…`) that goes straight to
Ollama without the notebook. Haris accepts it, but warns: **⚠ No token**: anyone who learns that
address can use, add or delete models on your Colab. The notebook is the protected way.

### Speech on Colab

With **Also recognise speech on Colab's GPU** on (the default) and a token saved, your recordings
are transcribed by faster-whisper on the Colab GPU instead of your PC. The **Voice** drawer then
shows **● Colab GPU** (or **● Colab GPU (local backup)** when your local Whisper is also running).
If Colab does not answer, Haris uses the local engine for the next minute, then tries Colab again.

### When the session ends

Colab sessions end on their own (idle around 90 minutes, at most around 12 hours). Within two
minutes Haris shows a message such as *"Colab is not answering … the session may have ended: run
the notebook again."* Run all again; with the ngrok secrets and `HARIS_TOKEN` the address and token
stay the same, so there is nothing to change in Haris.

## What goes where

| | Local (Ollama, LM Studio) | Claude / OpenAI-compatible | Colab |
|---|---|---|---|
| What you type or say (as text) | Stays on your PC | Sent to the provider | Sent to your Colab session |
| File, clipboard and web text Haris reads for you | Stays on your PC | Sent to the provider | Sent to your Colab session |
| Screenshots and camera photos | Stay on your PC | Sent, only after you approve each one | Sent, only after you approve each one |
| Your recorded voice | Stays on your PC | Stays on your PC | Sent to Colab only with speech on Colab on |
| Your memory facts | Stay on your PC | The newest ones are part of each conversation | The newest ones are part of each conversation |

The approval card always shows where a result will go, for example
*"Result goes to: Claude · claude-opus-5-5 — sent over the internet to the provider's servers"*.
The full table, including voice and web search, is in [Security and privacy](security-and-privacy.md#what-leaves-your-pc).

## Choosing a model

- **Tool use matters most.** Haris acts only through tools. A model without function calling can
  still chat: Haris then says *"This model does not support tools, so I can only talk. Pick a
  tool-capable model (e.g. qwen2.5 or llama3.2) to let me act on the computer."*
- **Vision needs a vision model.** To use *look at my screen* or *look through the camera* with a
  local or Colab engine, pick a vision model (for example `qwen2.5vl:7b`, `gemma3`, `llava`).
  Claude handles pictures.
- **Size vs speed.** Small local models (3B–8B) answer quickly on modest hardware but choose tools
  less reliably. If your PC struggles, Colab's GPU or a cloud model is faster.
- **Arabic.** For replies in Egyptian Arabic, larger models do better. Qwen 2.5 and Claude are
  good starting points.
- **One conversation, one engine.** Switching engines starts a fresh conversation.

## The engine label in the HUD

The **System** drawer shows the current **Engine** (for example `Ollama · qwen2.5:7b`), the time
to the **First token** of the last reply and its **Throughput** in characters per second.
