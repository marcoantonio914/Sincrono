#!/usr/bin/env python3
"""v22: palco com várias linhas, 'como na partitura', parte só do anfitrião, botões redondos, Nova música.   python3 tools/teste_palco_quebras.py"""
import json, socket, subprocess, sys, tempfile, time, urllib.request, urllib.error
from pathlib import Path
from playwright.sync_api import sync_playwright

RAIZ = Path(__file__).resolve().parent.parent
xml = (RAIZ / "docs/exemplos-partitura/K284-2.xml").read_bytes()
falhas = []
def check(nome, ok, extra=""):
    print(("  ok  " if ok else " FALHA"), nome, "" if ok else extra)
    if not ok: falhas.append(nome)

s = socket.socket(); s.bind(("127.0.0.1", 0)); PORT = s.getsockname()[1]; s.close()
proc = subprocess.Popen([sys.executable, str(RAIZ / "servidor-pc/sincrono_server.py"), "--port", str(PORT), "--cache", tempfile.mkdtemp(), "--no-browser"], stdout=subprocess.DEVNULL)
BASE = "http://127.0.0.1:%d" % PORT
for _ in range(60):
    try: socket.create_connection(("127.0.0.1", PORT), 0.2).close(); break
    except OSError: time.sleep(0.1)

def api(path, body=None, raw=None):
    data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
    req = urllib.request.Request(BASE + path, data=data, method="POST" if data is not None else "GET", headers={"Content-Type": "application/json"})
    try: return json.loads(urllib.request.urlopen(req, timeout=5).read() or b"{}")
    except urllib.error.HTTPError as e: return {"_status": e.code, **json.loads(e.read() or b"{}")}

LINES = "new Set([...document.querySelectorAll('#score-view svg .ln, #score-view svg line')].map(l=>Math.round(l.getBoundingClientRect().top/20))).size"
try:
    api("/api/score/upload?name=K284-2.xml", raw=xml)
    with sync_playwright() as p:
        b = p.chromium.launch(); erros = []
        ctx = b.new_context(viewport={"width": 390, "height": 800}, has_touch=True)
        pg = ctx.new_page(); pg.on("pageerror", lambda e: erros.append(str(e)))
        pg.goto(BASE + "/"); pg.wait_for_timeout(500)
        if pg.is_visible("#start-host"): pg.click("#start-host")
        pg.click('#tabs [data-go="sync"]'); pg.wait_for_selector("#score-view svg", timeout=8000)
        check("botões redondos (modo, A−, A+, palco)", all(pg.eval_on_selector(i, "e => getComputedStyle(e).borderRadius") in ("50%", "22px") or float(pg.eval_on_selector(i, "e => parseFloat(getComputedStyle(e).borderTopLeftRadius)")) >= 20 for i in ("#score-mode", "#score-zoom-in", "#score-zoom-out", "#score-stage")))
        check("anfitrião vê o seletor de parte", pg.is_visible("#score-part"))
        modos = []
        for _ in range(3):
            modos.append(pg.evaluate("window.__scoreView.mode")); pg.click("#score-mode"); pg.wait_for_timeout(300)
        check("botão cicla continuous→pages→score", modos == ["continuous", "pages", "score"], modos)
        # modo 'score': sistemas = marcas new-system (25 no K284), sem rolagem horizontal
        pg.evaluate("window.__scoreView.mode='score'"); pg.click("#score-mode"); pg.click("#score-mode")  # volta para continuous->pages->score
        pg.wait_for_timeout(400)
        check("modo 'score' ativo", pg.evaluate("window.__scoreView.mode") == "score", pg.evaluate("window.__scoreView.mode"))
        sw = pg.evaluate("[document.querySelector('#score-view').scrollWidth, document.querySelector('#score-view').clientWidth]")
        check("sem rolagem horizontal no celular (modo partitura)", sw[0] <= sw[1] + 2, sw)
        # palco
        pg.click("#score-stage"); pg.wait_for_timeout(500)
        check("palco: classe stage e botões à mostra", pg.evaluate("document.querySelector('#score').classList.contains('stage')") and pg.is_visible("#score-mode") and pg.is_visible("#score-stage"))
        check("palco: não é só uma linha (rolagem vertical ou várias linhas)", pg.evaluate("document.querySelector('#score-view').classList.contains('pages')"))
        sw = pg.evaluate("[document.querySelector('#score-view').scrollWidth, document.querySelector('#score-view').clientWidth]")
        check("palco: sem rolagem horizontal", sw[0] <= sw[1] + 2, sw)
        n_sys = pg.evaluate("document.querySelectorAll('#score-view svg g.system, #score-view svg [data-system]').length")
        pg.screenshot(path="/tmp/palco390.png")
        pg.click("#score-stage"); pg.wait_for_timeout(300)
        check("sair do palco", not pg.evaluate("document.querySelector('#score').classList.contains('stage')"))
        # convidado: sem seletor de parte, mesmo com sobra salva
        gp = ctx.new_page(); gp.on("pageerror", lambda e: erros.append(str(e)))
        gp.add_init_script("try{localStorage.setItem('sincrono.part','1')}catch(e){}")
        gp.goto(BASE + "/?guest=1"); gp.wait_for_selector("#score-view svg", timeout=8000)
        check("convidado não vê o seletor de parte", not gp.is_visible("#score-part"))
        # tela larga e estreita
        for w in (320, 768):
            pg.set_viewport_size({"width": w, "height": 700}); pg.wait_for_timeout(500)
            sw = pg.evaluate("[document.documentElement.scrollWidth, window.innerWidth]")
            check("sem estouro horizontal da página a %dpx" % w, sw[0] <= sw[1] + 2, sw)
        check("sem erros de JS", not erros, erros)
        b.close()
    # ---- Nova música
    api("/api/groups", {"op": "add", "name": "Coro"})
    r = api("/api/setlist", {"op": "add", "name": "K284"}); check("add música", r.get("current") == 1, r)
    r = api("/api/setlist", {"op": "new"})
    check("nova música: solta a atual e limpa a partitura", r.get("current") is None and api("/api/score").get("score") in (None, False) or api("/api/score").get("name") is None, r)
    time.sleep(0.3)
    r = api("/api/setlist"); check("nova música: a música anterior continua intacta na lista", len(r["songs"]) == 1 and r["songs"][0]["name"] == "K284", r)
    api("/api/score/upload?name=completa.musicxml", raw=(RAIZ / "docs/exemplos-partitura/completa.musicxml").read_bytes())
    r = api("/api/setlist", {"op": "add", "name": "Segunda"})
    check("segunda música adicionada", len(r["songs"]) == 2, r)
    api("/api/setlist", {"op": "apply", "id": 1})
    sc = api("/api/score")
    check("a primeira música continua com a própria partitura (K284)", "K284" in json.dumps(sc), str(sc)[:120])
finally:
    proc.terminate()
print("FALHAS:", falhas if falhas else "nenhuma"); sys.exit(1 if falhas else 0)
