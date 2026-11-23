#!/usr/bin/env python3
"""Só partitura (sem áudio): o Tocar da playlist, com as caixas como estão por padrão, tem de tocar o metrônomo e andar o cursor (servidor do PC de verdade + Chromium).   python3 tools/teste_so_partitura.py"""
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
        pg.goto(BASE + "/"); pg.wait_for_timeout(500)
        if pg.is_visible("#start-host"): pg.click("#start-host")
        pg.set_input_files("#score-file", files=[{"name": "completa.musicxml", "mimeType": "application/xml", "buffer": xml}])
        pg.wait_for_selector("#score-view svg", timeout=6000)
        pg.click('#tabs [data-go="playlist"]')
        pg.wait_for_timeout(1500)
        check("padrão: Áudio marcado e Metrônomo desmarcado", pg.is_checked("#with-audio") and not pg.is_checked("#with-metro"))
        pg.click("#play-all"); pg.wait_for_timeout(700)
        check("só partitura: o Tocar liga o metrônomo", pg.evaluate("state.playing"))
        pg.click('#tabs [data-go="sync"]'); pg.wait_for_timeout(300)
        x0 = pg.evaluate("parseFloat(document.querySelector('#score-view .ln').getAttribute('x1'))")
        pg.wait_for_timeout(5600)
        x1 = pg.evaluate("parseFloat(document.querySelector('#score-view .ln').getAttribute('x1'))")
        check("só partitura: o cursor anda", x1 > x0 + 20, (x0, x1))
        pg.click('#tabs [data-go="playlist"]')
        pg.click("#stop-all"); pg.wait_for_timeout(2300)
        check("parar deixa tudo parado", not pg.evaluate("state.playing"))
        # com áudio carregado, Áudio sozinho continua só áudio
        api("/api/audio/upload?group=g1&name=m.wav", raw=wav(), headers={"X-File-Type": "audio/wav"})
        pg.wait_for_timeout(2500)
        pg.click("#play-all"); pg.wait_for_timeout(700)
        check("com áudio: toca o áudio e não o metrônomo", api("/api/audio?group=g1").get("playing") and not pg.evaluate("state.playing"))
        pg.click("#stop-all"); pg.wait_for_timeout(2300)
        check("sem erros de JavaScript", not erros, erros)
        b.close()
finally:
    proc.terminate()

print("\nFALHAS:", falhas) if falhas else print("\nTodos os testes passaram.")
sys.exit(1 if falhas else 0)
