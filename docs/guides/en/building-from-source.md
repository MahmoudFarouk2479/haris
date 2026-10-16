# Building from source

**[العربية](../ar/building-from-source.md)** · [All guides](README.md)

For developers who want to run Haris from the code, build the installable zip, run the tests or
contribute.

## Prerequisites

| Tool | Version | Used for |
|---|---|---|
| Windows | 10 or 11 | Haris uses Windows APIs; it is built and tested there. |
| JDK | 21 | The core. `run.ps1` and `package.ps1` use `C:\Program Files\Java\jdk-21` if it exists, otherwise `JAVA_HOME`. `package.ps1` needs `jpackage` from the JDK. |
| Maven | 3.9 or later | Builds and tests the core. |
| Node.js (with npm) | 20 or later | Builds and tests the HUD. |
| Edge or Chrome | — | The HUD window. |
| Docker Desktop | optional | Voice and web search. |
| Python 3 | optional | Tests for the Colab proxy and regenerating the notebook. |

Both scripts download the wake-word model once (`vosk-model-small-en-us-0.15`, about 40 MB) with
`models\fetch-models.ps1`, which checks it against a pinned SHA-256 before using it.

## Run from source

```powershell
git clone https://github.com/MahmoudFarouk2479/haris.git
cd haris
.\run.ps1
```

`run.ps1`:

1. fetches the wake-word model if it is missing;
2. rebuilds when any file in `src`, `ui\src`, `ui\public`, `ui\index.html` or `pom.xml` is newer
   than the jar: `npm ci` (first time), `npm run build` in `ui`, then `mvn -q -B package -DskipTests`;
3. warns if Docker is not installed;
4. starts `java -jar target\haris-core-<version>.jar`.

`.\run.ps1 -Rebuild` forces a full rebuild.

Running from source uses the same data folder as the installed app, `~\.haris` (settings, memory,
keys, audit log). The Docker Compose files are found in the current folder (`voice\`, `search\`),
which is why `run.ps1` runs from the project root. **Start with Windows** is only available in the
installed app.

### Useful properties

The core reads these Spring properties (from `src/main/resources/application.properties`); you can
override them on the command line, for example
`java -jar target\haris-core-0.6.0.jar --haris.open-window=false`:

| Property | Default | Effect |
|---|---|---|
| `haris.open-window` | `true` | `false`: do not open the HUD window; the log prints the address (with the token) to open yourself. |
| `haris.exit-on-window-close` | `true` | `false`: keep the core running after the window closes. |
| `haris.engines.autostart` | `true` | `false`: do not start the Docker voice and search engines. |
| `haris.engines.stop-on-exit` | `true` | `false`: leave the containers running when Haris exits. |
| `haris.voice.stt-port` / `haris.voice.tts-port` | `10300` / `10200` | Whisper and Piper ports. |
| `haris.search.port` | `10500` | SearXNG port. |
| `server.port` | `0` | `0` means the stable random port in `~\.haris\port`; any other value is used as is. The address is always `127.0.0.1`. |

## Build the installable zip

```powershell
.\package.ps1            # with tests
.\package.ps1 -SkipTests # faster
```

`package.ps1`:

1. fetches the wake-word model;
2. runs the HUD tests (`npm test`) and builds the HUD;
3. builds the core with Maven (running the Java tests unless `-SkipTests`);
4. creates a self-contained app with `jpackage` (its own Java runtime, `-Xmx768m`), adding the
   `voice`, `search` and `models` folders next to `Haris.exe`;
5. removes any `.env` file from the output, so local secrets and overrides are never shipped;
6. writes `target\Haris-<version>-windows.zip` containing `Haris\`, `install.ps1`,
   `uninstall.ps1` and `haris_colab.ipynb`.

The app folder alone is in `target\dist\Haris\`.

## Tests

```powershell
cd ui; npm test; cd ..        # HUD: Node's built-in test runner (node --test "src/**/*.test.js")
mvn test                      # core: JUnit 5 and AssertJ
python -m unittest discover -s colab -p "test_*.py" -v   # Colab proxy
```

| Suite | Covers |
|---|---|
| `ui/src/*.test.js` | Translations (both languages have the same keys, every key used exists), drawers and the conversation column, the emblem, the status report and first names, the daily briefing timing, speech batching, bidirectional text, settings diffs, page structure. |
| `src/test/java/...` | The permission gate, audit summaries, the path guard, settings validation, the local access filter, SSRF rules, encrypted memory, image handling, the Wyoming voice protocol, Colab and Gemini speech, stalled streams, GPU routing for Whisper, the wake-word matcher, legacy data migration, and a test that forces every tool to declare whether its output is outside content. |
| `colab/test_haris_proxy.py` | The Colab proxy's client identification and lockout. |

To measure wake-word accuracy on your own recordings, set `HARIS_WAKE_CLIPS` to a folder of 16 kHz
16-bit mono `.pcm` files named `pos*` (should wake) and `neg*` (should not) before `mvn test`.

## Project layout

```
src/main/java/dev/haris/
  HarisApplication.java  entry point; LegacyData moves ~/.jarvis to ~/.haris once
  agent/      AgentService (model loop + permission gate), ConfirmationBroker, AuditLog,
              UndoStack, ConversationService (system prompt), EventHub
  chat/       ChatController (/api/chat, server-sent events)
  llm/        ChatProvider, AnthropicProvider, OpenAiCompatibleProvider, ProviderFactory
  tools/      Tool, Risk, PathGuard, ToolRegistry, impl/ (the tools)
  memory/     MemoryStore (AES-256-GCM), remember / recall / forget
  vision/     screen and camera tools, Images (scale + strip metadata)
  web/        WebSearchTool, FetchPageTool, PageFetcher (pinned DNS), NetGuard (SSRF rules)
  voice/      WakeWordDetector (Vosk), VoiceEngines (Wyoming), GeminiSpeech, ColabSpeech
  security/   LocalAccessFilter, SessionToken, SecretStore (Credential Manager)
  settings/   Settings, SettingsService (validation)
  system/     EnginesService (Docker), WhisperRoute (GPU/CPU), briefing, greeting,
              autostart, weather, system stats
  launcher/   AppWindowLauncher (Edge/Chrome app window), StablePort, HudWindow
ui/           Vite HUD: index.html, src/ (main.js, i18n.js, drawers, emblem, voice …), public/
voice/        compose.yaml (Whisper + Piper), compose.gpu.yaml (GPU Whisper), .env.example
search/       compose.yaml and settings.yml for SearXNG
colab/        haris_proxy.py, build_notebook.py, haris_colab.ipynb, test_haris_proxy.py
models/       fetch-models.ps1 (the model is downloaded, not committed)
packaging/    icon, install.ps1, uninstall.ps1
docs/         media (screenshots, GIFs), guides, design notes
```

## How the HUD gets into the jar

1. `ui/vite.config.js` builds the HUD straight into `src/main/resources/static` (emptied first).
   Assets are never inlined as `data:` URIs and there are no source maps, which keeps the
   Content Security Policy strict.
2. Maven packages that folder into the jar, and Spring Boot serves it from `127.0.0.1`.
3. `LocalAccessFilter` lets only `/`, `/index.html`, `/mic-worklet.js` and `/assets/*` through
   without the session token; everything else needs it.

`src/main/resources/static` is in `.gitignore`. If you run `mvn package` without building the HUD
first, the jar has no HUD: use `run.ps1` or `package.ps1`, or run `npm run build` in `ui` first.

The HUD is plain ES modules with no framework. Its own strings live in `ui/src/i18n.js` (English and
Arabic); model replies are never translated there.

## The Colab notebook

`colab/haris_colab.ipynb` is generated: the proxy has a single source, `colab/haris_proxy.py`.
After changing the proxy or the notebook text in `colab/build_notebook.py`, regenerate it:

```powershell
python colab/build_notebook.py
```

## Contributing

- **Security reports** go through a private advisory, never a public issue: see
  [SECURITY.md](../../../SECURITY.md).
- **Keep the model untrusted.** No tool may run code, a shell command or an arbitrary program. A new
  tool implements `Tool` and decides its `risk`, whether `sessionApprovable`, whether it
  `sendsDataOut`, and whether it `readsOutsideContent`; `ToolTrustDeclarationTest` fails until the
  last decision is recorded.
- **Both languages.** Every HUD string needs an English and an Arabic entry in `ui/src/i18n.js`;
  the tests check that the key sets match.
- **Run all tests** (`npm test`, `mvn test`, and the Colab tests if you touched `colab/`) before
  sending a change.
- **Commit messages** in this repository follow a `type(scope): summary` style, for example
  `fix(ui): …`, `feat(voice): …`, `docs: …`.
- Haris is MIT-licensed ([LICENSE](../../../LICENSE)). Check
  [THIRD_PARTY_NOTICES.md](../../../THIRD_PARTY_NOTICES.md) before adding a dependency.
