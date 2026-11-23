#!/usr/bin/env python3
"""v29: botões da página para salvar e abrir .sincro.   python3 tools/teste_sincro_pagina.py"""
import json, socket, subprocess, sys, tempfile, time, urllib.request
from pathlib import Path
from playwright.sync_api import sync_playwright
RAIZ = Path(__file__).resolve().parent.parent
falhas = []
def check(n, ok, extra=""):
    print(("  ok  " if ok else " FALHA"), n, "" if ok else extra)
    if not ok: falhas.append(n)
def srv():
    s = socket.socket(); s.bind(("127.0.0.1", 0)); port = s.getsockname()[1]; s.close()
    p = subprocess.Popen([sys.executable, str(RAIZ / "servidor-pc/sincrono_server.py"), "--port", str(port), "--cache", tempfile.mkdtemp(), "--no-browser"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    for _ in range(80):
        try: socket.create_connection(("127.0.0.1", port), 0.2).close(); break
        except OSError: time.sleep(0.1)
    return p, "http://127.0.0.1:%d" % port
def api(B, path, body=None, raw=None):
    d = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
    return json.loads(urllib.request.urlopen(urllib.request.Request(B + path, data=d, method="POST" if d is not None else "GET", headers={"Content-Type": "application/json"}), timeout=10).read() or b"{}")
pa, A = srv(); pb, Bb = srv()
try:
    api(A, "/api/groups", {"op": "add", "name": "Soprano"})
    api(A, "/api/score/upload?name=a.musicxml", raw=(RAIZ / "docs/exemplos-partitura/completa.musicxml").read_bytes())
    api(A, "/api/audio/upload?group=g2&name=sop.flac", raw=b"FLAC" * 1000)
    api(A, "/api/setlist", {"op": "add", "name": "Antífona"}); api(A, "/api/setlist", {"op": "new"})
    api(A, "/api/score/upload?name=b.musicxml", raw=(RAIZ / "docs/exemplos-partitura/completa.musicxml").read_bytes())
    api(A, "/api/setlist", {"op": "add", "name": "Segunda"})
    with sync_playwright() as p:
        b = p.chromium.launch(); erros = []
        pg = b.new_page(viewport={"width": 420, "height": 900}, accept_downloads=True); pg.on("pageerror", lambda e: erros.append(str(e)))
        pg.goto(A + "/"); pg.wait_for_timeout(400)
        if pg.is_visible("#start-host"): pg.click("#start-host")
        pg.click('#tabs [data-go="playlist"]'); pg.wait_for_selector("#rep-list li")
        with pg.expect_download() as d: pg.click("#rep-export")
        dest = Path(tempfile.mkdtemp()) / "x.sincro"; d.value.save_as(str(dest))
        check("botão salva a playlist como .sincro", d.value.suggested_filename.endswith(".sincro") and dest.stat().st_size > 1000, d.value.suggested_filename)
        pg.click("#rep-list li:nth-child(1) [data-a=more]")
        with pg.expect_download() as d1: pg.click("#rep-list li:nth-child(1) [data-a=export]")
        check("menu da música salva só ela", d1.value.suggested_filename.startswith("Ant") and d1.value.suggested_filename.endswith(".sincro"), d1.value.suggested_filename)
        pg2 = b.new_page(viewport={"width": 420, "height": 900}); pg2.on("pageerror", lambda e: erros.append(str(e)))
        pg2.goto(Bb + "/"); pg2.wait_for_timeout(400)
        if pg2.is_visible("#start-host"): pg2.click("#start-host")
        pg2.click('#tabs [data-go="playlist"]')
        pg2.set_input_files("#rep-import", str(dest)); pg2.wait_for_function("document.querySelectorAll('#rep-list li').length === 2", timeout=15000)
        check("botão abre o .sincro e mostra as 2 músicas", "2 músicas" in pg2.inner_text("#rep-msg"), pg2.inner_text("#rep-msg"))
        pg2.set_input_files("#rep-import", str(dest)); pg2.wait_for_function("document.querySelectorAll('#rep-list li').length === 4", timeout=15000)
        check("abrir de novo junta (4 músicas)", True)
        pg2.select_option("#rep-import-mode", "replace"); pg2.on("dialog", lambda dlg: dlg.accept())
        pg2.set_input_files("#rep-import", str(dest)); pg2.wait_for_function("document.querySelectorAll('#rep-list li').length === 2", timeout=15000)
        check("modo substituir deixa só as 2 do arquivo", True)
        pg2.set_input_files("#rep-import", {"name": "ruim.sincro", "mimeType": "application/octet-stream", "buffer": b"nao e zip" * 20})
        pg2.wait_for_function("document.getElementById('rep-msg').textContent.includes('inválido')", timeout=10000)
        check("arquivo ruim mostra aviso e não muda a lista", pg2.evaluate("document.querySelectorAll('#rep-list li').length") == 2)
        check("sem erros de JS", not erros, erros)
        b.close()
finally:
    pa.terminate(); pb.terminate()
print("FALHAS:", falhas if falhas else "nenhuma"); sys.exit(1 if falhas else 0)
