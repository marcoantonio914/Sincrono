#!/usr/bin/env python3
"""Tela acesa: a página pede a trava de tela enquanto toca e solta ao parar (servidor do PC de verdade + Chromium).   python3 tools/teste_tela_acesa.py"""
import io, json, socket, subprocess, sys, tempfile, time, urllib.request, wave
from pathlib import Path
from playwright.sync_api import sync_playwright

RAIZ = Path(__file__).resolve().parent.parent
xml = (RAIZ / "docs/exemplos-partitura/completa.musicxml").read_bytes()
falhas = []


def check(nome, ok, extra=""):
    print(("  ok  " if ok else " FALHA"), nome, "" if ok else extra)
    if not ok:
        falhas.append(nome)


s = socket.socket(); s.bind(("127.0.0.1", 0)); PORT = s.getsockname()[1]; s.close()
proc = subprocess.Popen([sys.executable, str(RAIZ / "servidor-pc/sincrono_server.py"), "--port", str(PORT), "--cache", tempfile.mkdtemp(), "--no-browser"],
                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
BASE = "http://127.0.0.1:%d" % PORT
for _ in range(60):
    try: socket.create_connection(("127.0.0.1", PORT), 0.2).close(); break
    except OSError: time.sleep(0.1)


def api(path, body=None, raw=None, headers=None):
    data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
    h = {"Content-Type": "application/json"}; h.update(headers or {})
    req = urllib.request.Request(BASE + path, data=data, method="POST" if data is not None else "GET", headers=h)
    return json.loads(urllib.request.urlopen(req, timeout=5).read() or b"{}")


def wav(sec=20):
    b = io.BytesIO(); w = wave.open(b, "wb"); w.setnchannels(1); w.setsampwidth(2); w.setframerate(8000); w.writeframes(b"\0\0" * 8000 * sec); w.close(); return b.getvalue()


try:
    with sync_playwright() as p:
        b = p.chromium.launch()
        pg = b.new_page(viewport={"width": 480, "height": 900})
        erros = []
        pg.on("pageerror", lambda e: erros.append(str(e)))
        pg.add_init_script("""
          window.__wl = { req: 0, rel: 0 };
          Object.defineProperty(navigator, 'wakeLock', { configurable: true, value: { request: async () => {
            window.__wl.req++;
            const t = new EventTarget(); t.released = false;
            t.release = async () => { window.__wl.rel++; t.released = true; t.dispatchEvent(new Event('release')); };
            return t; } } });
        """)
        pg.goto(BASE + "/"); pg.wait_for_timeout(800)
        if pg.is_visible("#start-host"): pg.click("#start-host")
        pg.wait_for_timeout(2000)
        check("parado: não pede a trava", pg.evaluate("__wl.req") == 0)
        pg.click('#tabs [data-go="sync"]')
        pg.click("#play"); pg.wait_for_timeout(3500)
        check("tocando: pede a trava de tela", pg.evaluate("__wl.req") >= 1, pg.evaluate("__wl"))
        pg.click("#pause"); pg.wait_for_timeout(3500)
        check("parou: solta a trava", pg.evaluate("__wl.rel") >= 1, pg.evaluate("__wl"))
        check("sem erros de JavaScript", not erros, erros)
        b.close()
finally:
    proc.terminate()

print("\nFALHAS:", falhas) if falhas else print("\nTodos os testes passaram.")
sys.exit(1 if falhas else 0)
