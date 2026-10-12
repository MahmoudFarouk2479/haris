# Third-party software

Haris is MIT-licensed (see `LICENSE`). It is built on the projects below, each under its own
license. Check this list again before using Haris in a commercial product.

## Shipped inside the app (`package.ps1` output)

| Component | Used for | License |
|---|---|---|
| Java runtime (OpenJDK, bundled by jpackage) | runs the app | GPL-2.0 with Classpath Exception |
| Spring Boot / Spring Framework | web core | Apache-2.0 |
| Jackson | JSON | Apache-2.0 |
| anthropic-java | Claude API | MIT |
| OkHttp | web fetching | Apache-2.0 |
| jsoup | page-to-text | MIT |
| OSHI | system status | MIT |
| JNA | Windows API access | Apache-2.0 or LGPL-2.1 (dual-licensed; used under Apache-2.0) |
| java-keyring | Windows Credential Manager | BSD-3-Clause |
| Vosk (`com.alphacephei:vosk`) | wake word | Apache-2.0 |
| `vosk-model-small-en-us-0.15` | wake-word model | Apache-2.0 ([source](https://alphacephei.com/vosk/models)) |
| three.js | HUD graphics | MIT |
| Orbitron, Rajdhani (via @fontsource) | HUD fonts | SIL Open Font License 1.1 |

## Downloaded and run separately, on your machine or your Colab session

These are not part of the Haris download. Docker, Colab or pip fetches them from their publishers
the first time they are used.

| Component | Used for | License |
|---|---|---|
| `rhasspy/wyoming-whisper`, faster-whisper, Whisper models | speech-to-text | MIT |
| `rhasspy/wyoming-piper`, Piper | text-to-speech | MIT (engine) |
| Piper voices (`en_GB-alan-medium`, `ar_JO-kareem-medium`, …) | voices | **per voice** — see each voice's `MODEL_CARD` before commercial use |
| SearXNG (`searxng/searxng`) | private web search | AGPL-3.0 (run unmodified as a separate service) |
| Ollama | local / Colab models | MIT |
| Model weights you choose (Qwen, Llama, Gemma, …) | the assistant's brain | each model's own license |
| cloudflared | Colab tunnel (optional) | Apache-2.0 |
| ngrok agent | Colab tunnel (optional) | proprietary, ngrok terms of service |

## Not used

The earlier wake-word engine, openWakeWord, was replaced because its pre-trained models (including
"hey jarvis") are licensed CC BY-NC-SA 4.0, which does not allow commercial use.

## Trademarks

"Claude" is a trademark of Anthropic, "Google Colab" of Google, "ngrok" of ngrok Inc. Haris is an
independent project, not affiliated with or endorsed by any of them.
