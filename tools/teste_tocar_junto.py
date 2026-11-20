#!/usr/bin/env python3
"""Testa 'Tocar todos' com Metrônomo e/ou Áudio (servidor do PC de verdade + Chromium).   python3 tools/teste_tocar_junto.py"""
import io, json, socket, subprocess, sys, tempfile, time, urllib.request, wave
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


def api(path, body=None, raw=None, headers=None):
    data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
    h = {"Content-Type": "application/json"}; h.update(headers or {})
    req = urllib.request.Request(BASE + path, data=data, method="POST" if data is not None else "GET", headers=h)
    return json.loads(urllib.request.urlopen(req, timeout=5).read() or b"{}")


def wav(sec=20):
    b = io.BytesIO(); w = wave.open(b, "wb"); w.setnchannels(1); w.setsampwidth(2); w.setframerate(8000); w.writeframes(b"\0\0" * 8000 * sec); w.close(); return b.getvalue()


try:
    now = lambda: time.time() * 1000
    # --- protocolo: startAt combinado
    t = now() + 6000
    r = api("/api/command", {"type": "play", "bpm": 100, "beatsPerBar": 4, "startAt": t})
    check("metrônomo aceita startAt futuro", abs(r["startAt"] - t) < 2, r)
    r = api("/api/command", {"type": "play", "bpm": 100, "beatsPerBar": 4, "startAt": now() - 60000})
    check("startAt absurdo (1 min atrás) é ignorado: usa o padrão", r["startAt"] > now() + 1500, r)
    api("/api/command", {"type": "pause", "bpm": 100, "beatsPerBar": 4})
    api("/api/audio/upload?group=g1&name=m.wav", raw=wav(), headers={"X-File-Type": "audio/wav"})
    t = now() + 5000
    a = api("/api/audio/cmd?group=all", {"type": "play", "startAt": t})
    st = api("/api/audio?group=g1")
    check("áudio aceita startAt combinado", st.get("playing") and abs(st["startAt"] - t) < 2, st)
    api("/api/audio/cmd?group=all", {"type": "stop"})

    with sync_playwright() as p:
        b = p.chromium.launch()
        pg = b.new_page(viewport={"width": 480, "height": 900})
        erros = []
        pg.on("pageerror", lambda e: erros.append(str(e)))
        pg.goto(BASE + "/"); pg.wait_for_timeout(500)
        if pg.is_visible("#start-host"): pg.click("#start-host")
        pg.set_input_files("#score-file", files=[{"name": "completa.musicxml", "mimeType": "application/xml", "buffer": xml}])
        pg.wait_for_selector("#score-view svg", timeout=6000)
        pg.click('#tabs [data-go="audio"]')
        pg.wait_for_timeout(1500)
        # 1) só metrônomo (sem áudio)
        pg.uncheck("#with-audio"); pg.check("#with-metro")
        pg.click("#play-all"); pg.wait_for_timeout(700)
        au = api("/api/audio?group=g1")
        check("só metrônomo: o áudio NÃO toca", not au.get("playing"), au)
        pg.click('#tabs [data-go="sync"]'); pg.wait_for_timeout(300)
        x0 = pg.evaluate("parseFloat(document.querySelector('#score-view .ln').getAttribute('x1'))")
        pg.wait_for_timeout(5600)
        x1 = pg.evaluate("parseFloat(document.querySelector('#score-view .ln').getAttribute('x1'))")
        check("só metrônomo: o cursor da partitura anda", x1 > x0 + 20, (x0, x1))
        pg.click('#tabs [data-go="audio"]')
        pg.click("#stop-all"); pg.wait_for_timeout(500)
        # 2) metrônomo + áudio: começam no mesmo instante
        pg.check("#with-audio"); pg.check("#with-metro")
        pg.click("#play-all"); pg.wait_for_timeout(700)
        st = api("/api/audio?group=g1")
        check("metrônomo + áudio: o áudio toca", st.get("playing"), st)
        # estado do metrônomo vem por SSE; lê pela página
        sa = pg.evaluate("state.startAt"); ok_play = pg.evaluate("state.playing")
        check("metrônomo + áudio: o metrônomo toca e começa junto com o áudio (dif. %.0f ms)" % abs(sa - st["startAt"]), ok_play and abs(sa - st["startAt"]) < 5, (sa, st.get("startAt")))
        pg.click("#stop-all"); pg.wait_for_timeout(600)
        check("parar: metrônomo parou", not pg.evaluate("state.playing"))
        # 3) só áudio continua como antes
        pg.uncheck("#with-metro"); pg.check("#with-audio")
        pg.click("#play-all"); pg.wait_for_timeout(600)
        check("só áudio: metrônomo continua parado", not pg.evaluate("state.playing") and api("/api/audio?group=g1").get("playing"))
        pg.click("#stop-all"); pg.wait_for_timeout(400)
        check("sem erros de JavaScript", not erros, erros)
        b.close()
finally:
    proc.terminate()

print("\nFALHAS:", falhas) if falhas else print("\nTodos os testes passaram.")
sys.exit(1 if falhas else 0)
