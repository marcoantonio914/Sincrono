#!/usr/bin/env python3
"""v27: cada grupo tem a sua cor (igual em todos os aparelhos).   python3 tools/teste_cor_grupo.py"""
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
def api(path, body=None, raw=None):
    d = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
    return json.loads(urllib.request.urlopen(urllib.request.Request(B + path, data=d, method="POST" if d is not None else "GET", headers={"Content-Type": "application/json"}), timeout=5).read() or b"{}")
def rgb(h): return "rgb(%d, %d, %d)" % tuple(int(h[i:i + 2], 16) for i in (1, 3, 5))
try:
    api("/api/score/upload?name=a.xml", raw=(RAIZ / "docs/exemplos-partitura/completa.musicxml").read_bytes())
    for n in ("Soprano", "Contralto"): api("/api/groups", {"op": "add", "name": n})
    with sync_playwright() as p:
        b = p.chromium.launch(); pg = b.new_page(viewport={"width": 390, "height": 900}); erros = []
        pg.on("pageerror", lambda e: erros.append(str(e)))
        pg.goto(B + "/"); pg.wait_for_timeout(500)
        if pg.is_visible("#start-host"): pg.click("#start-host")
        pg.click('#tabs [data-go="music"]') if pg.locator('#tabs [data-go="music"]').count() else None
        pg.wait_for_selector("#groups-list .group", state="attached", timeout=8000)
        cores = pg.evaluate("[...document.querySelectorAll('#groups-list .group')].map(g=>getComputedStyle(g).borderLeftColor)")
        check("cada grupo tem uma cor diferente", len(set(cores)) == len(cores) and len(cores) >= 3, cores)
        check("Geral é cinza e o 2º grupo é azul (como no vídeo)", cores[0] == rgb("#475569") and cores[1] == rgb("#1d4ed8"), cores)
        # coloca esta página no grupo 2 e confere a etiqueta e a moldura da partitura
        dev = pg.evaluate("deviceId")
        api("/api/groups", {"op": "assign", "device": dev, "group": "g2"})
        pg.click('#tabs [data-go="sync"]'); pg.wait_for_function("document.getElementById('score-grp') && document.getElementById('score-grp').textContent.includes('Soprano')", timeout=20000)
        txt = pg.inner_text("#score-grp"); bc = pg.evaluate("getComputedStyle(document.getElementById('score-view')).borderTopColor")
        check("etiqueta com o nome do grupo", "Soprano" in txt, txt)
        check("moldura da partitura na cor do grupo", bc == rgb("#1d4ed8"), bc)
        check("sem erros de JS", not erros, erros)
        b.close()
finally:
    pr.terminate()
print("FALHAS:", falhas if falhas else "nenhuma"); sys.exit(1 if falhas else 0)
