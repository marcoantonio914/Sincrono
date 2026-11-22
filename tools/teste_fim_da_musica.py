#!/usr/bin/env python3
"""Fim da música: tudo volta ao 0:00 sozinho (áudio e metrônomo param). Também confere pausa e botões da playlist.   python3 tools/teste_fim_da_musica.py"""
import io, json, socket, subprocess, sys, tempfile, time, urllib.request, wave
from pathlib import Path
from playwright.sync_api import sync_playwright

RAIZ = Path(__file__).resolve().parent.parent
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
def wav(sec):
    b = io.BytesIO(); w = wave.open(b, "wb"); w.setnchannels(1); w.setsampwidth(2); w.setframerate(8000); w.writeframes(b"\0\0" * 8000 * sec); w.close(); return b.getvalue()
try:
    api("/api/audio/upload?group=g1&name=curta.wav", raw=wav(4), headers={"X-File-Type": "audio/wav"})
    with sync_playwright() as p:
        b = p.chromium.launch(); erros = []
        pg = b.new_page(viewport={"width": 480, "height": 900}); pg.on("pageerror", lambda e: erros.append(str(e)))
        pg.goto(BASE + "/"); pg.wait_for_timeout(500)
        if pg.is_visible("#start-host"): pg.click("#start-host")
        pg.click('#tabs [data-go="audio"]'); pg.wait_for_timeout(2000)
        pg.check("#with-metro"); pg.check("#with-audio")
        pg.click("#play-all"); pg.wait_for_timeout(4500)
        au = api("/api/audio?group=g1"); st = api("/api/state")
        check("tocando com áudio e metrônomo", au.get("playing") and st.get("playing"), (au, st))
        # a música (4 s) acaba sozinha: tudo para e volta ao 0:00
        pg.wait_for_timeout(5500)
        au = api("/api/audio?group=g1"); st = api("/api/state")
        check("no fim o áudio para e volta ao 0:00", not au.get("playing") and au.get("positionMs", 1) == 0, au)
        check("no fim o metrônomo também para", not st.get("playing"), st)
        # pausa na playlist: não volta ao início
        pg.click('#tabs [data-go="playlist"]')
        check("playlist tem pausar entre anterior e tocar", pg.locator(".pl-ctrl button").evaluate_all("els => els.map(e => e.id)") == ["rep-prev", "pl-pause", "pl-play", "pl-stop", "rep-next"])
        pg.click("#pl-play"); pg.wait_for_timeout(3600)
        pg.click("#pl-pause"); pg.wait_for_timeout(1800)
        au = api("/api/audio?group=g1")
        check("pausar mantém a posição (não zera)", not au.get("playing") and au.get("positionMs", 0) > 200, au)
        # o tocar fica no meio da linha
        box = pg.evaluate("(()=>{const r=document.querySelector('.pl-ctrl').getBoundingClientRect(), p=document.getElementById('pl-play').getBoundingClientRect(); return [r.x + r.width/2, p.x + p.width/2];})()")
        check("botão tocar centralizado (%.0f vs %.0f)" % tuple(box), abs(box[0] - box[1]) < 2, box)
        # botões redondos na aba SYNC e MÚSICA
        pg.click('#tabs [data-go="sync"]')
        rr = pg.evaluate("getComputedStyle(document.getElementById('play')).borderRadius")
        check("SYNC: botões redondos (%s)" % rr, rr in ("50%", "32px", "26px"), rr)
        pg.click('#tabs [data-go="audio"]')
        rr = pg.evaluate("getComputedStyle(document.getElementById('play-all')).borderRadius")
        check("MÚSICA: botões redondos (%s)" % rr, rr in ("50%", "32px", "26px"), rr)
        check("sem erros de JavaScript", not erros, erros)
        pg.screenshot(path="/tmp/claude-0/-home-claude/c851e147-eb1f-58c7-8838-8cb9b59b2d38/scratchpad/dash/musica.png")
        pg.click('#tabs [data-go="sync"]'); pg.screenshot(path="/tmp/claude-0/-home-claude/c851e147-eb1f-58c7-8838-8cb9b59b2d38/scratchpad/dash/sync.png")
        b.close()
finally:
    proc.terminate()
print("\nFALHAS:", falhas) if falhas else print("\nTodos os testes passaram.")
sys.exit(1 if falhas else 0)
