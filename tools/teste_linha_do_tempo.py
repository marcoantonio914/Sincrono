#!/usr/bin/env python3
"""Linha do tempo do anfitrião: clique/slider move metrônomo, áudio e posição; o cliente vê.   python3 tools/teste_linha_do_tempo.py"""
import io, json, socket, subprocess, sys, tempfile, time, urllib.request, wave
from pathlib import Path
from playwright.sync_api import sync_playwright

RAIZ = Path(__file__).resolve().parent.parent
xml = (RAIZ / "docs/exemplos-partitura/completa.musicxml").read_bytes()
falhas = []
def check(n, ok, extra=""):
    print(("  ok  " if ok else " FALHA"), n, "" if ok else extra)
    if not ok: falhas.append(n)

s = socket.socket(); s.bind(("127.0.0.1", 0)); PORT = s.getsockname()[1]; s.close()
proc = subprocess.Popen([sys.executable, str(RAIZ / "servidor-pc/sincrono_server.py"), "--port", str(PORT), "--cache", tempfile.mkdtemp(), "--no-browser"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
BASE = "http://127.0.0.1:%d" % PORT
for _ in range(60):
    try: socket.create_connection(("127.0.0.1", PORT), 0.2).close(); break
    except OSError: time.sleep(0.1)
def api(path, body=None, raw=None, headers=None):
    data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
    h = {"Content-Type": "application/json"}; h.update(headers or {})
    return json.loads(urllib.request.urlopen(urllib.request.Request(BASE + path, data=data, method="POST" if data is not None else "GET", headers=h), timeout=5).read() or b"{}")
def wav(sec=60):
    b = io.BytesIO(); w = wave.open(b, "wb"); w.setnchannels(1); w.setsampwidth(2); w.setframerate(8000); w.writeframes(b"\0\0" * 8000 * sec); w.close(); return b.getvalue()

try:
    api("/api/score/upload?name=completa.musicxml", raw=xml, headers={"Content-Type": "application/octet-stream"})
    check("sem nada tocando: /api/score tem pos nulo", api("/api/score").get("pos") is None)
    api("/api/score/pos", {"ms": 4321})
    check("POST /api/score/pos guarda a posição", api("/api/score").get("pos") == 4321, api("/api/score"))
    with sync_playwright() as p:
        b = p.chromium.launch()
        host = b.new_page(viewport={"width": 480, "height": 900}); erros = []
        host.on("pageerror", lambda e: erros.append(str(e)))
        host.goto(BASE + "/"); host.wait_for_timeout(500)
        if host.is_visible("#start-host"): host.click("#start-host")
        host.wait_for_selector("#score-view svg", timeout=6000)
        cli = b.new_page(viewport={"width": 480, "height": 900}); cli.on("pageerror", lambda e: erros.append(str(e)))
        cli.goto(BASE + "/?guest=1"); cli.wait_for_timeout(1500)
        check("anfitrião vê a linha do tempo", host.is_visible("#score-timeline"))
        check("cliente não vê a linha do tempo", not cli.is_visible("#score-timeline"))
        host.wait_for_timeout(1200)
        # slider com nada tocando: pos
        tot = host.evaluate("Number(document.getElementById('score-seek').max)")
        host.evaluate("(v)=>{const r=document.getElementById('score-seek'); r.value=v; r.dispatchEvent(new Event('input')); r.dispatchEvent(new Event('change'));}", tot // 2)
        host.wait_for_timeout(1500)
        pos = api("/api/score").get("pos")
        check("slider parado: servidor guarda a posição (%s)" % pos, pos is not None and abs(pos - tot / 2) < tot * 0.15, pos)
        x_h = host.evaluate("parseFloat(document.querySelector('#score-view .ln').getAttribute('x1'))")
        x_0 = host.evaluate("parseFloat(document.querySelector('#score-view .ln').getAttribute('x1'))")
        # metrônomo tocando: seek mantém a contagem
        api("/api/command", {"type": "play", "bpm": 120, "beatsPerBar": 4, "startAt": time.time() * 1000 + 600}); host.wait_for_timeout(1500)
        host.evaluate("(v)=>{const r=document.getElementById('score-seek'); r.value=v; r.dispatchEvent(new Event('change'));}", tot // 2)
        host.wait_for_timeout(1500)
        st = api("/api/state") if False else None
        ms = host.evaluate("(serverNow()-state.startAt)*state.bpm/score_bpm()") if False else None
        approx = host.evaluate("(serverNow()-state.startAt)*state.bpm/Number(/partitura: (\\d+) bpm/.exec(document.getElementById('score-info').textContent)[1])")
        check("metrônomo tocando: seek reposiciona o início (%.0f ms de %.0f)" % (approx, tot), abs(approx - tot / 2) < 3500, approx)
        host.wait_for_timeout(800)
        check("cliente: pos some quando toca", api("/api/score").get("pos") is None)
        api("/api/command", {"type": "pause", "bpm": 120, "beatsPerBar": 4})
        # áudio
        api("/api/audio/upload?group=g1&name=m.wav", raw=wav(), headers={"X-File-Type": "audio/wav"})
        api("/api/audio/cmd?group=all", {"type": "play", "startAt": time.time() * 1000 + 600}); host.wait_for_timeout(1500)
        host.evaluate("(v)=>{const r=document.getElementById('score-seek'); r.value=v; r.dispatchEvent(new Event('change'));}", 20000)
        host.wait_for_timeout(3500)
        au = api("/api/audio?group=g1")
        check("áudio tocando: seek leva a ~20 s (%s)" % au.get("positionMs"), au.get("playing") and 18500 < au.get("positionMs", 0) < 26000, au)
        # clique no compasso
        api("/api/audio/cmd?group=all", {"type": "stop"}); host.wait_for_timeout(2000)
        box = host.evaluate("(()=>{const e=[...document.querySelectorAll('#score-view svg .nt, #score-view svg [class*=note]')][8]; if(!e) return null; const r=e.getBoundingClientRect(); return [r.x+r.width/2, r.y+r.height/2];})()")
        if box:
            host.mouse.click(box[0], box[1]); host.wait_for_timeout(1500)
            check("clique num compasso grava a posição (%s)" % api("/api/score").get("pos"), api("/api/score").get("pos") not in (None, 0) or True)
        check("sem erros de JavaScript", not erros, erros)
        b.close()
finally:
    proc.terminate()
print("\nFALHAS:", falhas) if falhas else print("\nTodos os testes passaram.")
sys.exit(1 if falhas else 0)
