#!/usr/bin/env python3
"""Testa os endpoints de partitura do servidor (uma por sessão, parte por grupo)."""
import http.client, json, os, socket, subprocess, sys, tempfile, time
from pathlib import Path

def free_port():
    s = socket.socket(); s.bind(("127.0.0.1", 0)); p = s.getsockname()[1]; s.close(); return p

PORT = free_port()
cache = tempfile.mkdtemp()
proc = subprocess.Popen([sys.executable, os.path.join(os.path.dirname(os.path.abspath(__file__)), "sincrono_server.py"), "--port", str(PORT), "--cache", cache],
                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
fails = 0
def check(ok, msg):
    global fails
    print(("OK    " if ok else "FALHA ") + msg)
    if not ok: fails += 1

def call(method, path, body=None, raw=None, headers=None):
    c = http.client.HTTPConnection("127.0.0.1", PORT, timeout=5)
    h = dict(headers or {}); data = raw
    if body is not None:
        data = json.dumps(body).encode(); h["Content-Type"] = "application/json"
    c.request(method, path, body=data, headers=h)
    r = c.getresponse(); text = r.read(); c.close()
    try: return r.status, json.loads(text)
    except ValueError: return r.status, text

for _ in range(50):
    try: socket.create_connection(("127.0.0.1", PORT), 0.2).close(); break
    except OSError: time.sleep(0.1)
xml = (Path(__file__).resolve().parent.parent / "docs/exemplos-partitura/completa.musicxml").read_bytes()
try:
    s, r = call("GET", "/api/score"); check(s == 200 and r["file"] is None and r["parts"] == {}, "começa sem partitura")
    s, r = call("POST", "/api/score/part", {"group": "g1", "part": 0}); check(s == 400, "escolher parte sem partitura é recusado")
    s, r = call("POST", "/api/score/upload?name=completa.musicxml", raw=xml)
    check(s == 200 and r["file"]["name"] == "completa.musicxml" and r["file"]["size"] == len(xml), "importa a partitura")
    fid = r["file"]["id"]; rev1 = r["rev"]
    s, data = call("GET", "/api/score/file?id=%d" % fid)
    check(s == 200 and (data if isinstance(data, bytes) else json.dumps(data).encode()).startswith(b"<?xml") or s == 200, "baixa o arquivo")
    c = http.client.HTTPConnection("127.0.0.1", PORT); c.request("GET", "/api/score/file?id=%d" % fid); raw = c.getresponse().read(); c.close()
    check(raw == xml, "arquivo baixado é idêntico ao enviado")
    s, _ = call("GET", "/api/score/file?id=999"); check(s == 404, "id inexistente dá 404")
    s, g = call("POST", "/api/groups", {"op": "add", "name": "Violoncelos"}); gid = g["groups"][1]["id"]
    s, r = call("POST", "/api/score/part", {"group": gid, "part": 1}); check(s == 200 and r["parts"] == {gid: 1} and r["rev"] > rev1, "grupo escolhe a parte 1")
    s, r = call("POST", "/api/score/part", {"group": "g1", "part": 2}); check(r["parts"] == {gid: 1, "g1": 2}, "outro grupo escolhe outra parte")
    s, _ = call("POST", "/api/score/part", {"group": "nao", "part": 0}); check(s == 400, "grupo inexistente recusado")
    s, _ = call("POST", "/api/score/part", {"group": "g1", "part": -1}); check(s == 400, "parte negativa recusada")
    s, r = call("POST", "/api/groups", {"op": "remove", "id": gid}); s, r = call("GET", "/api/score"); check(r["parts"] == {"g1": 2}, "remover grupo limpa a parte")
    s, r = call("POST", "/api/score/upload?name=outra.musicxml", raw=xml); check(r["file"]["id"] != fid and r["parts"] == {}, "nova partitura zera as escolhas")
    s, _ = call("GET", "/api/score/file?id=%d" % fid); check(s == 404, "arquivo antigo some")
    s, r = call("POST", "/api/score/upload?name=x", raw=b""); check(s == 400, "arquivo vazio recusado")
    s, r = call("POST", "/api/score/clear", {}); check(s == 200 and r["file"] is None, "remover partitura")
finally:
    proc.terminate()
print("\n%s" % ("TUDO CERTO" if not fails else "%d FALHA(S)" % fails))
sys.exit(1 if fails else 0)
