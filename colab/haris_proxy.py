"""
Token-checking gatekeeper in front of Ollama — and a GPU speech-to-text endpoint — for running
Haris's heavy work on Google Colab.

Ollama has no authentication of its own, and a Colab tunnel address is public: without this
proxy anyone who learned the address could use (or wipe) the models. The proxy:

  * listens on 127.0.0.1 only — the tunnel is its sole way in;
  * requires "Authorization: Bearer <token>" (constant-time comparison, per-client lockout
    keyed on the address the tunnel's edge records, never on headers a client can forge);
  * forwards exactly two Ollama endpoints — GET /v1/models and POST /v1/chat/completions — so
    nothing can pull, delete, copy or create models, or reach Ollama's native /api;
  * streams replies through unchanged (server-sent events), with a request-size cap;
  * transcribes speech on the GPU at POST /haris/v1/transcribe (raw 16 kHz 16-bit mono PCM in,
    {"text": ...} out) with faster-whisper. Audio is processed in memory and never stored.

Run standalone:  HARIS_PROXY_TOKEN=<token> python haris_proxy.py [--port 8000]
                 [--upstream http://127.0.0.1:11434] [--stt-model large-v3-turbo] [--stt-cache DIR]
                 [--tunnel cloudflare|ngrok|none]
"""
import argparse
import asyncio
import os
import secrets
import threading
import time
from collections import OrderedDict

from aiohttp import ClientSession, ClientTimeout, web

OLLAMA_ROUTES = {("GET", "/v1/models"), ("POST", "/v1/chat/completions")}
STT_ROUTE = ("POST", "/haris/v1/transcribe")
MAX_BODY = 24 * 1024 * 1024          # room for a couple of screenshots in base64
MAX_AUDIO = 30 * 16000 * 2           # 30 s of 16 kHz 16-bit mono speech
LOCKOUT_AFTER = 20                   # failed tokens from one client within the window …
LOCKOUT_WINDOW = 60                  # … lock that client (only) out for the window
LANGUAGES = {"ar", "en"}
MAX_CLIENTS = 10_000                 # clients remembered for lockout; the least recent is forgotten first
TUNNELS = ("cloudflare", "ngrok", "none")


class SpeechToText:
    """faster-whisper, loaded once in the background so the first request does not wait for it."""

    def __init__(self, model: str, cache: str | None):
        self.model_name = model
        self.cache = cache
        self.model = None
        self.device = None
        self.error = None
        self.lock = threading.Lock()
        self.ready = threading.Event()
        threading.Thread(target=self._load, daemon=True).start()

    def _load(self):
        try:
            import numpy as np
            from faster_whisper import WhisperModel   # imported here: optional dependency
            silence = np.zeros(16000, dtype=np.float32)
            # A CUDA model can be created even when the CUDA libraries are missing and only fail on
            # first use, so each candidate must transcribe a second of silence before it is kept.
            for device, compute in (("cuda", "float16"), ("cpu", "int8")):
                try:
                    model = WhisperModel(self.model_name, device=device, compute_type=compute,
                                         download_root=self.cache)
                    list(model.transcribe(silence, language="en", beam_size=1)[0])
                    self.model, self.device = model, device
                    print(f"speech-to-text: {self.model_name} on {device}", flush=True)
                    break
                except Exception as e:
                    self.error = f"{device}: {e}"
        except Exception as e:   # no faster-whisper: the endpoint answers 503
            self.error = str(e)
        finally:
            self.ready.set()

    def transcribe(self, pcm: bytes, language: str | None) -> str:
        import numpy as np
        if not self.ready.wait(timeout=600) or self.model is None:
            raise RuntimeError(self.error or "speech model unavailable")
        audio = np.frombuffer(pcm, dtype=np.int16).astype(np.float32) / 32768.0
        with self.lock:
            segments, _info = self.model.transcribe(audio, language=language, beam_size=1, vad_filter=True,
                                                    condition_on_previous_text=False)
            return " ".join(s.text.strip() for s in segments).strip()


def client_of(headers, remote: str | None, tunnel: str) -> str:
    """The real client behind the tunnel, read only from what the tunnel's edge sets itself.

    Cloudflare overwrites CF-Connecting-IP; ngrok appends the client to X-Forwarded-For, so only
    its last entry is trustworthy. Anything else in those headers was written by the client.
    """
    if tunnel == "cloudflare":
        return headers.get("CF-Connecting-IP") or remote or "?"
    if tunnel == "ngrok":
        hops = [hop.strip() for hop in headers.get("X-Forwarded-For", "").split(",") if hop.strip()]
        return hops[-1] if hops else (remote or "?")
    return remote or "?"


class Failures:
    """Recent bad-token times per client. Bounded: when full, the least recent client is forgotten."""

    def __init__(self, limit: int = MAX_CLIENTS):
        self.limit = limit
        self.by_client: OrderedDict[str, list[float]] = OrderedDict()

    def recent(self, client: str, now: float) -> int:
        times = [t for t in self.by_client.get(client, []) if now - t < LOCKOUT_WINDOW]
        if times:
            self.by_client[client] = times
            self.by_client.move_to_end(client)
        else:
            self.by_client.pop(client, None)
        return len(times)

    def add(self, client: str, now: float) -> None:
        self.by_client.setdefault(client, []).append(now)
        self.by_client.move_to_end(client)
        while len(self.by_client) > self.limit:
            self.by_client.popitem(last=False)


def make_app(token: str, upstream: str, stt: SpeechToText | None, tunnel: str = "none") -> web.Application:
    expected = f"Bearer {token}".encode()
    failures = Failures()

    async def handle(request: web.Request) -> web.StreamResponse:
        client = client_of(request.headers, request.remote, tunnel)
        now = time.monotonic()
        if failures.recent(client, now) >= LOCKOUT_AFTER:
            return web.Response(status=429, text="too many bad tokens; wait a minute")

        supplied = request.headers.get("Authorization", "").encode()
        if not secrets.compare_digest(supplied, expected):
            failures.add(client, now)
            return web.Response(status=401, text="unauthorized")

        route = (request.method, request.path)
        if route == STT_ROUTE:
            return await transcribe(request)
        if route not in OLLAMA_ROUTES:
            return web.Response(status=404, text="not available")

        body = await request.read()
        headers = {"Content-Type": request.headers.get("Content-Type", "application/json")}
        timeout = ClientTimeout(total=None, sock_connect=10, sock_read=600)
        async with ClientSession(timeout=timeout) as session:
            async with session.request(request.method, upstream + request.path, data=body or None,
                                       headers=headers) as up:
                response = web.StreamResponse(status=up.status, headers={
                    "Content-Type": up.headers.get("Content-Type", "application/json"),
                    "Cache-Control": "no-store",
                })
                await response.prepare(request)
                async for chunk in up.content.iter_any():
                    await response.write(chunk)
                await response.write_eof()
                return response

    async def transcribe(request: web.Request) -> web.Response:
        if stt is None:
            return web.Response(status=503, text="speech-to-text is not enabled on this proxy")
        pcm = await request.read()
        if len(pcm) < 3200 or len(pcm) > MAX_AUDIO or len(pcm) % 2:
            return web.Response(status=400, text="send 0.1-30 s of 16 kHz 16-bit mono PCM")
        language = request.query.get("language")
        language = language if language in LANGUAGES else None
        try:
            text = await asyncio.wait_for(asyncio.to_thread(stt.transcribe, pcm, language), timeout=120)
        except Exception as e:
            return web.json_response({"error": (str(e) or type(e).__name__)[:200]}, status=503)
        return web.json_response({"text": text}, headers={"Cache-Control": "no-store"})

    app = web.Application(client_max_size=MAX_BODY)
    app.router.add_route("*", "/{tail:.*}", handle)
    return app


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--token", default=os.environ.get("HARIS_PROXY_TOKEN"),
                        help="defaults to $HARIS_PROXY_TOKEN (keeps it out of the process list)")
    parser.add_argument("--port", type=int, default=8000)
    parser.add_argument("--upstream", default="http://127.0.0.1:11434")
    parser.add_argument("--stt-model", default=os.environ.get("HARIS_STT_MODEL", "large-v3-turbo"),
                        help="faster-whisper model; 'off' disables speech-to-text")
    parser.add_argument("--stt-cache", default=os.environ.get("HARIS_STT_CACHE"),
                        help="where to keep the downloaded speech model (e.g. on Google Drive)")
    parser.add_argument("--tunnel", choices=TUNNELS, default=os.environ.get("HARIS_TUNNEL", "none"),
                        help="which tunnel fronts the proxy, so the real client address is read from the "
                             "header that tunnel's edge sets (default: none — use the peer address)")
    args = parser.parse_args()
    if not args.token or len(args.token) < 32:
        raise SystemExit("use a long random token (secrets.token_urlsafe(32))")
    stt = None if args.stt_model == "off" else SpeechToText(args.stt_model, args.stt_cache)
    web.run_app(make_app(args.token, args.upstream, stt, args.tunnel), host="127.0.0.1", port=args.port,
                access_log=None, print=None)


if __name__ == "__main__":
    main()
