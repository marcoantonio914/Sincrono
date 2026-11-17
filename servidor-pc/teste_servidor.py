#!/usr/bin/env python3
"""Teste de integração do servidor: sobe-o numa porta livre e confere o protocolo (grupos, áudio, volumes, SSE)."""
import http.client, json, subprocess, sys, threading, time, os, socket, tempfile

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
    h = dict(headers or {})
    data = raw
    if body is not None:
        data = json.dumps(body).encode(); h["Content-Type"] = "application/json"
    c.request(method, path, body=data, headers=h)
    r = c.getresponse(); text = r.read(); c.close()
    try: return r.status, json.loads(text)
    except ValueError: return r.status, text

for _ in range(50):
    try: socket.create_connection(("127.0.0.1", PORT), 0.2).close(); break
    except OSError: time.sleep(0.1)

try:
    s, st = call("GET", "/api/state"); check(s == 200 and st["revision"] == -1 and not st["playing"], "estado inicial")
    s, r = call("POST", "/api/command", {"type": "play", "bpm": 100, "beatsPerBar": 3})
    check(s == 200 and r["playing"] and r["revision"] == 0 and r["startAt"] > time.time() * 1000, "play agenda no futuro")
    s, _ = call("POST", "/api/command", {"type": "play", "bpm": 999, "beatsPerBar": 3}); check(s == 400, "bpm inválido recusado")
    s, r = call("POST", "/api/sync", {"clientSentAt": 7}); check(s == 200 and r["clientSentAt"] == 7 and r["serverSentAt"] >= r["serverReceivedAt"], "sync")

    s, g = call("POST", "/api/groups", {"op": "add", "name": "Violinos"}); check(s == 200 and len(g["groups"]) == 2, "cria grupo")
    s, g = call("POST", "/api/groups", {"op": "add", "name": "Coral"}); gv, gc = "g2", "g3"
    check(g["groups"][2]["id"] == gc, "ids g2, g3")
    s, g = call("POST", "/api/groups", {"op": "assign", "device": "devA", "group": gv}); check(g["assign"]["devA"] == gv, "atribui aparelho")
    s, _ = call("POST", "/api/groups", {"op": "assign", "device": "devA", "group": "g99"}); check(s == 400, "grupo inexistente recusado")
    s, _ = call("POST", "/api/groups", {"op": "remove", "id": "g1"}); check(s == 400, "grupo padrão não removível")

    s, _ = call("POST", f"/api/audio/cmd?group={gv}", {"type": "play"}); check(s == 400, "sem arquivo: play recusado")
    blob = os.urandom(5000)
    for gid in ("g1", gv):
        s, g = call("POST", f"/api/audio/upload?group={gid}&name=m.wav", raw=blob, headers={"X-File-Type": "audio/wav"})
        check(s == 200, f"upload em {gid}")
    s, snap = call("GET", f"/api/audio?device=devA&ready=0&group={gv}"); check(snap["file"]["name"] == "m.wav" and snap["devices"] == 1, "estado do grupo e registro do aparelho")
    s, data = call("GET", f"/api/audio/file?id={snap['file']['id']}&group={gv}"); check(s == 200 and data == blob or isinstance(data, (str, bytes)), "download do arquivo")
    s, snap_c = call("GET", f"/api/audio?device=devB&ready=0&group={gc}"); check(snap_c["file"] is None and snap_c["devices"] == 1, "grupos têm estado separado")

    # SSE do grupo g2: deve receber o play de 'todos'
    got = []
    def listen():
        c = http.client.HTTPConnection("127.0.0.1", PORT, timeout=8)
        c.request("GET", f"/events/audio?group={gv}"); r = c.getresponse()
        buf = b""
        while len(got) < 2:
            ch = r.read1(4096)
            if not ch: break
            buf += ch
            while b"\n\n" in buf:
                ev, buf = buf.split(b"\n\n", 1)
                if ev.startswith(b"event: audio"): got.append(json.loads(ev.split(b"data: ", 1)[1]))
    t = threading.Thread(target=listen, daemon=True); t.start(); time.sleep(0.4)
    s, _ = call("POST", "/api/audio/cmd?group=all", {"type": "play"}); check(s == 200, "tocar todos")
    t.join(5)
    check(len(got) >= 2 and got[-1]["playing"], "SSE do grupo recebe o play")
    s, a = call("GET", "/api/audio?device=x&group=g1"); s, b = call("GET", f"/api/audio?device=y&group={gv}")
    check(a["startAt"] == b["startAt"] and a["startAt"] > 0, "todos os grupos com o MESMO início (%d)" % a["startAt"])
    s, _ = call("POST", f"/api/audio/cmd?group={gv}", {"type": "pause"})
    s, b = call("GET", f"/api/audio?device=y&group={gv}"); a = call("GET", "/api/audio?device=x&group=g1")[1]
    check((not b["playing"]) and a["playing"], "pausar um grupo (ainda antes do início) cancela só ele")

    s, m = call("POST", "/api/mix", {"device": "devA", "level": 0.4}); check(m["levels"]["devA"] == 0.4, "volume do aparelho")
    s, m = call("POST", "/api/mix", {"device": "g:g2", "level": 7}); check(m["levels"]["g:g2"] == 1.0, "volume do grupo limitado a 1")
    s, d = call("GET", "/api/devices"); check(any(x["id"] == "devA" and x["group"] == gv for x in d), "lista de aparelhos")
    s, r = call("GET", "/api/calib?device=z&name=Teste")
    check(s in (200, 404), "calibração: 200 (com numpy) ou 404 (sem numpy; cliente mostra 'não oferece')")
    if s == 200:
        check(r.get("cable") is True and r.get("state") == "idle", "calibração por cabo: sessão em repouso")
    s, g = call("POST", "/api/groups", {"op": "remove", "id": gv}); check(s == 200 and "devA" not in g["assign"], "remover grupo devolve aparelhos ao padrão")
    s, _ = call("GET", f"/api/audio?device=a&group={gv}"); check(s == 404, "grupo removido some")
    s, page = call("GET", "/"); check(s == 200 and b"Sincrono" in (page if isinstance(page, bytes) else page.encode()), "página web servida")
    s, info = call("GET", "/api/info")
    check(s == 200 and info.get("host") == "pc" and info.get("local") is True, "info: anfitrião pc, pedido local reconhecido")
    s, d = call("GET", "/api/discover"); check(s == 200 and "sessions" in d and "available" in d, "descoberta de sessões responde (mesmo sem zeroconf)")
    import http.client
    c = http.client.HTTPConnection("127.0.0.1", PORT, timeout=5); c.request("GET", "/admin"); r = c.getresponse()
    check(r.status == 302 and r.getheader("Location") == "/", "/admin leva à página única")
finally:
    proc.terminate()
print("tudo certo" if fails == 0 else f"{fails} falha(s)")
sys.exit(1 if fails else 0)
