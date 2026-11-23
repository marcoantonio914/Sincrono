#!/usr/bin/env python3
"""v29: cor escolhida por grupo e arquivo .sincro (música ou playlist inteira).   python3 tools/teste_sincro.py"""
import io, json, socket, subprocess, sys, tempfile, time, urllib.request, urllib.error, zipfile
from pathlib import Path
RAIZ = Path(__file__).resolve().parent.parent
falhas = []
def check(n, ok, extra=""):
    print(("  ok  " if ok else " FALHA"), n, "" if ok else extra)
    if not ok: falhas.append(n)

class Srv:
    def __init__(self):
        s = socket.socket(); s.bind(("127.0.0.1", 0)); self.port = s.getsockname()[1]; s.close()
        self.p = subprocess.Popen([sys.executable, str(RAIZ / "servidor-pc/sincrono_server.py"), "--port", str(self.port), "--cache", tempfile.mkdtemp(), "--no-browser"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        for _ in range(80):
            try: socket.create_connection(("127.0.0.1", self.port), 0.2).close(); break
            except OSError: time.sleep(0.1)
        self.B = "http://127.0.0.1:%d" % self.port
    def api(self, path, body=None, raw=None, raw_resp=False):
        d = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
        req = urllib.request.Request(self.B + path, data=d, method="POST" if d is not None else "GET", headers={"Content-Type": "application/json"})
        try:
            r = urllib.request.urlopen(req, timeout=20); data = r.read()
            return (r.status, data) if raw_resp else json.loads(data or b"{}")
        except urllib.error.HTTPError as e:
            data = e.read(); return (e.code, data) if raw_resp else {"_status": e.code, **json.loads(data or b"{}")}
    def stop(self): self.p.terminate()

score = (RAIZ / "docs/exemplos-partitura/completa.musicxml").read_bytes()
A, Bs = Srv(), Srv()
try:
    # ---- cor escolhida
    A.api("/api/groups", {"op": "add", "name": "Soprano"}); A.api("/api/groups", {"op": "add", "name": "Baixo"})
    g = {x["name"]: x for x in A.api("/api/groups")["groups"]}
    check("cor padrão vem na lista de grupos", g["Soprano"]["color"] == "#1d4ed8" and g["Geral"]["color"] == "#475569", g)
    r = A.api("/api/groups", {"op": "color", "id": g["Soprano"]["id"], "color": "#E11D48"})
    check("anfitrião escolhe a cor do grupo", {x["name"]: x for x in r["groups"]}["Soprano"]["color"] == "#e11d48", r)
    bad = [A.api("/api/groups", {"op": "color", "id": g["Soprano"]["id"], "color": c}) for c in ("vermelho", "#12", "#GGGGGG", "javascript:1")] + [A.api("/api/groups", {"op": "color", "id": "g99", "color": "#112233"})]
    check("cor inválida ou grupo inexistente é recusado", all(b.get("_status") == 400 for b in bad), bad)
    # ---- duas músicas
    A.api("/api/score/upload?name=a.musicxml", raw=score)
    A.api("/api/audio/upload?group=%s&name=sop.flac" % g["Soprano"]["id"], raw=b"FLAC-SOPRANO" * 500)
    A.api("/api/audio/upload?group=%s&name=baixo.flac" % g["Baixo"]["id"], raw=b"FLAC-BAIXO" * 700)
    A.api("/api/score/part", {"group": g["Soprano"]["id"], "part": 0}); A.api("/api/score/part", {"group": g["Baixo"]["id"], "part": 0})
    A.api("/api/setlist", {"op": "add", "name": "Antífona"})
    A.api("/api/setlist", {"op": "new"})
    A.api("/api/score/upload?name=b.musicxml", raw=score)
    A.api("/api/setlist", {"op": "add", "name": "Segunda"})
    lst = A.api("/api/setlist"); check("duas músicas na playlist", len(lst["songs"]) == 2, lst)
    # ---- exportar
    st, tudo = A.api("/api/setlist/export", raw_resp=True)
    z = zipfile.ZipFile(io.BytesIO(tudo)); man = json.loads(z.read("sincro.json"))
    check("exporta a playlist inteira (2 músicas)", st == 200 and [s["name"] for s in man["songs"]] == ["Antífona", "Segunda"], man)
    check("zip contém partitura e áudios de cada música", "song-1/score.bin" in z.namelist() and "song-1/audio-1.bin" in z.namelist() and "song-1/audio-2.bin" in z.namelist() and "song-2/score.bin" in z.namelist(), z.namelist())
    check("cor do grupo vai no arquivo", man["songs"][0]["colors"].get("Soprano") == "#e11d48", man["songs"][0].get("colors"))
    st, uma = A.api("/api/setlist/export?ids=%d&name=So%%20a%%20Antifona" % lst["songs"][0]["id"], raw_resp=True)
    check("exporta só uma música", len(json.loads(zipfile.ZipFile(io.BytesIO(uma)).read("sincro.json"))["songs"]) == 1)
    # ---- importar em outro servidor
    r = Bs.api("/api/setlist/import?mode=add", raw=tudo)
    check("importa a playlist inteira (juntar)", r.get("added") == 2 and len(r["songs"]) == 2, r)
    r = Bs.api("/api/setlist/import?mode=add", raw=uma)
    check("juntar acrescenta no fim sem apagar", len(r["songs"]) == 3 and r["songs"][2]["name"] == "Antífona", r)
    Bs.api("/api/setlist", {"op": "apply", "id": r["songs"][0]["id"]})
    gs = {x["name"]: x for x in Bs.api("/api/groups")["groups"]}
    check("abrir recria os grupos com as cores salvas", gs.get("Soprano", {}).get("color") == "#e11d48" and "Baixo" in gs, gs)
    check("abrir carrega os áudios dos grupos", gs["Soprano"]["file"] == "sop.flac" and gs["Baixo"]["file"] == "baixo.flac", gs)
    sc = Bs.api("/api/score"); check("abrir carrega a partitura e as partes", bool(sc.get("file")) and sc.get("parts", {}).get(gs["Soprano"]["id"]) == 0, str(sc)[:200])
    r = Bs.api("/api/setlist/import?mode=replace", raw=tudo)
    check("substituir troca a playlist toda", len(r["songs"]) == 2 and r["current"] is None, r)
    # ---- segurança
    def zip_com(nomes, man=None):
        b = io.BytesIO()
        with zipfile.ZipFile(b, "w") as zz:
            zz.writestr("sincro.json", json.dumps(man or {"format": "sincro", "version": 1, "songs": []}))
            for n in nomes: zz.writestr(n, b"x")
        return b.getvalue()
    casos = {"caminho com ../": zip_com(["../fora.bin"]), "caminho absoluto": zip_com(["/etc/x.bin"]), "nome desconhecido": zip_com(["song-1/outro.txt"]),
             "não é zip": b"isto nao e zip" * 10, "sem manifesto": b"PK\x05\x06" + b"\0" * 18, "versão futura": zip_com([], {"format": "sincro", "version": 9, "songs": []}),
             "formato errado": zip_com([], {"format": "outro", "version": 1, "songs": []})}
    for nome, dados in casos.items():
        r = Bs.api("/api/setlist/import?mode=add", raw=dados)
        check("recusa: " + nome, r.get("_status") == 400, r)
    n_antes = len(Bs.api("/api/setlist")["songs"])
    r = Bs.api("/api/setlist/import?mode=add", raw=zip_com(["song-1/score.bin"], {"format": "sincro", "version": 1, "songs": [{"name": "../../x\x00y", "score": {"name": "../p"}, "audio": [{"group": "../g", "file": "../../a.bin", "name": "n"}], "parts": {"a": "zz"}, "bpm": "abc", "colors": {"A": "red"}}]}))
    nm = r["songs"][-1]["name"] if r.get("songs") else ""
    check("nomes perigosos são limpos e dados estranhos ignorados", r.get("added") == 1 and "/" not in nm and "\x00" not in nm and r["songs"][-1]["audios"] == [], r)
    # ---- substituir recusa com algo tocando
    Bs.api("/api/command", {"type": "start"}) if False else None
finally:
    A.stop(); Bs.stop()
print("FALHAS:", falhas if falhas else "nenhuma"); sys.exit(1 if falhas else 0)
