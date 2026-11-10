#!/usr/bin/env python3
"""Tiny authenticated deploy hook. GitHub Actions POSTs here after a green build."""

import hmac
import json
import os
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

TOKEN = os.environ.get("DEPLOY_TOKEN", "")
PORT = int(os.environ.get("DEPLOY_PORT", "39001"))
BIND = os.environ.get("DEPLOY_BIND", "0.0.0.0")
SCRIPT = os.environ.get("DEPLOY_SCRIPT", "/opt/guab-server/deploy.sh")
PAUSE_FILE = os.environ.get("DEPLOY_PAUSE_FILE", "/opt/guab-server/.deploy-paused")
COOLDOWN = int(os.environ.get("DEPLOY_COOLDOWN", "30"))
TIMEOUT = int(os.environ.get("DEPLOY_TIMEOUT", "600"))

last_run = 0.0
lock = threading.Lock()


def paused() -> bool:
    try:
        with open(PAUSE_FILE, "r", encoding="utf-8") as handle:
            reason = handle.read().strip()
    except FileNotFoundError:
        return False
    except OSError:
        # If the guard exists but cannot be read, fail closed.
        return True
    print(f"Deploy skipped: paused ({reason or PAUSE_FILE})", flush=True)
    return True


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        global last_run

        if self.path != "/deploy":
            return self.reply(404, {"error": "not found"})

        if not TOKEN or not hmac.compare_digest(
            self.headers.get("Authorization", ""), f"Bearer {TOKEN}"
        ):
            return self.reply(401, {"error": "unauthorized"})

        if paused():
            return self.reply(503, {"ok": False, "error": "deploy paused"})

        with lock:
            now = time.time()
            if now - last_run < COOLDOWN:
                return self.reply(429, {"error": "deploy already ran recently"})
            last_run = now

        if paused():
            return self.reply(503, {"ok": False, "error": "deploy paused"})

        try:
            result = subprocess.run(
                [SCRIPT], capture_output=True, text=True, timeout=TIMEOUT
            )
        except subprocess.TimeoutExpired:
            return self.reply(504, {"ok": False, "error": "deploy timed out"})

        output = (result.stdout + result.stderr)[-2000:]
        self.reply(200 if result.returncode == 0 else 500, {"ok": result.returncode == 0, "output": output})

    def reply(self, code, payload):
        body = json.dumps(payload).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt, *args):
        print(f"{self.address_string()} {fmt % args}", flush=True)


ThreadingHTTPServer((BIND, PORT), Handler).serve_forever()
