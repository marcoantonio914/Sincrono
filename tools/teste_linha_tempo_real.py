#!/usr/bin/env python3
"""v25: a linha do tempo (controle + 'compasso N') acompanha a música em tempo real, mesmo depois de um toque cancelado.   python3 tools/teste_linha_tempo_real.py"""
import json, socket, subprocess, sys, tempfile, time, urllib.request
from pathlib import Path
from playwright.sync_api import sync_playwright

RAIZ = Path(__file__).resolve().parent.parent
falhas = []
def check(n, ok, extra=""):
    print(("  ok  " if ok else " FALHA"), n, "" if ok else extra)
    if not ok: falhas.append(n)

s = socket.socket(); s.bind(("127.0.0.1", 0)); P = s.getsockname()[1]; s.close()
pr = subprocess.Popen([sys.executable, str(RAIZ / "servidor-pc/sincrono_server.py"), "--port", str(P), "--cache", tempfile.mkdtemp(), "--no-browser"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
B = "http://127.0.0.1:%d" % P
for _ in range(60):
    try: socket.create_connection(("127.0.0.1", P), 0.2).close(); break
    except OSError: time.sleep(0.1)
try:
    req = urllib.request.Request(B + "/api/score/upload?name=c.musicxml", data=(RAIZ / "docs/exemplos-partitura/completa.musicxml").read_bytes(), method="POST")
    urllib.request.urlopen(req, timeout=5).read()
    with sync_playwright() as p:
        b = p.chromium.launch(); pg = b.new_page(viewport={"width": 390, "height": 800}, has_touch=True)
        pg.goto(B + "/"); pg.wait_for_timeout(500)
        if pg.is_visible("#start-host"): pg.click("#start-host")
        pg.click('#tabs [data-go="sync"]'); pg.wait_for_selector("#score-view svg")
        # simula um toque no controle que é cancelado (rolagem da página), o que travava o acompanhamento
        pg.evaluate("const r=document.getElementById('score-seek'); r.dispatchEvent(new PointerEvent('pointerdown')); r.dispatchEvent(new PointerEvent('pointercancel'))")
        pg.click("#play")
        vals = []
        for _ in range(10):
            pg.wait_for_timeout(1000)
            vals.append(pg.evaluate("[Number(document.getElementById('score-seek').value), document.getElementById('score-seek-label').textContent]"))
        vs = [v for v, _ in vals]
        check("o controle anda enquanto toca", vs[-1] > vs[0] + 3000, vals)
        check("o controle não anda para trás (sem repetição nesse trecho)", all(vs[i] <= vs[i + 1] for i in range(len(vs) - 1)), vs)
        check("o 'compasso N' muda", len({l for _, l in vals}) >= 3, vals)
        b.close()
finally:
    pr.terminate()
print("FALHAS:", falhas if falhas else "nenhuma"); sys.exit(1 if falhas else 0)
