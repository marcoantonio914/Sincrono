#!/usr/bin/env python3
"""Testa score.html (a página que a WebView do app abre) com o servidor do PC e Chromium, simulando o que o app
entrega a cada 50 ms (sincronoTick: metrônomo, áudio, grupo).   python3 tools/teste_partitura_app.py"""
import json, socket, subprocess, sys, tempfile, time, urllib.request
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


CUR = "parseFloat(document.querySelector('#score-view .ln').getAttribute('x1'))"
try:
    api("/api/score/upload?name=completa.musicxml", raw=xml)
    api("/api/groups", {"op": "add", "name": "Violoncelos"})
    gid = api("/api/groups")["groups"][1]["id"]
    api("/api/score/part", {"group": gid, "part": 1})
    with sync_playwright() as p:
        b = p.chromium.launch()
        erros = []
        # ---- cliente (como o GuestScreen do app)
        g = b.new_page(viewport={"width": 400, "height": 520})
        g.on("pageerror", lambda e: erros.append(str(e)))
        g.goto(BASE + "/score.html")
        g.wait_for_selector("#score-view svg", timeout=6000)
        g.evaluate("sincronoTick({group:'g1',bpm:100,metro:null,audio:null,audioPlaying:false})")
        check("cliente: sem controles de anfitrião", not g.is_visible("#score-file") and not g.is_visible("#score-remove"))
        g.evaluate("sincronoTick({group:'%s',bpm:100,metro:null,audio:null,audioPlaying:false})" % gid)
        g.wait_for_timeout(2500)
        txt = g.eval_on_selector("#score-view svg", "s => s.textContent")
        check("cliente do grupo Violoncelos vê a parte do violoncelo (mp, sem 'La')", "mp" in txt and "La" not in txt, txt[:80])
        g.evaluate("sincronoTick({group:'%s',bpm:100,metro:null,audio:null,audioPlaying:false})" % gid); g.wait_for_timeout(200)
        x_parado = g.evaluate(CUR)
        g.evaluate("sincronoTick({group:'%s',bpm:100,metro:-1500,audio:null,audioPlaying:false})" % gid); g.wait_for_timeout(200)
        x_contagem = g.evaluate(CUR)
        check("contagem regressiva do metrônomo: cursor parado no início", abs(x_contagem - x_parado) < 1, (x_parado, x_contagem))
        g.evaluate("sincronoTick({group:'%s',bpm:100,metro:3000,audio:null,audioPlaying:false})" % gid); g.wait_for_timeout(200)
        x_metro = g.evaluate(CUR)
        check("metrônomo a 3 s (100 bpm): cursor no compasso 2", x_metro > x_parado + 40, (x_parado, x_metro))
        g.evaluate("sincronoTick({group:'%s',bpm:100,metro:null,audio:7300,audioPlaying:true})" % gid); g.wait_for_timeout(200)
        x_audio = g.evaluate(CUR)
        check("áudio a 7,3 s: cursor volta ao compasso 1 pela repetição", x_audio < x_metro - 20, (x_metro, x_audio))
        g.evaluate("sincronoTick({group:'%s',bpm:100,metro:3000,audio:7300,audioPlaying:true})" % gid); g.wait_for_timeout(200)
        check("áudio tocando tem prioridade sobre o metrônomo", abs(g.evaluate(CUR) - x_audio) < 1)
        g.evaluate("sincronoTick({group:'%s',bpm:120,metro:2400,audio:null,audioPlaying:false})" % gid); g.wait_for_timeout(200)
        x120 = g.evaluate(CUR)
        g.evaluate("sincronoTick({group:'%s',bpm:100,metro:2400,audio:null,audioPlaying:false})" % gid); g.wait_for_timeout(200)
        x100 = g.evaluate(CUR)
        check("metrônomo mais rápido que a partitura leva o cursor mais longe", x120 > x100 + 5, (x100, x120))
        # ---- anfitrião (aba Partitura do app: ?host=1)
        h = b.new_page(viewport={"width": 400, "height": 700})
        h.on("pageerror", lambda e: erros.append(str(e)))
        h.goto(BASE + "/score.html?host=1")
        h.wait_for_selector("#score-view svg", timeout=6000)
        check("anfitrião (aba do app): botão remover visível, sem o campo de arquivo (o app tem o seu); parte é escolhida no cartão do grupo",
              h.is_visible("#score-remove") and not h.is_visible("#score-file") and h.locator("#score-groups").count() == 0)
        api("/api/score/part", {"group": "g1", "part": 2}); g.wait_for_timeout(1500)
        g.evaluate("sincronoTick({group:'g1',bpm:100,metro:null,audio:null,audioPlaying:false})"); g.wait_for_timeout(1500)
        gt = g.eval_on_selector("#score-view svg", "s => s.textContent")
        check("cliente do grupo Geral passa a ver o piano em até ~1,5 s depois da escolha do anfitrião", "mp" not in gt and "La" not in gt and "Andante" in gt, gt[:80])
        check("sem erros de JavaScript", not erros, erros)
        h.screenshot(path="/tmp/score-app-host.png")
        b.close()
finally:
    proc.terminate()
print("\n" + ("TUDO CERTO" if not falhas else "%d FALHA(S): %s" % (len(falhas), falhas)))
sys.exit(1 if falhas else 0)
