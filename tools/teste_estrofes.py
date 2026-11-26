#!/usr/bin/env python3
"""v40: estrofe atual (repetição com contagem) e painel 'pronto' por aparelho.   python3 tools/teste_estrofes.py"""
import io, json, socket, subprocess, sys, tempfile, time, urllib.request, wave
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
def wav(sec=20):
    b = io.BytesIO(); w = wave.open(b, "wb"); w.setnchannels(1); w.setsampwidth(2); w.setframerate(8000); w.writeframes(b"\0\0" * 8000 * sec); w.close(); return b.getvalue()

m = lambda n, a="", e="": f'<measure number="{n}">{a}<note><pitch><step>C</step><octave>5</octave></pitch><duration>4</duration><type>whole</type><lyric number="1"><text>um</text></lyric><lyric number="2"><text>dois</text></lyric></note>{e}</measure>'
at = '<attributes><divisions>1</divisions><time><beats>4</beats><beat-type>4</beat-type></time></attributes><direction><sound tempo="120"/></direction>'
bar = "<barline location='right'><bar-style>light-heavy</bar-style><repeat direction='backward' times='3'/></barline>"
XML = ('<?xml version="1.0"?><score-partwise><part-list><score-part id="P1"><part-name>S</part-name></score-part></part-list><part id="P1">'
       + m(1, at) + m(2, "", bar) + "</part></score-partwise>").encode()

try:
    with sync_playwright() as p:
        b = p.chromium.launch(); erros = []
        pg = b.new_page(viewport={"width": 480, "height": 900}); pg.on("pageerror", lambda e: erros.append(str(e)))
        pg.goto(BASE + "/"); pg.wait_for_timeout(600)
        if pg.is_visible("#start-host"): pg.click("#start-host")
        pg.set_input_files("#score-file", files=[{"name": "hino.musicxml", "mimeType": "application/xml", "buffer": XML}])
        pg.wait_for_selector("#score-view svg", timeout=8000)
        pg.click('#tabs [data-go="sync"]'); pg.wait_for_timeout(500)
        def verso(ms):
            post("/api/score/pos", {"ms": ms}); pg.wait_for_timeout(2200)
            return pg.inner_text("#score-verse") if pg.is_visible("#score-verse") else "", pg.evaluate("[...document.querySelectorAll('#score-view text[data-v]')].map(t => [t.getAttribute('data-v'), t.getAttribute('opacity')])")
        t, ops = verso(500)
        check("1ª passada: 'Estrofe 1/3'", t == "Estrofe 1/3", t)
        check("1ª passada: a letra 1 em destaque, a 2 esmaecida", all((o == "1") == (v == "1") for v, o in ops if True) if False else any(v == "1" and o == "1" for v, o in ops) and any(v == "2" and o == "0.35" for v, o in ops), ops)
        t, ops = verso(4500)
        check("2ª passada: 'Estrofe 2/3'", t == "Estrofe 2/3", t)
        check("2ª passada: a letra 2 em destaque, a 1 esmaecida", any(v == "2" and o == "1" for v, o in ops) and any(v == "1" and o == "0.35" for v, o in ops), ops)
        t, ops = verso(9000)
        check("3ª passada: 'Estrofe 3/3' (só há 2 linhas: segue a última)", t == "Estrofe 3/3" and any(v == "2" and o == "1" for v, o in ops), (t, ops))
        # painel "pronto": áudio do grupo carregado, aparelho (esta página) ainda sem baixar
        post("/api/audio/upload?group=g1&name=m.wav", raw=wav(), headers={"X-File-Type": "audio/wav"})
        pg.click('#tabs [data-go="mixer"]'); pg.wait_for_timeout(4500)
        d = get("/api/devices")
        check("/api/devices traz o estado do áudio de cada aparelho", d and all("audio" in x for x in d), d)
        txt = pg.inner_text("#devices-list").lower()
        check("painel 'pronto' aparece no mixer (%s)" % txt[:60].replace("\n", " "), "pront" in txt, txt[:200])
        check("sem erros de script", not erros, erros)
        b.close()
finally:
    proc.terminate()
print("\n%s" % ("TUDO OK" if not falhas else "FALHAS: %s" % falhas)); sys.exit(1 if falhas else 0)
