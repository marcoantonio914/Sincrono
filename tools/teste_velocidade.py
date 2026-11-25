#!/usr/bin/env python3
"""v38: gerar cópia do áudio em outra velocidade (mantendo o tom), metrônomo acompanha, marcadores reescalados, voltar ao original.   python3 tools/teste_velocidade.py"""
import io, json, math, socket, struct, subprocess, sys, tempfile, time, urllib.request, wave
from pathlib import Path
from playwright.sync_api import sync_playwright

RAIZ = Path(__file__).resolve().parent.parent
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
def wav_bytes(sec=10, sr=22050, hz=440):
    b = io.BytesIO(); w = wave.open(b, "wb"); w.setnchannels(1); w.setsampwidth(2); w.setframerate(sr)
    w.writeframes(b"".join(struct.pack("<h", int(12000 * math.sin(2 * math.pi * hz * i / sr))) for i in range(sr * sec))); w.close(); return b.getvalue()
def info_wav(data):
    w = wave.open(io.BytesIO(data)); n = w.getnframes(); sr = w.getframerate(); fr = w.readframes(n)
    xs = struct.unpack("<%dh" % (len(fr) // 2), fr); lo, hi = int(len(xs) * .2), int(len(xs) * .8)
    cross = sum(1 for i in range(lo + 1, hi) if xs[i - 1] < 0 <= xs[i])
    return n / sr, cross / ((hi - lo) / sr)

try:
    orig = wav_bytes()
    post("/api/audio/upload?group=g1&name=cancao.wav", raw=orig, headers={"X-File-Type": "audio/wav"})
    post("/api/score/marks", {"sig": "cancao.wav", "marks": [0, 2000, 4000, 6000], "offset": -20})
    post("/api/command", {"type": "settings", "bpm": 120, "beatsPerBar": 4})
    with sync_playwright() as p:
        b = p.chromium.launch(); erros = []
        pg = b.new_page(viewport={"width": 480, "height": 900}); pg.on("pageerror", lambda e: erros.append(str(e)))
        pg.goto(BASE + "/"); pg.wait_for_timeout(600)
        if pg.is_visible("#start-host"): pg.click("#start-host")
        pg.click('#tabs [data-go="audio"]'); pg.wait_for_timeout(1500)
        pg.fill("#speed-from", "120"); pg.fill("#speed-to", "84")
        pg.click("#speed-go")
        pg.wait_for_function("document.getElementById('speed-msg').textContent.startsWith('Pronto') || document.getElementById('speed-msg').textContent.startsWith('Não deu')", timeout=60000)
        txt = pg.inner_text("#speed-msg"); print("  msg:", txt)
        a = get("/api/audio?group=g1")
        check("o áudio do grupo virou a cópia '(84 bpm)'", a["file"]["name"] == "cancao (84 bpm).wav", a["file"])
        data = urllib.request.urlopen(BASE + "/api/audio/file?id=%d&group=g1" % a["file"]["id"]).read()
        dur, hz = info_wav(data)
        check("duração = 10 s / 0,7 (%.2f s)" % dur, abs(dur - 10 / 0.7) < 0.2, dur)
        check("tom mantido (%.1f Hz, esperado 440)" % hz, abs(hz - 440) < 12, hz)
        check("metrônomo foi para 84 bpm", get("/api/state")["bpm"] == 84)
        m = get("/api/score").get("marks", {}).get("cancao (84 bpm).wav")
        check("marcadores reescalados por 1/0,7", bool(m) and abs(m["marks"][1] - 2000 / 0.7) < 1 and abs(m["marks"][3] - 6000 / 0.7) < 1, m)
        check("botão 'Voltar ao original' aparece", pg.is_visible("#speed-reset"))
        # de novo, a partir do ORIGINAL (não empilha esticadas): 150 bpm
        pg.fill("#speed-to", "150"); pg.click("#speed-go")
        pg.wait_for_function("document.getElementById('speed-msg').textContent.includes('150 bpm') && document.getElementById('speed-msg').textContent.startsWith('Pronto')", timeout=60000)
        a = get("/api/audio?group=g1")
        dur, hz = info_wav(urllib.request.urlopen(BASE + "/api/audio/file?id=%d&group=g1" % a["file"]["id"]).read())
        check("segunda vez parte do original: %.2f s (esperado %.2f)" % (dur, 10 / 1.25), abs(dur - 8) < 0.2 and a["file"]["name"] == "cancao (150 bpm).wav", (dur, a["file"]))
        pg.click("#speed-reset")
        pg.wait_for_function("document.getElementById('speed-msg').textContent.startsWith('Áudios originais')", timeout=30000)
        a = get("/api/audio?group=g1")
        check("voltar ao original: mesmo nome e tamanho", a["file"]["name"] == "cancao.wav" and a["file"]["size"] == len(orig), a["file"])
        check("metrônomo voltou a 120", get("/api/state")["bpm"] == 120)
        # recarregar a página não perde o botão nem o original (IndexedDB)
        pg.click("#speed-go"); pg.wait_for_function("document.getElementById('speed-msg').textContent.startsWith('Pronto')", timeout=60000)
        pg.reload(); pg.wait_for_timeout(800)
        if pg.is_visible("#start-host"): pg.click("#start-host")
        pg.click('#tabs [data-go="audio"]'); pg.wait_for_timeout(4500)
        check("depois de recarregar, 'Voltar ao original' continua aparecendo", pg.is_visible("#speed-reset"))
        pg.click("#speed-reset")
        pg.wait_for_function("document.getElementById('speed-msg').textContent.startsWith('Áudios originais') || document.getElementById('speed-msg').textContent.includes('não está guardado')", timeout=30000)
        a = get("/api/audio?group=g1")
        check("e restaura o original mesmo depois de recarregar", a["file"]["name"] == "cancao.wav" and a["file"]["size"] == len(orig), a["file"])
        # o áudio acompanha o metrônomo
        pg.fill("#speed-from", "120"); pg.check("#speed-follow")
        post("/api/command", {"type": "settings", "bpm": 100, "beatsPerBar": 4})
        pg.wait_for_function("document.getElementById('speed-msg').textContent.startsWith('Pronto')", timeout=60000)
        a = get("/api/audio?group=g1")
        check("acompanhar o metrônomo: BPM mudou para 100 e o áudio foi refeito sozinho", a["file"]["name"] == "cancao (100 bpm).wav", a["file"])
        pg.uncheck("#speed-follow")
        # com música tocando, recusa
        post("/api/audio/cmd?group=g1", {"type": "play"}); pg.wait_for_timeout(300)
        pg.click("#speed-go"); pg.wait_for_timeout(1500)
        check("com a música tocando, pede para parar antes", "Pare a música" in pg.inner_text("#speed-msg"), pg.inner_text("#speed-msg"))
        check("sem erros de script", not erros, erros)
        b.close()
finally:
    proc.terminate()
print("\n%s" % ("TUDO OK" if not falhas else "FALHAS: %s" % falhas)); sys.exit(1 if falhas else 0)
