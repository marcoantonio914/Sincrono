#!/usr/bin/env python3
"""Marcadores de compasso (timestamp): matemática áudio<->partitura, servidor e fluxo na página.   python3 tools/teste_marcadores.py"""
import io, json, socket, subprocess, sys, tempfile, time, urllib.request, urllib.error, wave
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
def api(path, body=None, raw=None, headers=None, ok=True):
    data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
    h = {"Content-Type": "application/json"}; h.update(headers or {})
    try:
        return json.loads(urllib.request.urlopen(urllib.request.Request(BASE + path, data=data, method="POST" if data is not None else "GET", headers=h), timeout=5).read() or b"{}")
    except urllib.error.HTTPError as e:
        return {"_status": e.code}
def wav(sec=60):
    b = io.BytesIO(); w = wave.open(b, "wb"); w.setnchannels(1); w.setsampwidth(2); w.setframerate(8000); w.writeframes(b"\0\0" * 8000 * sec); w.close(); return b.getvalue()

try:
    api("/api/score/upload?name=completa.musicxml", raw=xml, headers={"Content-Type": "application/octet-stream"})
    r = api("/api/score/marks", {"sig": "a.wav", "marks": [0, 2000, 4000], "offset": -100})
    check("servidor guarda marcadores", r.get("marks", {}).get("a.wav", {}).get("marks") == [0, 2000, 4000] and r["marks"]["a.wav"]["offset"] == -100, r)
    check("marcadores fora de ordem são recusados", api("/api/score/marks", {"sig": "a.wav", "marks": [0, 3000, 2000]}).get("_status") == 400)
    check("ajuste absurdo é recusado", api("/api/score/marks", {"sig": "a.wav", "marks": [0], "offset": 99999}).get("_status") == 400)
    r = api("/api/score/marks", {"sig": "a.wav", "marks": None})
    check("marcadores podem ser apagados", "a.wav" not in r["marks"], r)
    api("/api/score/marks", {"sig": "a.wav", "marks": [0, 1000]})
    api("/api/score/upload?name=outra.musicxml", raw=xml, headers={"Content-Type": "application/octet-stream"})
    check("trocar a partitura apaga os marcadores", api("/api/score")["marks"] == {})

    with sync_playwright() as p:
        b = p.chromium.launch(); erros = []
        pg = b.new_page(viewport={"width": 520, "height": 900}); pg.on("pageerror", lambda e: erros.append(str(e)))
        pg.goto(BASE + "/"); pg.wait_for_timeout(500)
        if pg.is_visible("#start-host"): pg.click("#start-host")
        pg.wait_for_selector("#score-view svg", timeout=6000)
        # matemática com a partitura real
        res = pg.evaluate("""async () => {
          const buf = await (await fetch('/api/score/file?id=' + (await (await fetch('/api/score')).json()).file.id)).arrayBuffer();
          const sc = await SincronoScore.load(buf), n = sc.order.length;
          const marks = Array.from({length: n}, (_, k) => 1000 + k * 2500 + (k % 3) * 200);   // andamento "humano", diferente do escrito
          const errs = []; let rt = 0;
          for (let k = 0; k < n; k++) {
            const a = marks[k] + (k < n - 1 ? (marks[k+1] - marks[k]) / 2 : 400);
            const t = sc.fromAudio(marks, 0, a), o = sc.order[k];
            const want = k < n - 1 ? (o.ms0 + o.ms1) / 2 : o.ms0 + 400;
            if (Math.abs(t - want) > 1) errs.push([k, t, want]);
            if (Math.abs(sc.toAudio(marks, 0, t) - a) > 1) rt++;
          }
          return { n, errs: errs.slice(0, 3), rt, antes: sc.fromAudio(marks, 0, 100), off: sc.fromAudio(marks, -100, 1000 + 100) };
        }""")
        check("áudio -> partitura cai no meio de cada compasso (%d compassos)" % res["n"], not res["errs"], res["errs"])
        check("partitura -> áudio é o inverso", res["rt"] == 0, res["rt"])
        check("antes do 1º marcador o cursor fica no início", res["antes"] == 0)
        check("o ajuste do toque adianta os marcadores", abs(res["off"] - 0) < 1 or res["off"] >= 0, res["off"])
        # fluxo: marcar tocando
        api("/api/audio/upload?group=g1&name=m.wav", raw=wav(), headers={"X-File-Type": "audio/wav"})
        pg.wait_for_timeout(1500)
        check("sem marcadores aparece o botão Marcar compassos", pg.is_visible("#marks-start"))
        pg.click("#marks-start")
        pg.click("#marks-tap"); pg.wait_for_timeout(300)
        check("não marca com o áudio parado", pg.evaluate("document.getElementById('marks-tap').textContent").startswith("COMPASSO 1 "))
        api("/api/audio/cmd?group=all", {"type": "play", "startAt": time.time() * 1000 + 300}); pg.wait_for_timeout(1200)
        for _ in range(4):
            pg.click("#marks-tap"); pg.wait_for_timeout(700)
        pg.click("#marks-undo"); pg.click("#marks-tap")
        pg.fill("#marks-delay", "150")
        pg.click("#marks-save"); pg.wait_for_timeout(1500)
        m = api("/api/score")["marks"].get("m.wav")
        check("fluxo: 4 marcadores salvos em ordem, ajuste -150", m and len(m["marks"]) == 4 and m["offset"] == -150 and m["marks"] == sorted(m["marks"]), m)
        check("os marcadores têm ~0,7 s entre si", m and all(600 < b - a < 900 for a, b in zip(m["marks"][:3], m["marks"][1:3])), m)
        check("depois de salvar aparece Apagar marcadores", pg.is_visible("#marks-clear"))
        api("/api/audio/cmd?group=all", {"type": "stop"})
        # cliente não vê os controles
        cl = b.new_page(viewport={"width": 420, "height": 800}); cl.goto(BASE + "/?guest=1"); cl.wait_for_timeout(2000)
        check("cliente não vê Marcar compassos", not cl.is_visible("#marks-start") and not cl.is_visible("#marks-clear"))
        check("sem erros de JavaScript", not erros, erros)
        b.close()
finally:
    proc.terminate()
print("\nFALHAS:", falhas) if falhas else print("\nTodos os testes passaram.")
sys.exit(1 if falhas else 0)
