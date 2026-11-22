#!/usr/bin/env python3
"""Repertório: salvar, carregar, trocar, mover, apagar e persistir (servidor real + Chromium).   python3 tools/teste_repertorio.py"""
import io, json, socket, subprocess, sys, tempfile, time, urllib.request, urllib.error, wave
from pathlib import Path
from playwright.sync_api import sync_playwright

RAIZ = Path(__file__).resolve().parent.parent
xmlA = (RAIZ / "docs/exemplos-partitura/completa.musicxml").read_bytes()
xmlB = (RAIZ / "docs/exemplos-partitura/orquestra.musicxml").read_bytes()
falhas = []
def check(n, ok, extra=""):
    print(("  ok  " if ok else " FALHA"), n, "" if ok else extra)
    if not ok: falhas.append(n)

CACHE = tempfile.mkdtemp()
def start():
    s = socket.socket(); s.bind(("127.0.0.1", 0)); port = s.getsockname()[1]; s.close()
    pr = subprocess.Popen([sys.executable, str(RAIZ / "servidor-pc/sincrono_server.py"), "--port", str(port), "--cache", CACHE, "--no-browser"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    for _ in range(60):
        try: socket.create_connection(("127.0.0.1", port), 0.2).close(); break
        except OSError: time.sleep(0.1)
    return pr, "http://127.0.0.1:%d" % port
def mk(base):
    def api(path, body=None, raw=None, headers=None):
        data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
        h = {"Content-Type": "application/json"}; h.update(headers or {})
        try:
            return json.loads(urllib.request.urlopen(urllib.request.Request(base + path, data=data, method="POST" if data is not None else "GET", headers=h), timeout=8).read() or b"{}")
        except urllib.error.HTTPError as e:
            r = json.loads(e.read() or b"{}"); r["_status"] = e.code; return r
    return api
def wav(sec, v=0):
    b = io.BytesIO(); w = wave.open(b, "wb"); w.setnchannels(1); w.setsampwidth(2); w.setframerate(8000); w.writeframes(bytes([v, 0]) * 8000 * sec); w.close(); return b.getvalue()

proc, BASE = start(); api = mk(BASE)
try:
    # ---- música A: partitura A, áudio no Geral e em "Violinos", violinos veem a parte 1, 90 bpm, marcadores
    api("/api/groups", {"op": "add", "name": "Violinos"})
    gids = {g["name"]: g["id"] for g in api("/api/groups")["groups"]}
    api("/api/score/upload?name=A.musicxml", raw=xmlA, headers={"Content-Type": "application/octet-stream"})
    api("/api/audio/upload?group=%s&name=geralA.wav" % gids["Geral"], raw=wav(5, 1), headers={"X-File-Type": "audio/wav"})
    api("/api/audio/upload?group=%s&name=violA.wav" % gids["Violinos"], raw=wav(6, 2), headers={"X-File-Type": "audio/wav"})
    api("/api/score/part", {"group": gids["Violinos"], "part": 1})
    api("/api/command", {"type": "settings", "bpm": 90, "beatsPerBar": 3})
    api("/api/score/marks", {"sig": "geralA.wav", "marks": [0, 1000, 2000]})
    check("sem música carregada, a configuração aparece como não guardada", api("/api/setlist").get("unsaved") is True and api("/api/setlist").get("dirty") is False, api("/api/setlist"))
    r = api("/api/setlist", {"op": "add", "name": "Música A"})
    check("depois de adicionar, nada pendente", api("/api/setlist").get("unsaved") is False and api("/api/setlist").get("dirty") is False, api("/api/setlist"))
    check("salvar música A", [m["name"] for m in r["songs"]] == ["Música A"] and r["songs"][0]["bpm"] == 90 and len(r["songs"][0]["audios"]) == 2, r)
    # ---- música B: outra partitura, só áudio no Geral, 140 bpm, sem partes
    api("/api/groups", {"op": "remove", "id": gids["Violinos"]}); api("/api/groups", {"op": "add", "name": "Violinos"})   # grupo novo, sem áudio
    gids = {g["name"]: g["id"] for g in api("/api/groups")["groups"]}
    api("/api/score/upload?name=B.musicxml", raw=xmlB, headers={"Content-Type": "application/octet-stream"})
    api("/api/audio/upload?group=%s&name=geralB.wav" % gids["Geral"], raw=wav(4, 3), headers={"X-File-Type": "audio/wav"})
    api("/api/command", {"type": "settings", "bpm": 140, "beatsPerBar": 4})
    r = api("/api/setlist", {"op": "add", "name": ""})
    check("nome vazio usa o nome da partitura", r["songs"][1]["name"] == "B.musicxml", r["songs"])
    idA, idB = r["songs"][0]["id"], r["songs"][1]["id"]
    # ---- carregar A
    r = api("/api/setlist", {"op": "apply", "id": idA})
    sc = api("/api/score")
    check("carregar A: partitura A", sc["file"]["name"] == "A.musicxml", sc["file"])
    check("carregar A: violinos voltam à parte 1", sc["parts"].get(gids["Violinos"]) == 1, sc["parts"])
    check("carregar A: marcadores voltam", "geralA.wav" in sc["marks"], sc["marks"])
    check("carregar A: áudio do Geral e dos Violinos", api("/api/audio?group=%s" % gids["Geral"])["file"]["name"] == "geralA.wav" and api("/api/audio?group=%s" % gids["Violinos"])["file"]["name"] == "violA.wav")
    st = api("/api/state"); check("carregar A: metrônomo 90 bpm, 3 tempos", st["bpm"] == 90 and st["beatsPerBar"] == 3, st)
    # ---- a configuração não some: mudanças na música carregada são guardadas sozinhas
    check("recém carregada: sem alterações pendentes", api("/api/setlist").get("dirty") is False, api("/api/setlist"))
    api("/api/command", {"type": "settings", "bpm": 77, "beatsPerBar": 3})
    check("mudar o andamento marca alterações não guardadas", api("/api/setlist").get("dirty") is True, api("/api/setlist"))
    t0 = time.time()
    while time.time() - t0 < 12 and api("/api/setlist").get("dirty"): time.sleep(0.5)
    check("o servidor guarda sozinho (%.1f s)" % (time.time() - t0), not api("/api/setlist").get("dirty") and api("/api/setlist")["songs"][0]["bpm"] == 77, api("/api/setlist"))
    api("/api/command", {"type": "settings", "bpm": 91, "beatsPerBar": 3})
    r = api("/api/setlist", {"op": "save"})
    check("Salvar agora guarda na hora", r.get("dirty") is False and r["songs"][0]["bpm"] == 91, r)
    # ---- carregar B: o áudio dos Violinos some (B não tem)
    api("/api/setlist", {"op": "apply", "id": idB})
    check("carregar B: partitura B e sem áudio nos Violinos", api("/api/score")["file"]["name"] == "B.musicxml" and not api("/api/audio?group=%s" % gids["Violinos"]).get("file"))
    check("carregar B: marcadores de A somem", api("/api/score")["marks"] == {})
    # ---- recusa com algo tocando
    api("/api/audio/cmd?group=all", {"type": "play", "startAt": time.time() * 1000 + 300}); time.sleep(0.8)
    r = api("/api/setlist", {"op": "apply", "id": idA})
    check("não troca de música enquanto toca (409)", r.get("_status") == 409 and api("/api/score")["file"]["name"] == "B.musicxml", r)
    api("/api/audio/cmd?group=all", {"type": "stop"}); time.sleep(1.6)
    # ---- próxima / anterior / mover / renomear
    check("anterior leva a A", (api("/api/setlist", {"op": "prev"}) or {}).get("current") == idA)
    check("anterior de A não existe", api("/api/setlist", {"op": "prev"}).get("_status") == 400)
    check("próxima leva a B", api("/api/setlist", {"op": "next"}).get("current") == idB)
    r = api("/api/setlist", {"op": "move", "id": idB, "dir": -1})
    check("mover B para cima", [m["id"] for m in r["songs"]] == [idB, idA], r)
    r = api("/api/setlist", {"op": "rename", "id": idA, "name": "Abertura"})
    check("renomear", r["songs"][1]["name"] == "Abertura")
    # ---- persistência: reinicia o servidor com a mesma pasta
    proc.terminate(); proc.wait(); proc, BASE = start(); api = mk(BASE)
    time.sleep(1.5)
    check("ao abrir o servidor volta a música que estava carregada", api("/api/score").get("file") and api("/api/state")["bpm"] in (90, 91, 140), (api("/api/score"), api("/api/state")))
    r = api("/api/setlist")
    check("depois de reiniciar o servidor o repertório continua", [m["name"] for m in r["songs"]] == ["B.musicxml", "Abertura"], r)
    r = api("/api/setlist", {"op": "apply", "id": idA})
    check("depois de reiniciar, carregar Abertura funciona", api("/api/score")["file"]["name"] == "A.musicxml" and api("/api/audio?group=g1")["file"]["name"] == "geralA.wav", api("/api/score"))
    g = {x["name"] for x in api("/api/groups")["groups"]}
    check("o grupo Violinos é recriado pelo nome", "Violinos" in g, g)
    r = api("/api/setlist", {"op": "remove", "id": idB})
    check("remover música", [m["id"] for m in r["songs"]] == [idA] and not (Path(CACHE) / "repertorio" / ("song-%d" % idB)).exists(), r)
    # ---- página
    with sync_playwright() as p:
        b = p.chromium.launch(); erros = []
        pg = b.new_page(viewport={"width": 520, "height": 900}); pg.on("pageerror", lambda e: erros.append(str(e))); pg.on("dialog", lambda d: d.accept())
        pg.goto(BASE + "/"); pg.wait_for_timeout(500)
        if pg.is_visible("#start-host"): pg.click("#start-host")
        check("a lista não fica mais na aba MÚSICA", pg.evaluate("!document.querySelector('.tab[data-tab=audio] #setlist')"))
        pg.click('#tabs [data-go="playlist"]'); pg.wait_for_selector("#rep-list .pl-row", timeout=6000)
        check("página lista a música", pg.locator("#rep-list .pl-row").count() == 1 and "Abertura" in pg.inner_text("#rep-list"))
        pg.fill("#rep-name", "Segunda"); pg.click("#rep-add"); pg.wait_for_timeout(800)
        check("página salva nova música", pg.locator("#rep-list .pl-row").count() == 2, pg.inner_text("#rep-msg"))
        pg.locator("#rep-list .pl-row").first.locator("[data-a=apply]").click(); pg.wait_for_timeout(800)
        check("página carrega a música", "arregada" in pg.inner_text("#rep-msg"), pg.inner_text("#rep-msg"))
        check("cartão mostra a música carregada", "CARREGADA" in pg.inner_text("#pl-label") and pg.inner_text("#pl-title") in ("Abertura", "Segunda"), pg.inner_text("#pl-title"))
        pg.locator("#rep-list .pl-row").first.locator("[data-a=more]").click()
        check("menu ⋯ abre as opções", pg.locator("#rep-list .pl-row").first.locator(".pl-menu").is_visible())
        pg.screenshot(path="/tmp/claude-0/-home-claude/c851e147-eb1f-58c7-8838-8cb9b59b2d38/scratchpad/dash/playlist.png")
        pg.wait_for_timeout(1300)
        check("botão Modo palco aparece na playlist (há partitura)", pg.is_visible("#pl-stage"))
        pg.click("#pl-stage"); pg.wait_for_timeout(900)
        check("modo palco abre em tela cheia com a partitura", pg.evaluate("document.getElementById('score').classList.contains('stage')") and pg.is_visible("#score-view svg"))
        pg.keyboard.press("Escape"); pg.wait_for_timeout(400)
        check("Esc sai do modo palco", not pg.evaluate("document.getElementById('score').classList.contains('stage')"))
        cl = b.new_page(viewport={"width": 420, "height": 800}); cl.goto(BASE + "/?guest=1"); cl.wait_for_timeout(1500)
        check("cliente não vê o repertório", not cl.is_visible("#setlist"))
        check("sem erros de JavaScript", not erros, erros)
        b.close()
finally:
    proc.terminate()
print("\nFALHAS:", falhas) if falhas else print("\nTodos os testes passaram.")
sys.exit(1 if falhas else 0)
