#!/usr/bin/env python3
"""v39: com as duas caixas desmarcadas a partitura segue andando (relógio mudo); Tocar só com a partitura.   python3 tools/teste_so_partitura_ao_vivo.py"""
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
BASE = "http://127.0.0.1:%d" % PORT
proc = subprocess.Popen([sys.executable, str(RAIZ / "servidor-pc/sincrono_server.py"), "--port", str(PORT), "--cache", tempfile.mkdtemp(), "--no-browser"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
for _ in range(80):
    try: socket.create_connection(("127.0.0.1", PORT), 0.2).close(); break
    except OSError: time.sleep(0.1)
def get(path): return json.loads(urllib.request.urlopen(BASE + path, timeout=10).read())
def post(path, body=None, raw=None, headers=None):
    h = {"Content-Type": "application/json"}; h.update(headers or {})
    return json.loads(urllib.request.urlopen(urllib.request.Request(BASE + path, data=raw if raw is not None else json.dumps(body).encode(), method="POST", headers=h), timeout=10).read() or b"{}")
def wav(sec=60):
    b = io.BytesIO(); w = wave.open(b, "wb"); w.setnchannels(1); w.setsampwidth(2); w.setframerate(8000); w.writeframes(b"\0\0" * 8000 * sec); w.close(); return b.getvalue()
MS = "window.sincronoScore.ms()"
CUR = "parseFloat(document.querySelector('#score-view .ln').getAttribute('x1'))"
try:
    post("/api/audio/upload?group=g1&name=m.wav", raw=wav(), headers={"X-File-Type": "audio/wav"})
    with sync_playwright() as p:
        b = p.chromium.launch(); erros = []
        pg = b.new_page(viewport={"width": 480, "height": 900}); pg.on("pageerror", lambda e: erros.append(str(e)))
        pg.goto(BASE + "/"); pg.wait_for_timeout(600)
        if pg.is_visible("#start-host"): pg.click("#start-host")
        pg.set_input_files("#score-file", files=[{"name": "completa.musicxml", "mimeType": "application/xml", "buffer": xml}])
        pg.wait_for_selector("#score-view svg", timeout=8000)
        pg.click('#tabs [data-go="playlist"]'); pg.wait_for_timeout(2500)
        pg.evaluate("getAudio().resume()")
        pg.uncheck("#with-metro"); pg.uncheck("#with-audio")
        # a) Tocar com as duas caixas desmarcadas = só partitura
        pg.click("#play-all"); pg.wait_for_timeout(4500)
        st = get("/api/state")
        check("Tocar sem caixas: relógio mudo ligado (tocando e silent)", st["playing"] and st.get("silent") is True, st)
        check("Tocar sem caixas: o áudio não toca", not get("/api/audio?group=g1")["playing"])
        x0 = pg.evaluate(CUR); pg.wait_for_timeout(3500); x1 = pg.evaluate(CUR)
        check("Tocar sem caixas: o cursor anda (%.0f -> %.0f)" % (x0, x1), x1 > x0 + 10, (x0, x1))
        pg.click("#stop-all"); pg.wait_for_timeout(2500)
        # b) desmarcar as duas no meio da música com áudio tocando
        pg.check("#with-audio"); pg.click("#play-all"); pg.wait_for_timeout(8000)
        check("áudio tocando", get("/api/audio?group=g1")["playing"])
        xa = pg.evaluate(MS)
        pg.uncheck("#with-audio"); pg.wait_for_timeout(4500)
        st = get("/api/state")
        check("desmarcar a última caixa: áudio pausa e o relógio mudo assume", (not get("/api/audio?group=g1")["playing"]) and st["playing"] and st.get("silent") is True, st)
        xb = pg.evaluate(MS); pg.wait_for_timeout(3000); xc = pg.evaluate(MS)
        check("o cursor continua andando e não volta ao começo (%.0f, %.0f, %.0f ms)" % (xa, xb, xc), xc > xb + 500 and xb >= xa - 300, (xa, xb, xc))
        # c) marcar Metrônomo: o clique volta (deixa de ser mudo)
        pg.check("#with-metro"); pg.wait_for_timeout(3000)
        st = get("/api/state")
        check("marcar Metrônomo tira o mudo, sem parar", st["playing"] and st.get("silent") is False, st)
        # d) marcar Áudio com o relógio tocando: o áudio entra
        pg.uncheck("#with-metro"); pg.wait_for_timeout(2500)
        pg.check("#with-audio"); pg.wait_for_timeout(5500)
        check("marcar Áudio com a partitura andando: o áudio entra", get("/api/audio?group=g1")["playing"])
        # e) a mesma lógica pela função usada pela WebView do app: sincronoLive('audio', false, false) com áudio tocando
        pg.evaluate("window.sincronoStage && 0")
        pg.click("#stop-all"); pg.wait_for_timeout(2500)
        pg.uncheck("#with-metro"); pg.check("#with-audio"); pg.click("#play-all"); pg.wait_for_timeout(7000)
        pg.evaluate("document.getElementById('with-audio').checked = false")      # a caixa do app não existe aqui: simula o estado
        pg.evaluate("window.sincronoLive('audio', false, false)"); pg.wait_for_timeout(4500)
        st = get("/api/state")
        check("sincronoLive (app): áudio pausa e relógio mudo assume", (not get("/api/audio?group=g1")["playing"]) and st["playing"] and st.get("silent") is True, st)
        pg.evaluate("window.sincronoLive('metro', true, false)"); pg.wait_for_timeout(3000)
        st = get("/api/state")
        check("sincronoLive (app): metrônomo liga na posição, sem mudo", st["playing"] and st.get("silent") is False, st)
        check("sem erros de script", not erros, erros)
        b.close()
finally:
    proc.terminate()
print("\n%s" % ("TUDO OK" if not falhas else "FALHAS: %s" % falhas)); sys.exit(1 if falhas else 0)
