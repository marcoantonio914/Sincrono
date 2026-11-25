#!/usr/bin/env python3
"""v37: tempo ao vivo (batida e posição preservadas), caixas Metrônomo/Áudio em tempo real, anfitrião que reinicia (época).   python3 tools/teste_ao_vivo.py"""
import io, json, socket, subprocess, sys, tempfile, time, urllib.request, wave
from pathlib import Path
from playwright.sync_api import sync_playwright

RAIZ = Path(__file__).resolve().parent.parent
falhas = []
def check(nome, ok, extra=""):
    print(("  ok  " if ok else " FALHA"), nome, "" if ok else extra)
    if not ok: falhas.append(nome)

s = socket.socket(); s.bind(("127.0.0.1", 0)); PORT = s.getsockname()[1]; s.close()
BASE = "http://127.0.0.1:%d" % PORT
def sobe():
    p = subprocess.Popen([sys.executable, str(RAIZ / "servidor-pc/sincrono_server.py"), "--port", str(PORT), "--cache", tempfile.mkdtemp(), "--no-browser"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    for _ in range(80):
        try: socket.create_connection(("127.0.0.1", PORT), 0.2).close(); break
        except OSError: time.sleep(0.1)
    return p
def api(path, body=None, raw=None, headers=None):
    data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
    h = {"Content-Type": "application/json"}; h.update(headers or {})
    req = urllib.request.Request(BASE + path, data=data, method="POST" if data is not None else "GET", headers=h)
    return json.loads(urllib.request.urlopen(req, timeout=5).read() or b"{}")
def wav(sec=60):
    b = io.BytesIO(); w = wave.open(b, "wb"); w.setnchannels(1); w.setsampwidth(2); w.setframerate(8000); w.writeframes(b"\0\0" * 8000 * sec); w.close(); return b.getvalue()

proc = sobe()
try:
    # 1) tempo ao vivo: a batida (e a posição) não pulam
    now = api("/api/sync", {"clientSentAt": int(time.time() * 1000)}).get("serverNow") or int(time.time() * 1000)
    api("/api/command", {"type": "play", "bpm": 120, "beatsPerBar": 4, "startAt": int(time.time() * 1000)})
    time.sleep(3.2)
    antes = api("/api/state") if False else None
    st0 = json.loads(urllib.request.urlopen(BASE + "/api/state").read())
    t = time.time() * 1000
    beat0 = (t - st0["startAt"]) / (60000 / st0["bpm"])
    api("/api/command", {"type": "settings", "bpm": 60, "beatsPerBar": 4})
    st1 = json.loads(urllib.request.urlopen(BASE + "/api/state").read())
    t = time.time() * 1000
    beat1 = (t - st1["startAt"]) / (60000 / st1["bpm"])
    check("tempo ao vivo: segue tocando com o novo andamento", st1["playing"] and st1["bpm"] == 60, st1)
    check("tempo ao vivo: a batida não pula (%.2f -> %.2f)" % (beat0, beat1), abs(beat1 - beat0) < 0.15, (beat0, beat1))
    check("estado traz a época do servidor", bool(st1.get("epoch")), st1)
    api("/api/command", {"type": "pause", "bpm": 60, "beatsPerBar": 4})

    # 2) caixas em tempo real (página do anfitrião)
    api("/api/audio/upload?group=g1&name=m.wav", raw=wav(), headers={"X-File-Type": "audio/wav"})
    with sync_playwright() as p:
        b = p.chromium.launch(); erros = []
        pg = b.new_page(viewport={"width": 480, "height": 900}); pg.on("pageerror", lambda e: erros.append(str(e)))
        pg.goto(BASE + "/"); pg.wait_for_timeout(500)
        if pg.is_visible("#start-host"): pg.click("#start-host")
        pg.click('#tabs [data-go="playlist"]'); pg.wait_for_timeout(2500)
        pg.evaluate("getAudio().resume()")
        pg.click("#play-all"); pg.wait_for_timeout(4500)
        check("áudio tocando", api("/api/audio?group=g1").get("playing"))
        check("metrônomo desmarcado: só áudio", not pg.evaluate("state.playing"))
        pg.check("#with-metro"); pg.wait_for_timeout(3500)
        check("marcar Metrônomo liga o clique na hora, sem parar o áudio", pg.evaluate("state.playing") and api("/api/audio?group=g1").get("playing"))
        pg.uncheck("#with-audio"); pg.wait_for_timeout(2500)
        check("desmarcar Áudio pausa só o áudio", (not api("/api/audio?group=g1").get("playing")) and pg.evaluate("state.playing"))
        pg.check("#with-audio"); pg.wait_for_timeout(6000)
        check("marcar Áudio volta a tocar junto com o metrônomo", api("/api/audio?group=g1").get("playing") and pg.evaluate("state.playing"))
        pg.uncheck("#with-metro"); pg.wait_for_timeout(2000)
        check("desmarcar Metrônomo para só o clique", (not pg.evaluate("state.playing")) and api("/api/audio?group=g1").get("playing"))
        pg.click("#stop-all"); pg.wait_for_timeout(2300)
        # 3) BPM digitado na página vale ao vivo
        pg.click('#tabs [data-go="sync"]'); pg.wait_for_timeout(300)
        pg.fill("#bpm", "90"); pg.wait_for_timeout(1500)
        check("BPM digitado chega ao servidor sem apertar Tocar", json.loads(urllib.request.urlopen(BASE + "/api/state").read())["bpm"] == 90)
        # 4) anfitrião reinicia: revisões recomeçam menores; a página tem de aceitar o estado novo
        for i in range(6): api("/api/command", {"type": "settings", "bpm": 100 + i, "beatsPerBar": 4})
        pg.wait_for_timeout(800)
        rev_antes = pg.evaluate("state.revision")
        proc.terminate(); proc.wait(); time.sleep(0.5)
        proc = sobe()
        api("/api/command", {"type": "play", "bpm": 77, "beatsPerBar": 4, "startAt": int(time.time() * 1000) + 500})
        pg.wait_for_timeout(5000)
        check("depois do reinício a página aceita o estado novo (rev %s -> %s)" % (rev_antes, pg.evaluate("state.revision")), pg.evaluate("state.playing && state.bpm === 77"), pg.evaluate("JSON.stringify(state)"))
        check("sem erros de script", not erros, erros)
        b.close()
finally:
    proc.terminate()
print("\n%s" % ("TUDO OK" if not falhas else "FALHAS: %s" % falhas)); sys.exit(1 if falhas else 0)
