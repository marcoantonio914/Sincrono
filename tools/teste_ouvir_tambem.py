#!/usr/bin/env python3
"""Ouvir também: a página toca o áudio do próprio grupo e, marcado, o de outro grupo ao mesmo tempo (servidor do PC de verdade + Chromium).   python3 tools/teste_ouvir_tambem.py"""
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
    api("/api/groups", {"op": "add", "name": "Piano"})
    g = api("/api/groups")
    gid = [x["id"] for x in g["groups"] if x["name"] == "Piano"][0]
    api("/api/audio/upload?group=g1&name=voz.wav", raw=wav(30), headers={"X-File-Type": "audio/wav"})
    api("/api/audio/upload?group=%s&name=piano.wav" % gid, raw=wav(30), headers={"X-File-Type": "audio/wav"})
    with sync_playwright() as p:
        b = p.chromium.launch(args=["--autoplay-policy=no-user-gesture-required"])
        pg = b.new_page(viewport={"width": 480, "height": 900})
        erros = []
        pg.on("pageerror", lambda e: erros.append(str(e)))
        pg.goto(BASE + "/"); pg.wait_for_timeout(800)
        if pg.is_visible("#start-host"): pg.click("#start-host")
        pg.click('#tabs [data-go="mixer"]')
        pg.wait_for_selector("#extra-list .extra-row", timeout=8000)
        check("lista mostra o grupo Piano (não o próprio)", pg.inner_text("#extra-list").lower().count("piano") == 1 and "geral" not in pg.inner_text("#extra-list").lower())
        pg.evaluate("getAudio().resume()"); pg.wait_for_timeout(300)
        pg.check("#extra-list input[type=checkbox]")
        pg.wait_for_selector("#extra-list input[type=range]")
        pg.wait_for_timeout(2500)
        check("o arquivo do piano foi baixado e decodificado", pg.evaluate("!!(sincronoExtraAudio.players['%s'] && sincronoExtraAudio.players['%s'].buffer)" % (gid, gid)))
        pg.click('#tabs [data-go="playlist"]'); pg.wait_for_timeout(300)
        pg.uncheck("#with-metro"); pg.check("#with-audio")
        pg.click("#play-all"); pg.wait_for_timeout(4800)
        r = pg.evaluate("({ctx: audioContext.state, main: !!songNode, extra: !!sincronoExtraAudio.players['%s'].node})" % gid)
        check("toca o áudio do próprio grupo", r["main"], r)
        check("toca TAMBÉM o do piano ao mesmo tempo", r["extra"], r)
        d = pg.evaluate("(() => { const a = songNode, e = sincronoExtraAudio.players['%s'].node; return Math.abs((a.segP + (audioContext.currentTime - a.segT)) - (e.segP + (audioContext.currentTime - e.segT))); })()" % gid)
        check("os dois estão na mesma posição (dif. %.3f s)" % d, d < 0.05, d)
        pg.click('#tabs [data-go="mixer"]'); pg.fill("#extra-list input[type=range]", "30"); pg.wait_for_timeout(300)
        v = pg.evaluate("sincronoExtraAudio.players['%s'].vol.gain.value" % gid)
        check("o volume do piano acompanha o controle (%.2f)" % v, 0.2 < v < 0.5 + 0.5 and abs(v - 0.3) < 0.15, v)
        pg.click('#tabs [data-go="playlist"]'); pg.click("#stop-all"); pg.wait_for_timeout(2500)
        r = pg.evaluate("({main: !!songNode, extra: !!sincronoExtraAudio.players['%s'].node})" % gid)
        check("parar: os dois param", not r["main"] and not r["extra"], r)
        # desmarcar remove o tocador
        pg.click('#tabs [data-go="mixer"]'); pg.uncheck("#extra-list input[type=checkbox]"); pg.wait_for_timeout(300)
        check("desmarcar descarta o tocador do piano", pg.evaluate("!sincronoExtraAudio.players['%s']" % gid))
        # o aparelho não conta como 'pronto' no grupo extra (não se registra lá)
        check("sem erros de JavaScript", not erros, erros)
        b.close()
finally:
    proc.terminate()

print("\nFALHAS:", falhas) if falhas else print("\nTodos os testes passaram.")
sys.exit(1 if falhas else 0)
