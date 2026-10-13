"""Builds haris_colab.ipynb from haris_proxy.py, so the proxy has a single source.

    python colab/build_notebook.py
"""
import json
from pathlib import Path

HERE = Path(__file__).parent
CLOUDFLARED_VERSION = "2026.9.3"
CLOUDFLARED_SHA256 = "77e26d8d900e0b8469f416239d14b5f296525fdf79fee6f511ef55609e3fbac2"


def md(text: str) -> dict:
    return {"cell_type": "markdown", "metadata": {}, "source": text.strip("\n").splitlines(keepends=True)}


def code(text: str) -> dict:
    return {"cell_type": "code", "metadata": {}, "execution_count": None, "outputs": [],
            "source": text.strip("\n").splitlines(keepends=True)}


proxy_source = (HERE / "haris_proxy.py").read_text(encoding="utf-8")

cells = [
    md("""
# Haris · Ollama + speech on Google Colab

Runs your Haris model **and speech recognition** on Colab's free GPU and connects them to Haris on your PC.

**Every time:** *Runtime → Change runtime type → **T4 GPU***, then *Runtime → Run all*.

**Once, so every session after the first is quick and keeps the same address and token**
(🔑 *Secrets* in the left sidebar; switch on *Notebook access* for each):

| Secret | What it is |
|---|---|
| `NGROK_AUTHTOKEN` | from dashboard.ngrok.com → *Your Authtoken* (free account) |
| `NGROK_DOMAIN` | *(optional)* your free static domain, e.g. `something.ngrok-free.dev` — keeps the address the same |
| `HARIS_TOKEN` | *(optional)* the access token printed by the first run — keeps the token the same |

Without `NGROK_AUTHTOKEN` the notebook falls back to a Cloudflare quick tunnel (new address every session).

**Google Drive** (cell 1): models and the speech model are kept in `MyDrive/haris/`, so a new session
loads them instead of downloading gigabytes again.

**How it is protected**
- Ollama listens only inside this Colab machine (`127.0.0.1`).
- In front of it, a small proxy accepts only requests carrying your 256-bit **access token**, and only the
  endpoints Haris uses (models, chat, speech-to-text) — nobody can pull, delete or copy models even with the address.
- Speech is transcribed in memory and never written to disk. Tokens come from Colab Secrets, not from the notebook text.
- The tunnel software comes from ngrok's signed apt repository, or — for Cloudflare — is pinned to a version and checked
  against its SHA-256 before it runs.

**Privacy:** your conversations and voice are processed on Google's servers while you use this. Colab sessions end
after a while (idle ~90 min, at most ~12 h); just *Run all* again.
"""),
    code("""
#@title 1 · Settings
#@markdown Tool use (opening apps, files, search) needs a tool-capable model; pick a "vl" model to let Haris see your screen.
MODEL = "qwen2.5:7b"  #@param ["qwen2.5:7b", "qwen2.5:14b", "llama3.1:8b", "qwen2.5vl:7b", "gemma3:12b"] {allow-input: true}
#@markdown Speech-to-text on the GPU ("off" keeps it on your PC).
STT_MODEL = "large-v3-turbo"  #@param ["large-v3-turbo", "medium", "small", "off"]
#@markdown Keep models on Google Drive so the next session does not download them again.
USE_DRIVE = True  #@param {type:"boolean"}

import os
def secret(name):
    try:
        from google.colab import userdata
        return userdata.get(name) or None
    except Exception:   # not set, or notebook access not granted
        return None

BASE = "/content"
if USE_DRIVE:
    from google.colab import drive
    drive.mount("/content/drive")
    BASE = "/content/drive/MyDrive/haris"
MODELS_DIR = f"{BASE}/ollama-models"
WHISPER_DIR = f"{BASE}/whisper"
os.makedirs(MODELS_DIR, exist_ok=True)
os.makedirs(WHISPER_DIR, exist_ok=True)
print("Models kept in:", MODELS_DIR)
!nvidia-smi --query-gpu=name,memory.total --format=csv,noheader || echo "No GPU: Runtime > Change runtime type > T4 GPU"
"""),
    code("""
#@title 2 · Install and start Ollama (local to this machine only)
# pciutils + lshw let the Ollama installer find the GPU; without them it runs on the CPU (very slow).
!apt-get -qq update > /dev/null && apt-get -qq install -y zstd pciutils lshw > /dev/null
!curl -fsSL https://ollama.com/install.sh | sh > /dev/null

import subprocess, time, urllib.request
env = {**os.environ, "OLLAMA_HOST": "127.0.0.1:11434", "OLLAMA_KEEP_ALIVE": "-1", "OLLAMA_MODELS": MODELS_DIR}
ollama_proc = subprocess.Popen(["ollama", "serve"], env=env,
                               stdout=open("/content/ollama.log", "w"), stderr=subprocess.STDOUT)
for _ in range(60):
    try:
        urllib.request.urlopen("http://127.0.0.1:11434/api/version", timeout=1)
        break
    except Exception:
        time.sleep(1)
print("Ollama is running on 127.0.0.1:11434 (not reachable from outside).")
"""),
    code("""
#@title 3 · Get the model (from Drive if it is already there) and load it on the GPU
!OLLAMA_HOST=127.0.0.1:11434 OLLAMA_MODELS={MODELS_DIR} ollama pull {MODEL}
import json
req = urllib.request.Request("http://127.0.0.1:11434/api/generate", method="POST",
                             data=json.dumps({"model": MODEL, "prompt": "", "keep_alive": -1}).encode())
urllib.request.urlopen(req, timeout=600).read()
!OLLAMA_HOST=127.0.0.1:11434 ollama ps
"""),
    md("### 4 · The access-token proxy\nThe source below is identical to `colab/haris_proxy.py` in the Haris project."),
    code("%%writefile /content/haris_proxy.py\n" + proxy_source),
    code("""
#@title 5 · Start the proxy (speech-to-text loads in the background)
if STT_MODEL != "off":
    !pip -q install "faster-whisper>=1.1,<2"
import secrets, sys
TOKEN = secret("HARIS_TOKEN")
NEW_TOKEN = not TOKEN
if NEW_TOKEN:
    TOKEN = secrets.token_urlsafe(32)
TUNNEL = "ngrok" if secret("NGROK_AUTHTOKEN") else "cloudflare"   # the same choice step 6 makes
proxy_proc = subprocess.Popen([sys.executable, "/content/haris_proxy.py", "--port", "8000", "--tunnel", TUNNEL],
                              env={**os.environ, "HARIS_PROXY_TOKEN": TOKEN,
                                   "HARIS_STT_MODEL": STT_MODEL, "HARIS_STT_CACHE": WHISPER_DIR},
                              stdout=open("/content/proxy.log", "w"), stderr=subprocess.STDOUT)
time.sleep(3)
req = urllib.request.Request("http://127.0.0.1:8000/v1/models", headers={"Authorization": f"Bearer {TOKEN}"})
print("Proxy check:", urllib.request.urlopen(req, timeout=10).status)
"""),
    code(f"""
#@title 6 · Open the HTTPS tunnel and show what to paste into Haris
import hashlib, re

NGROK_AUTHTOKEN = secret("NGROK_AUTHTOKEN")
NGROK_DOMAIN = secret("NGROK_DOMAIN")
URL = None

if NGROK_AUTHTOKEN:
    # ngrok's own signed apt repository
    !curl -fsSL https://ngrok-agent.s3.amazonaws.com/ngrok.asc -o /etc/apt/trusted.gpg.d/ngrok.asc
    !echo "deb https://ngrok-agent.s3.amazonaws.com bookworm main" > /etc/apt/sources.list.d/ngrok.list
    !apt-get -qq update > /dev/null && apt-get -qq install -y ngrok > /dev/null
    cmd = ["ngrok", "http", "127.0.0.1:8000", "--log", "stdout"]
    if NGROK_DOMAIN:
        cmd += ["--url", "https://" + re.sub(r"^https?://", "", NGROK_DOMAIN).strip("/")]
    tunnel_proc = subprocess.Popen(cmd, env={{**os.environ, "NGROK_AUTHTOKEN": NGROK_AUTHTOKEN}},
                                   stdout=open("/content/tunnel.log", "w"), stderr=subprocess.STDOUT)
    for _ in range(30):
        time.sleep(1)
        try:
            tunnels = json.load(urllib.request.urlopen("http://127.0.0.1:4040/api/tunnels", timeout=2))["tunnels"]
            URL = next((t["public_url"] for t in tunnels if t["public_url"].startswith("https://")), None)
            if URL:
                break
        except Exception:
            pass
    assert URL, "ngrok did not start; see /content/tunnel.log"
else:
    print("No NGROK_AUTHTOKEN secret: using a Cloudflare quick tunnel (the address changes every session).")
    CLOUDFLARED = "/usr/local/bin/cloudflared"
    urllib.request.urlretrieve(
        "https://github.com/cloudflare/cloudflared/releases/download/{CLOUDFLARED_VERSION}/cloudflared-linux-amd64", CLOUDFLARED)
    digest = hashlib.sha256(open(CLOUDFLARED, "rb").read()).hexdigest()
    assert digest == "{CLOUDFLARED_SHA256}", "cloudflared checksum mismatch - not running it"
    os.chmod(CLOUDFLARED, 0o755)
    tunnel_proc = subprocess.Popen([CLOUDFLARED, "tunnel", "--no-autoupdate", "--url", "http://127.0.0.1:8000"],
                                   stdout=open("/content/tunnel.log", "w"), stderr=subprocess.STDOUT)
    for _ in range(60):
        time.sleep(1)
        m = re.search(r"https://[a-z0-9-]+\\.trycloudflare\\.com", open("/content/tunnel.log").read())
        if m:
            URL = m.group(0)
            break
    assert URL, "The tunnel did not start; see /content/tunnel.log"
    time.sleep(4)   # let the tunnel register with Cloudflare's edge

line = "=" * 72
print(line)
print("Haris -> Configure -> Ollama on Google Colab")
print(line)
print("Colab address :", URL)
print("Model         :", MODEL)
if NEW_TOKEN:
    print("Access token  :", TOKEN)
    print()
    print("To keep this token for every future session, save it as the Colab secret HARIS_TOKEN.")
else:
    print("Access token  : (unchanged - your HARIS_TOKEN secret)")
print(line)
print("Keep this tab open while you use Haris. Treat the token like a password.")
"""),
    code("""
#@title 7 · (optional) Status — run any time
for name, proc in [("ollama", ollama_proc), ("proxy", proxy_proc), ("tunnel", tunnel_proc)]:
    print(f"{name:7s}", "running" if proc.poll() is None else f"STOPPED (exit {proc.returncode})")
!OLLAMA_HOST=127.0.0.1:11434 ollama ps
!nvidia-smi --query-gpu=utilization.gpu,memory.used,memory.total --format=csv,noheader
!tail -n 5 /content/proxy.log
"""),
]

notebook = {
    "nbformat": 4,
    "nbformat_minor": 0,
    "metadata": {
        "colab": {"provenance": [], "gpuType": "T4"},
        "kernelspec": {"name": "python3", "display_name": "Python 3"},
        "language_info": {"name": "python"},
        "accelerator": "GPU",
    },
    "cells": cells,
}
(HERE / "haris_colab.ipynb").write_text(json.dumps(notebook, indent=1, ensure_ascii=False), encoding="utf-8")
print("wrote", HERE / "haris_colab.ipynb")
