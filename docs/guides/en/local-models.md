# Fully local in five minutes (Ollama)

**[العربية](../ar/local-models.md)** · [All guides](README.md)

With a local model, your conversation never leaves the PC. The only things that do are web searches
through your own SearXNG, the address of a page you ask Haris to read, and the home-city name sent
to Open-Meteo for weather.

## 1. Install Ollama and pull a model

Install [Ollama](https://ollama.com), then pull one model that supports **tools**. Haris needs tool
calling to act on your PC; without it, it can only talk.

| Your PC | Pull this | Download size | Notes |
|---|---|---|---|
| 8 GB RAM | `ollama pull qwen3:4b` | about 2.5 GB | The safest fit. |
| 8 GB RAM | `ollama pull qwen3.5:4b` | about 3.3–4 GB | A newer generation with wider language coverage. |
| 16 GB RAM, small GPU | `ollama pull qwen3.5:9b` or `ollama pull qwen3:8b` | about 5–7.6 GB | The best general choice. |
| 16 GB RAM, small GPU | `ollama pull gemma4:e4b` | about 6.6–9.5 GB | Can also look at screenshots. |

Sizes are from the Ollama library pages (October 2026), and they change. Allow 1–2 GB of RAM on top
of the file size for the conversation.

## 2. Connect Haris

1. **Settings → Engine → Provider → Ollama (local)**. The URL is already `http://127.0.0.1:11434`.
2. Click **Save & detect models**, pick the model, click **Save**.
3. Ask: *"What's my system status?"* The System drawer shows the engine as `Ollama · <model>`.

If Haris answers *"This model does not support tools, so I can only talk"*, pick a model from the
table above.

## Arabic with a local model

The models above are general models with Arabic among many languages. Try them with your own
Arabic, or Egyptian, prompts before you rely on them.

| Model | Pull | Tools | License | Note |
|---|---|---|---|---|
| Command R7B Arabic | `ollama pull command-r7b-arabic` | yes | **non-commercial** (CC-BY-NC) | Tuned for Modern Standard Arabic. About 5.1 GB. |
| Aya Expanse 8B | `ollama pull aya-expanse:8b` | yes | **non-commercial** (CC-BY-NC) | Arabic is one of its 23 languages. About 5.1 GB. |

Arab-built models such as **Jais 2**, **Fanar**, **ALLaM** and **Nile-Chat**, which is built for
Egyptian Arabic, have community GGUF builds you can open in LM Studio or with
`ollama run hf.co/<repo>`. None of them documents tool calling in Ollama as of October 2026, so they
may only chat in Haris. **Falcon-H1 Arabic** had no public download at the time of writing.

## LM Studio instead

Load any model in LM Studio, start its local server, and choose **LM Studio (local)** in Settings.
See [Engines](engines.md#lm-studio-local).
