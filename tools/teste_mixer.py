#!/usr/bin/env python3
"""Mixer: um cartão por grupo, mudo e solo, faders finos (servidor do PC de verdade + Chromium).   python3 tools/teste_mixer.py"""
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
    api("/api/groups", {"op": "add", "name": "Piano"})
    gs = api("/api/groups")["groups"]
    g2 = [x["id"] for x in gs if x["name"] == "Piano"][0]
    with sync_playwright() as p:
        b = p.chromium.launch()
        pg = b.new_page(viewport={"width": 390, "height": 900})
        erros = []
        pg.on("pageerror", lambda e: erros.append(str(e)))
        pg.goto(BASE + "/"); pg.wait_for_timeout(800)
        if pg.is_visible("#start-host"): pg.click("#start-host")
        pg.click('#tabs [data-go="mixer"]'); pg.wait_for_timeout(1500)
        check("um cartão por grupo", pg.locator("#devices-list .mx-card").count() == 2, pg.locator("#devices-list .mx-card").count())
        check("o aparelho desta página aparece dentro de um cartão", "esta página" in pg.inner_text("#devices-list"))
        ov = pg.evaluate("document.documentElement.scrollWidth <= window.innerWidth")
        check("sem rolagem horizontal em 390 px", ov)
        card = pg.locator("#devices-list .mx-card").nth(1)
        card.locator("button", has_text="Mudo").click(); pg.wait_for_timeout(900)
        check("Mudo zera o volume do grupo", api("/api/mix")["levels"].get("g:" + g2) == 0, api("/api/mix"))
        check("botão Mudo fica marcado", pg.locator("#devices-list .mx-card").nth(1).locator("button", has_text="Mudo").get_attribute("aria-pressed") == "true")
        pg.locator("#devices-list .mx-card").nth(1).locator("button", has_text="Mudo").click(); pg.wait_for_timeout(900)
        check("desmudar devolve o volume", api("/api/mix")["levels"].get("g:" + g2, 1) == 1)
        pg.locator("#devices-list .mx-card").nth(1).locator("button", has_text="Solo").click(); pg.wait_for_timeout(900)
        lv = api("/api/mix")["levels"]
        check("Solo silencia os outros grupos", lv.get("g:g1") == 0 and lv.get("g:" + g2, 1) == 1, lv)
        pg.locator("#devices-list .mx-card").nth(1).locator("button", has_text="Solo").click(); pg.wait_for_timeout(900)
        check("tirar o Solo devolve os outros", api("/api/mix")["levels"].get("g:g1", 1) == 1)
        th = pg.evaluate("(() => { const e = document.querySelector('#devices-list input.fader'); return !!e && getComputedStyle(e).appearance !== 'auto'; })()")
        check("os faders usam o visual novo", th)
        pg.screenshot(path="/tmp/mixer-web.png")
        check("sem erros de JavaScript", not erros, erros)
        b.close()
finally:
    proc.terminate()

print("\nFALHAS:", falhas) if falhas else print("\nTodos os testes passaram.")
sys.exit(1 if falhas else 0)
