#!/usr/bin/env python3
"""v36: palco ocupa a janela, 'Página inteira' mostra todas as linhas, etiqueta do grupo some no palco, partitura na aba PLAYLIST.   python3 tools/teste_palco_inteiro.py"""
import json, socket, subprocess, sys, tempfile, time, urllib.request
from pathlib import Path
from playwright.sync_api import sync_playwright

RAIZ = Path(__file__).resolve().parent.parent
xml = (RAIZ / "docs/exemplos-partitura/completa.musicxml").read_bytes()
falhas = []
def check(nome, ok, extra=""):
    print(("  ok  " if ok else " FALHA"), nome, "" if ok else extra)
    if not ok: falhas.append(nome)

s = socket.socket(); s.bind(("127.0.0.1", 0)); PORT = s.getsockname()[1]; s.close()
proc = subprocess.Popen([sys.executable, str(RAIZ / "servidor-pc/sincrono_server.py"), "--port", str(PORT), "--cache", tempfile.mkdtemp(), "--no-browser"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
BASE = "http://127.0.0.1:%d" % PORT
for _ in range(60):
    try: socket.create_connection(("127.0.0.1", PORT), 0.2).close(); break
    except OSError: time.sleep(0.1)

CUR = "parseFloat(document.querySelector('#score-view .ln').getAttribute('x1'))"
try:
    with sync_playwright() as p:
        b = p.chromium.launch(); erros = []
        for nome, vp in (("vertical", {"width": 390, "height": 800}), ("horizontal", {"width": 800, "height": 390})):
            pg = b.new_page(viewport=vp); pg.on("pageerror", lambda e: erros.append(str(e)))
            pg.goto(BASE + "/"); pg.wait_for_timeout(500)
            if pg.is_visible("#start-host"): pg.click("#start-host")
            pg.set_input_files("#score-file", files=[{"name": "completa.musicxml", "mimeType": "application/xml", "buffer": xml}])
            pg.wait_for_selector("#score-view svg", timeout=8000)
            pg.evaluate("window.sincronoStage(true)"); pg.wait_for_timeout(500)
            h = pg.evaluate("document.getElementById('score-view').getBoundingClientRect().height")
            check(nome + ": palco ocupa a janela (altura %d de %d)" % (h, vp["height"]), h >= vp["height"] - 20, h)
            chip = pg.evaluate("getComputedStyle(document.getElementById('score-grp')).display")
            check(nome + ": etiqueta do grupo some no palco", chip == "none", chip)
            for _ in range(5):
                if pg.evaluate("window.__scoreView.mode") == "fit": break
                pg.click("#score-mode"); pg.wait_for_timeout(250)
            pg.wait_for_timeout(300)
            r = pg.evaluate("(() => { const v = window.__scoreView, S = v.systems, bx = document.getElementById('score-view'); return { n: S.length, need: S.length * (S[0].bottom - S[0].top) * v.zEff, box: bx.clientHeight, z: v.zEff }; })()")
            check(nome + ": 'Página inteira' cabe tudo (%d linhas, %d de %d px, zoom %.2f)" % (r["n"], r["need"], r["box"], r["z"]), r["need"] <= r["box"] + 2, r)
            pg.evaluate("window.sincronoStage(false)"); pg.wait_for_timeout(300)
            h2 = pg.evaluate("document.getElementById('score-view').style.height")
            check(nome + ": ao sair do palco a altura volta ao normal", h2 == "", h2)
            pg.close()
        # partitura na aba PLAYLIST
        pg = b.new_page(viewport={"width": 480, "height": 900}); pg.on("pageerror", lambda e: erros.append(str(e)))
        pg.goto(BASE + "/"); pg.wait_for_timeout(500)
        if pg.is_visible("#start-host"): pg.click("#start-host")
        pg.set_input_files("#score-file", files=[{"name": "completa.musicxml", "mimeType": "application/xml", "buffer": xml}])
        pg.wait_for_selector("#score-view svg", timeout=8000)
        pg.click('#tabs [data-go="playlist"]'); pg.wait_for_timeout(900)
        check("PLAYLIST: a partitura aparece na própria aba", pg.is_visible("#setlist #score-view svg"))
        pg.click("#play-all"); pg.wait_for_timeout(700)
        x0 = pg.evaluate(CUR); pg.wait_for_timeout(5600); x1 = pg.evaluate(CUR)
        check("PLAYLIST: o cursor anda sem sair da aba", x1 > x0 + 20, (x0, x1))
        pg.click("#stop-all"); pg.wait_for_timeout(2300)
        pg.click('#tabs [data-go="sync"]'); pg.wait_for_timeout(800)
        check("SYNC: a partitura volta para a aba SYNC", pg.is_visible('.tab[data-tab="sync"] #score-view svg') and not pg.is_visible("#setlist"))
        pg.click('#tabs [data-go="playlist"]'); pg.wait_for_timeout(500)
        check("PLAYLIST de novo: partitura segue visível", pg.is_visible("#setlist #score-view svg"))
        check("sem erros de script", not erros, erros)
        b.close()
finally:
    proc.terminate()
print("\n%s" % ("TUDO OK" if not falhas else "FALHAS: %s" % falhas)); sys.exit(1 if falhas else 0)
