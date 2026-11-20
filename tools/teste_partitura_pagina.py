#!/usr/bin/env python3
"""Teste da página com o servidor do PC de verdade + Chromium: importar partitura, parte por grupo, cursor pelo metrônomo e pelo áudio.
   python3 tools/teste_partitura_pagina.py"""
import json, os, socket, subprocess, sys, tempfile, time, urllib.request
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


def api(path, body=None, raw=None):
    data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
    req = urllib.request.Request(BASE + path, data=data, method="POST" if data is not None else "GET", headers={"Content-Type": "application/json"})
    return json.loads(urllib.request.urlopen(req, timeout=5).read() or b"{}")


try:
    with sync_playwright() as p:
        b = p.chromium.launch()
        pg = b.new_page(viewport={"width": 480, "height": 900})
        erros = []
        pg.on("pageerror", lambda e: erros.append(str(e)))
        pg.goto(BASE + "/")
        pg.wait_for_timeout(500)
        if pg.is_visible("#start-host"):
            pg.click("#start-host")
        check("sem partitura, o painel não aparece", not pg.is_visible("#score"))
        pg.click('#tabs [data-go="audio"]')
        check("importar fica no mesmo painel dos grupos (aba MÚSICA)", pg.is_visible("#score-admin") and pg.is_visible("#groups-list") and pg.inner_text('#tabs [data-go="audio"]') == "MÚSICA")
        pg.click('#tabs [data-go="sync"]')
        pg.set_input_files("#score-file", files=[{"name": "completa.musicxml", "mimeType": "application/xml", "buffer": xml}])
        pg.wait_for_selector("#score-view svg", timeout=6000)
        check("partitura enviada e desenhada", pg.locator("#score-view svg").count() == 1)
        opts = pg.eval_on_selector_all("#score-part option", "o => o.map(x => x.textContent)")
        check("lista as 3 partes + parte do grupo", len(opts) == 4 and "Violoncelo" in opts and opts[0].startswith("Parte do meu grupo (Soprano"), opts)
        check("a lista separada de grupos na seção da partitura não existe mais", pg.locator("#score-groups").count() == 0)
        check("o nome da partitura aparece no painel de grupos", "completa.musicxml" in pg.inner_text("#score-name"))
        # parte do grupo: escolhida DENTRO do cartão do grupo (aba ÁUDIO), como o áudio
        pg.click('#tabs [data-go="audio"]')
        pg.wait_for_selector("#groups-list select[aria-label^='Parte da partitura']", timeout=6000)
        check("cartão do grupo tem o seletor 'Parte da partitura'", pg.locator("#groups-list select[aria-label^='Parte da partitura']").count() == 1)
        # cliente já aberto no grupo Geral
        cl = b.new_page(viewport={"width": 420, "height": 800})
        cl.on("pageerror", lambda e: erros.append(str(e)))
        cl.goto(BASE + "/?guest=1")
        cl.wait_for_selector("#score-view svg", timeout=6000)
        t0 = time.time()
        pg.select_option("#groups-list select[aria-label^='Parte da partitura']", "1")
        cl.wait_for_function("() => { const t = document.querySelector('#score-view svg').textContent; return t.includes('mp') && !t.includes('La'); }", timeout=4000)
        dt = time.time() - t0
        txt = cl.eval_on_selector("#score-view svg", "s => s.textContent")
        check("ao escolher a parte no cartão do grupo, o cliente passa a ver o violoncelo em até 2,5 s (levou %.1f s)" % dt, dt < 2.5 and "mp" in txt and "La" not in txt, dt)
        pg.click('#tabs [data-go="sync"]')
        pg.wait_for_timeout(300)
        # cursor pelo metrônomo
        api("/api/command", {"type": "play", "bpm": 100, "beatsPerBar": 4})
        pg.wait_for_timeout(600)
        x0 = pg.evaluate("parseFloat(document.querySelector('#score-view .ln').getAttribute('x1'))")
        pg.wait_for_timeout(5200)    # 2,5 s de contagem + ~3 s tocando = passa para o compasso 2
        x1 = pg.evaluate("parseFloat(document.querySelector('#score-view .ln').getAttribute('x1'))")
        shown = pg.evaluate("document.querySelector('#score-view .cur').style.display !== 'none'")
        check("cursor aparece e anda com o metrônomo", shown and x1 > x0 + 20, (x0, x1))
        api("/api/command", {"type": "pause"})
        pg.wait_for_timeout(500)
        # cursor pelo áudio: posição 3000 ms = compasso 2 (a 100 bpm)
        r = pg.evaluate("""() => { songState = { file: { id: 1 }, playing: true, startAt: serverNow() - 7300, positionMs: 0, stopAt: null };
            return new Promise(res => setTimeout(() => {
              const ln = parseFloat(document.querySelector('#score-view .ln').getAttribute('x1')); res({ ln, ms: songPositionMs() }); }, 150)); }""")
        # 7300 ms = depois dos compassos 1-2-3 (7200 ms): a repetição volta ao compasso 1, à esquerda do compasso 2
        check("áudio tocando manda no cursor e respeita a repetição (volta ao compasso 1)", r["ln"] < x1 - 20, (r, x1))
        # modo páginas, zoom
        pg.click("#score-mode"); pg.wait_for_timeout(300)
        check("modo páginas quebra em linhas", pg.evaluate("document.querySelector('#score-view').classList.contains('pages')"))
        pg.click("#score-zoom-in"); pg.click("#score-zoom-out")
        # cliente (já aberto acima)
        check("cliente vê a partitura, sem os controles de importar", cl.is_visible("#score-view") and not cl.is_visible("#score-file"))
        cl.select_option("#score-part", "2"); cl.wait_for_timeout(300)
        ptxt = cl.eval_on_selector("#score-view svg", "s => s.textContent")
        check("cliente troca para a própria parte (piano: sem 'La' e sem 'mp')", "La" not in ptxt and "mp" not in ptxt and "Andante" in ptxt, ptxt[:100])
        # remover
        pg.once("dialog", lambda d: d.accept())
        pg.click('#tabs [data-go="audio"]')
        pg.click("#score-remove"); pg.wait_for_timeout(2500)
        check("remover tira a partitura do anfitrião e do cliente", api("/api/score")["file"] is None and cl.evaluate("document.querySelector('#score').hidden"))
        check("sem erros de JavaScript", not erros, erros)
        pg.screenshot(path="/tmp/pagina-partitura.png")
        b.close()
finally:
    proc.terminate()
print("\n" + ("TUDO CERTO" if not falhas else "%d FALHA(S): %s" % (len(falhas), falhas)))
sys.exit(1 if falhas else 0)
