# Settings

**[العربية](../ar/settings.md)** · [All guides](README.md)

Open **Settings** with the gear icon at the bottom of the rail (Arabic: **الإعدادات**). This page
lists every field, in the order the dialog shows them.

## How saving works

- **Save** stores the settings in `~\.haris\settings.json` (plain JSON, with no keys in it) and
  closes the dialog. **Save & detect models** saves without closing and then lists the engine's
  models.
- Keys (API keys, the Colab token, the Google key) go to **Windows Credential Manager**. The
  window can write them but never read them back; it only shows whether one is stored. Leave a key
  field empty to keep the saved key.
- Saving changes the rules the model works under, so Haris starts a **fresh conversation**
  (*"Settings saved. A fresh conversation has started with the new rules."*). If you changed only
  the **Interface language**, or nothing, the conversation continues
  (*"Settings saved. The conversation continues."*). Entering a new key always starts fresh.
- Anything that decides where your data may go or which files may be touched is checked by the
  core when you save. A refused value shows a red message at the bottom of the dialog and nothing is
  saved (examples in [Troubleshooting](troubleshooting.md#settings-will-not-save)).

## Identity

| Field (English / Arabic) | Setting | Default | Valid values | What it does |
|---|---|---|---|---|
| **Assistant name** / **اسم المساعد** | `assistantName` | `Haris` | 1–40 characters, required | The name in the top bar, on Haris's messages and in the model's instructions. The default shows as **H.A.R.I.S.** (Arabic **حارس**); a one-word Latin name is shown with dots (e.g. `Nova` → **N.O.V.A.**). The wake word stays "Hey Haris". |
| **Your name** / **اسمك** | `userName` | empty | up to 40 characters | Tells the model your name. The greeting uses your first name. |
| **Home city (for weather)** / **مدينتك (للطقس)** | `homeCity` | empty | up to 60 characters, e.g. `Cairo` | The default city for the weather tool and the weather line in the daily status report. |
| **Interface language** / **لغة الواجهة** | `uiLanguage` | English (`en`) | `en`, `ar` | The HUD's language. Arabic switches the layout to right-to-left. It does not change the reply language. |
| **Daily briefing on the first launch of the day** / **ملخّص يومي عند أول تشغيل في اليوم** | `morningBriefing` | on | on / off | Once a day, asks the model for a short briefing. See [Using Haris](using-haris.md#daily-status-report-and-briefing). |
| **Start Haris when I sign in to Windows** / **شغّل حارس عند تسجيل الدخول إلى ويندوز** | `startWithWindows` | off | on / off | Adds one value named `Haris` under `HKCU\Software\Microsoft\Windows\CurrentVersion\Run`; unticking removes it. Only in the installed app: when running from source the box is disabled and the hint says so. |

## Access

| Field | Setting | Default | Valid values | What it does |
|---|---|---|---|---|
| **Allowed folders — one per line** / **المجلدات المسموحة — مجلد في كل سطر** | `allowedFolders` | Your `Desktop`, `Documents` and `Downloads` folders (those that exist) | Up to 10 existing folders, full paths | The only folders file tools can see. |

A folder is refused if it:

- does not exist or is not a full path;
- is a whole drive (`D:\`);
- is your whole home folder (`C:\Users\you`). Pick folders inside it instead;
- is inside, or contains, Windows, Program Files, ProgramData, AppData, `~\.ssh` or `~\.haris`.

If you remove every folder, the file tools are unavailable until you add one.

## Voice

| Field | Setting | Default | Valid values | What it does |
|---|---|---|---|---|
| **Speak replies aloud** / **انطق الردود بصوت** | `voiceReplies` | on | on / off | Speaks replies, the greeting and the status report. |
| **Listen for "Hey Haris"** / **استمع لـ "Hey Haris"** | `voiceWake` | off | on / off | Keeps the microphone open for the wake word; the audio goes only to the local wake-word engine. |
| **Conversation mode** / **وضع المحادثة** | `voiceFollowUp` | on | on / off | After a spoken answer to a spoken request, listens 5 s for a follow-up without the wake word. |
| **I speak** / **أتحدث** | `sttLanguage` | **Detect automatically** (`auto`) | `auto`, `ar` (**Arabic (best for Egyptian Arabic)**), `en` (**English**) | The language speech-to-text expects. |
| **Haris replies in** / **حارس يرد بـ** | `replyLanguage` | **The language I use** (`auto`) | `auto`, `ar` (**Egyptian Arabic, always**), `en` (**English, always**) | The language the model is told to answer in. |
| **Voice engine** / **محرك الصوت** | `ttsEngine` | **Piper — on this PC (private)** (`piper`) | `piper`, `gemini` | Who speaks the replies. Gemini sends the reply text to Google. |
| **Gemini voice** / **صوت Gemini** | `geminiVoice` | `Charon` | One of Gemini's 30 prebuilt voices listed in the menu | The Gemini voice. |
| **Google AI Studio key** / **مفتاح Google AI Studio** | key `gemini` | — | up to 512 characters | Needed for Gemini. Without it, Piper speaks. |
| **Piper English voice** / **صوت Piper الإنجليزي** | `voiceEnglish` | `en_GB-alan-medium` | `xx_YY-name-quality`, quality `x_low`, `low`, `medium` or `high` | Voice for sentences without Arabic letters. |
| **Piper Arabic voice** / **صوت Piper العربي** | `voiceArabic` | `ar_JO-kareem-medium` | same format | Voice for sentences with Arabic letters. |
| **▶ Test speaker** / **▶ اختبار السماعة** | — | — | — | Plays an English and an Arabic test sentence through the current voice engine. |

More in [Voice](voice.md).

## Engine

**Provider** / **المزوّد** (`provider`, default **Ollama (local)**) decides which fields appear:

| Provider (English / Arabic) | Value |
|---|---|
| **Ollama (local)** / **Ollama (محلي)** | `OLLAMA` |
| **LM Studio (local)** / **LM Studio (محلي)** | `LM_STUDIO` |
| **Ollama on Google Colab (free GPU)** / **Ollama على Google Colab (كارت مجاني)** | `COLAB` |
| **Claude (Anthropic API)** / **Claude (واجهة Anthropic)** | `ANTHROPIC` |
| **OpenAI-compatible cloud** / **سحابة متوافقة مع OpenAI** | `OPENAI_COMPATIBLE` |

### Ollama (local)

| Field | Setting | Default | Valid values |
|---|---|---|---|
| **Ollama URL** / **عنوان Ollama** | `ollamaBaseUrl` | `http://127.0.0.1:11434` | `http(s)://` on `127.0.0.1`, `localhost` or another loopback address; required |
| **Model** / **النموذج** | `ollamaModel` | empty | up to 120 characters |

### LM Studio (local)

| Field | Setting | Default | Valid values |
|---|---|---|---|
| **LM Studio URL** / **عنوان LM Studio** | `lmStudioBaseUrl` | `http://127.0.0.1:1234` | loopback only, as for Ollama; required |
| **Model** | `lmStudioModel` | empty | up to 120 characters |

### Ollama on Google Colab (free GPU)

| Field | Setting | Default | Valid values |
|---|---|---|---|
| **Colab address** / **عنوان Colab** | `colabBaseUrl` | empty | `https://` only, not a local address; saved as `https://host[:port]` |
| **Model** | `colabModel` | empty | up to 120 characters |
| **Access token** / **رمز الوصول** | key `colab` | — | the token the notebook prints |
| **Also recognise speech on Colab's GPU** | `colabStt` | on | on / off; works only with a saved token |

The hint under the token shows **● Token stored in vault — protected by the notebook proxy**, or a
warning when no token is saved. See [Engines](engines.md#ollama-on-google-colab).

### Claude (Anthropic API)

| Field | Setting | Default | Valid values |
|---|---|---|---|
| **Model** | `anthropicModel` | `claude-opus-5-5` | up to 80 characters, required; suggestions `claude-opus-5-5`, `claude-sonnet-5-5`, `claude-haiku-5-5`, `claude-fable-5-1` |
| **Effort** / **مستوى التفكير** | `anthropicEffort` | **Medium** (`medium`) | `low` (**Low — fastest**), `medium`, `high`, `xhigh` (**Extra high**), `max` |
| **API key** / **مفتاح API** | key `anthropic` | — | `sk-ant-…` |
| **Allow Claude to search the web** | `webSearch` | off | on / off |

### OpenAI-compatible cloud

| Field | Setting | Default | Valid values |
|---|---|---|---|
| **Base URL** / **العنوان الأساسي** | `openAiBaseUrl` | `https://api.openai.com` | `https://`, or a loopback `http://` address; up to 200 characters |
| **Model** | `openAiModel` | empty | up to 120 characters |
| **API key** | key `openai-compatible` | — | required to chat |

### Save & detect models

**Save & detect models** / **حفظ واكتشاف النماذج** saves, then asks the engine for its models and
offers them as suggestions in **Model**. For Claude it offers the four model names above. If the
engine does not answer you see *"No models found. Is the engine running?"* or the engine's error.

## Where the settings live

| What | Where |
|---|---|
| All settings above except keys | `~\.haris\settings.json` |
| Anthropic key, OpenAI-compatible key, Colab token, Google key | Windows Credential Manager, entries named `Haris\|anthropic`, `Haris\|openai-compatible`, `Haris\|colab`, `Haris\|gemini` |
| Pinned drawer, hidden conversation column | The HUD's own browser profile (`~\.haris\hud-profile`) |
| The app log (not a setting) | `%USERPROFILE%\.haris\logs\haris.log`; the bottom of the Settings dialog shows this path |

The window has no button to delete a stored key; you can replace it, or remove its entry in
Windows Credential Manager (**Control Panel → Credential Manager → Windows Credentials**). Do not
remove `Haris|memory-key`: without it your saved memory can no longer be decrypted.

To go back to the defaults, see [Troubleshooting](troubleshooting.md#reset-settings).
