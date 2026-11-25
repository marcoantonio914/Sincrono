#!/usr/bin/env python3
"""
Servidor do Sincrono para rodar no COMPUTADOR (o computador como anfitrião).

    python3 sincrono_server.py            # porta 3000
    python3 sincrono_server.py --port 3000 --web ../app/src/main/assets/web

Fala o mesmo protocolo do anfitrião do app Android (estado/relógio/comandos, áudio por grupo,
volumes), então os apps e a página web entram nele como em qualquer anfitrião. Só usa a
biblioteca padrão do Python (3.8+). Se o pacote `zeroconf` estiver instalado
(pip install zeroconf), o servidor também se anuncia na rede e aparece na lista "Sessões
encontradas" do app.

Tudo fica numa página só (http://localhost:3000): ao abrir neste computador ela pergunta se você quer
"Ser anfitrião" (grupos, arquivos, volumes, calibração, QR code) ou "Entrar em uma sessão" (como no app).

Limitações desta versão (honestas):
  * Calibração por cabo, pela mesa de som (veja calibracao_cabo.py; precisa de numpy e sounddevice).
    Sem elas, use o ajuste fino manual. Não testada com mesa de verdade, só com gravação simulada.
  * O servidor em si não toca som. O computador toca pela PÁGINA aberta no navegador dele
    (o servidor abre http://localhost:PORTA/ sozinho em Chrome/Edge com o som liberado; em outro navegador, clique uma vez em "Ativar som").
"""

import argparse
import tempfile
import zipfile
import json
import os
import queue
import re
import shutil
import socket
import sys
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

try:
    import calibracao_cabo
except Exception:  # sem o módulo ou sem numpy: o servidor funciona, só sem calibração por cabo
    calibracao_cabo = None
CABLE = None

LEAD_MS = 3000          # antecedência do início do metrônomo
AUDIO_LEAD_MS = 2500.0  # antecedência do início da música


def _wanted_start(body, now, default, past_ms=500.0):
    """Instante de início pedido pelo anfitrião (startAt, ms do servidor), para o metrônomo e a música
    começarem JUNTOS. Só vale se estiver entre 0,5 s atrás e 15 s à frente; senão usa o padrão."""
    try:
        v = float(body.get("startAt"))
    except (TypeError, ValueError):
        return default
    return v if now - past_ms <= v <= now + 15000 else default
AUDIO_STOP_MS = 1200.0  # antecedência da parada agendada
PEER_TTL_MS = 6000.0
MAX_GROUPS = 16
MAX_IMPORT = 2_000_000_000   # arquivo .sincro inteiro
GROUP_COLORS = ["#475569", "#1d4ed8", "#b45309", "#0f766e", "#7c3aed", "#be123c", "#15803d", "#be185d", "#0369a1", "#a16207"]
COLOR_RE = re.compile(r"^#[0-9a-fA-F]{6}$")


def default_color(gid):
    # igual a web/groupcolor.js: vem do número do id (g1, g2…); Geral = cinza
    try:
        n = int(re.sub(r"\D", "", str(gid)) or 1)
    except ValueError:
        n = 1
    return GROUP_COLORS[(n - 1) % len(GROUP_COLORS)]
DEFAULT_GROUP = "g1"
MAX_UPLOAD = 300_000_000
MAX_SCORE = 30_000_000   # partituras (MusicXML/MXL) são pequenas

_EPOCH_MS = time.time() * 1000.0
_T0 = time.perf_counter()


# Identifica esta execução do servidor: se o anfitrião reiniciar, os clientes veem outra "época" e aceitam o estado novo
# mesmo que a revisão recomece em número menor (senão ignorariam tudo como "antigo").
EPOCH = int(time.time() * 1000)


def now_ms():
    """Relógio do servidor: base em ms 'de parede' + avanço monotônico com fração de ms."""
    return _EPOCH_MS + (time.perf_counter() - _T0) * 1000.0


def clamp(v, lo, hi):
    return lo if v < lo else hi if v > hi else v


class Hub:
    """Estado do áudio de UM grupo: arquivo e função (play/pause/posição) do relógio do servidor."""

    def __init__(self, on_change):
        self.lock = threading.RLock()
        self.on_change = on_change
        self.file = None            # dict(id, name, path, size, mime)
        self.next_file_id = 1
        self.revision = 0
        self.playing = False
        self.start_at = 0.0
        self.position_ms = 0.0
        self.stop_at = None
        self.reset_on_stop = False  # a parada agendada é um "parar" (volta ao início)
        self.peers = {}             # device -> (lastSeen, readyId)

    def set_file(self, path, name, mime, size):
        with self.lock:
            old = self.file
            self.file = dict(id=self.next_file_id, name=name, path=path, size=size, mime=mime)
            self.next_file_id += 1
            self.playing = False
            self.position_ms = 0.0
            self.stop_at = None
            self.reset_on_stop = False
            self.revision += 1
            if old:
                try:
                    os.remove(old["path"])
                except OSError:
                    pass
        self._notify()

    def clear(self):
        with self.lock:
            if self.file:
                try:
                    os.remove(self.file["path"])
                except OSError:
                    pass
            self.file = None
            self.playing = False
            self.position_ms = 0.0
            self.stop_at = None
            self.reset_on_stop = False
            self.revision += 1
        self._notify()

    def _notify(self):
        if self.on_change:
            self.on_change(self.snapshot())

    def _normalize(self):
        if self.playing and self.stop_at is not None and now_ms() > self.stop_at + 300:
            self.position_ms = 0.0 if self.reset_on_stop else self.position_ms + max(0.0, self.stop_at - self.start_at)
            self.playing = False
            self.stop_at = None
            self.reset_on_stop = False
            self.revision += 1

    def _position_at(self, t):
        if not self.playing or t <= self.start_at:
            return self.position_ms
        if self.stop_at is not None and t >= self.stop_at:
            return 0.0 if self.reset_on_stop else self.position_ms + max(0.0, self.stop_at - self.start_at)
        return self.position_ms + (t - self.start_at)

    def has_file(self):
        with self.lock:
            return self.file is not None

    def is_playing(self):
        with self.lock:
            self._normalize()
            return self.playing and self.stop_at is None

    def file_name(self):
        with self.lock:
            return self.file["name"] if self.file else None

    def current_file(self, fid):
        with self.lock:
            return self.file if self.file and self.file["id"] == fid else None

    def register(self, device, ready):
        if device:
            with self.lock:
                self.peers[device] = (now_ms(), ready)

    def snapshot(self):
        with self.lock:
            self._normalize()
            now = now_ms()
            live = [p for p in self.peers.values() if now - p[0] < PEER_TTL_MS]
            f = self.file
            ready = 0 if f is None else sum(1 for p in live if p[1] == f["id"])
            return json.dumps({
                "revision": self.revision,
                "serverNow": int(now),
                "file": None if f is None else {"id": f["id"], "name": f["name"], "size": f["size"], "mime": f["mime"]},
                "playing": self.playing,
                "startAt": int(self.start_at),
                "positionMs": self.position_ms,
                "stopAt": None if self.stop_at is None else int(self.stop_at),
                "resetOnStop": self.reset_on_stop,
                "ready": ready,
                "devices": len(live),
                "epoch": EPOCH,
            })

    def command(self, body, at=None):
        """Devolve (status, json). [at]: 'agora' fixo, para vários grupos começarem no MESMO instante."""
        with self.lock:
            self._normalize()
            if self.file is None:
                return 400, {"error": "Nenhum arquivo de áudio foi escolhido."}
            now = now_ms() if at is None else at
            requested = body.get("positionMs")
            kind = body.get("type")
            if kind == "play":
                if not (self.playing and self.stop_at is None):
                    start_from = self._position_at(now) if requested is None else requested
                    self.start_at = _wanted_start(body, now, now + AUDIO_LEAD_MS)
                    self.position_ms = max(0.0, float(start_from))
                    self.stop_at = None
                    self.reset_on_stop = False
                    self.playing = True
                    self.revision += 1
            elif kind == "pause":
                if self.playing and self.stop_at is None:
                    if now < self.start_at:
                        self.playing = False
                    else:
                        self.stop_at = now + AUDIO_STOP_MS
                    self.revision += 1
            elif kind == "seek":
                target = max(0.0, float(requested or 0.0))
                self.reset_on_stop = False
                if self.playing:
                    self.start_at = _wanted_start(body, now, now + AUDIO_LEAD_MS)
                    self.position_ms = target
                    self.stop_at = None
                else:
                    self.position_ms = target
                self.revision += 1
            elif kind == "stop":
                # Parar = pausar num instante combinado (todos param juntos) e voltar ao início.
                if self.playing and self.stop_at is None:
                    if now < self.start_at:
                        self.playing = False
                        self.position_ms = 0.0
                    else:
                        self.stop_at = now + AUDIO_STOP_MS
                        self.reset_on_stop = True
                elif self.playing:
                    self.reset_on_stop = True
                else:
                    self.position_ms = 0.0
                self.revision += 1
            else:
                return 400, {"error": "Comando inválido."}
        self._notify()
        return 200, json.loads(self.snapshot())


class Session:
    """Tudo o que o servidor guarda: metrônomo, grupos, áudio por grupo, volumes e aparelhos vistos."""

    def __init__(self, cache_dir):
        self.lock = threading.RLock()
        self.cache_dir = Path(cache_dir)
        self.cache_dir.mkdir(parents=True, exist_ok=True)
        self.state = {"bpm": 120.0, "beatsPerBar": 4, "playing": False, "startAt": None, "revision": -1, "epoch": EPOCH, "silent": False}
        self.state_clients = []          # filas de eventos do metrônomo
        self.audio_clients = []          # (grupo, fila)
        self.group_names = {DEFAULT_GROUP: "Geral"}
        self.group_colors = {}           # cor escolhida pelo anfitrião (id -> #rrggbb); sem escolha vale a cor padrão
        self.group_next = 2
        self.assign = {}
        self.group_rev = 1
        self.hubs = {}
        self.mix = {}
        self.mix_rev = 1
        self.score = None                # partitura da sessão: dict(id, name, path, size)
        self.score_next = 1
        self.score_rev = 1
        self.score_parts = {}            # grupo -> índice da parte que o grupo vê
        self.score_pos = None            # posição (ms da partitura) escolhida pelo anfitrião na linha do tempo; None = nenhuma
        self.score_marks = {}            # 'nome:tamanho' do áudio -> {'marks': [ms de cada compasso tocado], 'offset': ms}
        self.devices = {}                # id -> dict(name, seen)
        self.rep_dir = self.cache_dir / "repertorio"      # repertório: músicas preparadas (arquivos copiados + índice)
        self.rep_dir.mkdir(parents=True, exist_ok=True)
        self.rep = {"songs": [], "next": 1, "current": None}
        try:
            self.rep = json.loads((self.rep_dir / "repertorio.json").read_text("utf-8"))
        except (OSError, ValueError):
            pass
        self.rep_seq = 0

    # ---- metrônomo
    def command(self, body):
        kind = body.get("type")
        if kind not in ("play", "pause", "restart", "settings"):
            return 400, {"error": "Comando inválido."}
        bpm = body.get("bpm")
        beats = body.get("beatsPerBar")
        if kind != "pause":
            try:
                ok = 30 <= float(bpm) <= 300 and 1 <= int(beats) <= 12
            except (TypeError, ValueError):
                ok = False
            if not ok:
                return 400, {"error": "BPM ou compasso inválido."}
        with self.lock:
            cur = self.state
            start_at = int(_wanted_start(body, now_ms(), now_ms() + LEAD_MS, past_ms=3600000.0))
            nxt = dict(cur)
            if kind == "pause":
                nxt.update(playing=False, startAt=start_at)
            elif kind == "settings":
                # tempo ao vivo: com o metrônomo tocando, mantém a batida e a posição na música (só muda a velocidade)
                new_bpm = float(bpm)
                keep = cur["startAt"]
                if cur["playing"] and cur["startAt"] is not None and cur["bpm"]:
                    now = now_ms()
                    keep = int(round(now - (now - cur["startAt"]) * float(cur["bpm"]) / new_bpm))
                nxt.update(bpm=new_bpm, beatsPerBar=int(beats), startAt=keep)
            else:
                nxt.update(bpm=float(bpm), beatsPerBar=int(beats), playing=True, startAt=start_at)
            if kind != "pause" and "silent" in body:
                nxt["silent"] = bool(body["silent"])        # relógio mudo: o cursor da partitura anda, mas sem clique
            nxt["revision"] = cur["revision"] + 1
            self.state = nxt
            clients = list(self.state_clients)
        text = json.dumps(nxt)
        for q in clients:
            q.put(f"event: state\ndata: {text}\n\n")
        return 200, nxt

    # ---- grupos
    def hub(self, gid):
        with self.lock:
            if gid not in self.group_names:
                return None
            h = self.hubs.get(gid)
            if h is None:
                h = Hub(lambda snap, gid=gid: self._broadcast_audio(gid, snap))
                self.hubs[gid] = h
            return h

    def _broadcast_audio(self, gid, snap):
        with self.lock:
            clients = [q for g, q in self.audio_clients if g == gid]
        for q in clients:
            q.put(f"event: audio\ndata: {snap}\n\n")

    # ---- partitura (uma por sessão; cada grupo escolhe a sua parte)
    def score_json(self):
        with self.lock:
            f = self.score
            return {"rev": self.score_rev,
                    "file": None if f is None else {"id": f["id"], "name": f["name"], "size": f["size"]},
                    "parts": dict(self.score_parts), "pos": self.score_pos, "marks": dict(self.score_marks)}

    def score_set(self, path, name, size):
        with self.lock:
            old = self.score
            self.score = dict(id=self.score_next, name=name, path=path, size=size)
            self.score_next += 1
            self.score_parts = {}
            self.score_pos = None
            self.score_marks = {}        # os marcadores valem para a partitura anterior
            self.score_rev += 1
        if old:
            try:
                os.remove(old["path"])
            except OSError:
                pass

    def score_clear(self):
        with self.lock:
            old, self.score = self.score, None
            self.score_parts = {}
            self.score_pos = None
            self.score_marks = {}
            self.score_rev += 1
        if old:
            try:
                os.remove(old["path"])
            except OSError:
                pass

    def score_part(self, gid, part):
        with self.lock:
            if gid not in self.group_names or self.score is None:
                return False
            try:
                part = int(part)
            except (TypeError, ValueError):
                return False
            if not 0 <= part < 64:
                return False
            self.score_parts[gid] = part
            self.score_rev += 1
        return True

    def score_file(self, fid):
        with self.lock:
            return self.score if self.score and self.score["id"] == fid else None

    def groups_json(self):
        with self.lock:
            groups = []
            for gid, name in self.group_names.items():
                h = self.hubs.get(gid)
                groups.append({"id": gid, "name": name, "file": h.file_name() if h else None,
                               "playing": h.is_playing() if h else False,
                               "color": self.group_colors.get(gid) or default_color(gid)})
            return {"rev": self.group_rev, "groups": groups, "assign": dict(self.assign)}

    def group_op(self, body):
        op = body.get("op")
        with self.lock:
            if op == "add":
                name = re.sub(r"[\x00-\x1f]", "", str(body.get("name", ""))).strip()[:40]
                if not name or len(self.group_names) >= MAX_GROUPS:
                    return False
                self.group_names["g%d" % self.group_next] = name
                self.group_next += 1
            elif op == "rename":
                name = re.sub(r"[\x00-\x1f]", "", str(body.get("name", ""))).strip()[:40]
                gid = body.get("id")
                if not name or gid not in self.group_names:
                    return False
                self.group_names[gid] = name
            elif op == "color":
                gid, col = body.get("id"), body.get("color")
                if gid not in self.group_names or not isinstance(col, str) or not COLOR_RE.match(col):
                    return False
                self.group_colors[gid] = col.lower()
            elif op == "remove":
                gid = body.get("id")
                if gid == DEFAULT_GROUP or gid not in self.group_names:
                    return False
                del self.group_names[gid]
                self.group_colors.pop(gid, None)
                self.score_parts.pop(gid, None)
                self.assign = {d: g for d, g in self.assign.items() if g != gid}
                h = self.hubs.pop(gid, None)
                if h:
                    h.on_change = None
                    h.clear()
                self.audio_clients = [(g, q) for g, q in self.audio_clients if g != gid or q.put(None)]
            elif op == "assign":
                dev, gid = body.get("device"), body.get("group")
                if not dev or len(dev) > 80 or gid not in self.group_names:
                    return False
                if gid == DEFAULT_GROUP:
                    self.assign.pop(dev, None)
                else:
                    self.assign[dev] = gid
            else:
                return False
            self.group_rev += 1
        return True

    def group_of(self, device):
        with self.lock:
            return self.assign.get(device, DEFAULT_GROUP)

    def audio_all(self, body):
        at = now_ms()
        ok = 0
        last = None
        for gid in list(self.group_names):
            h = self.hub(gid)
            if h and h.has_file():
                status, reply = h.command(body, at)
                if status == 200:
                    ok += 1
                else:
                    last = (status, reply)
        if ok == 0:
            return last or (400, {"error": "Nenhum grupo tem arquivo de áudio."})
        return 200, self.groups_json()

    # ---- repertório: cada música guarda partitura, áudio por grupo, parte de cada grupo, andamento e marcadores
    def _rep_save(self):
        tmp = self.rep_dir / "repertorio.json.tmp"
        tmp.write_text(json.dumps(self.rep, ensure_ascii=False), "utf-8")
        os.replace(tmp, self.rep_dir / "repertorio.json")

    def _fresh(self, prefix):
        self.rep_seq += 1
        return self.cache_dir / ("%s-%d-%d.bin" % (prefix, int(time.time() * 1000), self.rep_seq))

    def _rep_fp(self, score, audio, parts, bpm, beats, marks, colors):
        return json.dumps({"score": score, "audio": sorted(audio), "parts": sorted(parts), "bpm": bpm, "beats": beats, "marks": marks, "colors": sorted(colors)}, sort_keys=True)

    def _live_colors(self):
        return [[n, self.group_colors.get(g) or default_color(g)] for g, n in self.group_names.items()]

    def _rep_fp_live(self):
        sc = self.score
        return self._rep_fp(
            [sc["name"], sc["size"]] if sc else None,
            [[self.group_names[g], h.file["name"], h.file["size"]] for g, h in self.hubs.items() if h.file and g in self.group_names],
            [[self.group_names[g], i] for g, i in self.score_parts.items() if g in self.group_names],
            self.state["bpm"], self.state["beatsPerBar"], self.score_marks, self._live_colors())

    def _rep_fp_song(self, m):
        sc = m.get("score")
        return self._rep_fp(
            [sc["name"], sc["size"]] if sc else None,
            [[a["group"], a["name"], a["size"]] for a in m.get("audio", [])],
            [[g, i] for g, i in m.get("parts", {}).items()],
            m.get("bpm"), m.get("beats"), m.get("marks", {}),
            [[n, c] for n, c in m["colors"].items()] if "colors" in m else self._live_colors())

    def _rep_has_config(self):
        return bool(self.score) or any(h.has_file() for h in self.hubs.values())

    def rep_autosave(self, force=False):
        # a música carregada guarda sozinha o que foi mudado na configuração (nada tocando; ignora sessão vazia)
        with self.lock:
            m = self._rep_song(self.rep.get("current"))
            if m is None or not self._rep_has_config():
                return False
            if self.state.get("playing") or any(h.is_playing() for h in self.hubs.values()):
                return False
            if self._rep_fp_live() == self._rep_fp_song(m):
                return False
            self._rep_snapshot(m)
            self._rep_save()
            return True

    def rep_restore(self):
        # ao abrir o servidor: volta a música que estava carregada
        with self.lock:
            m = self._rep_song(self.rep.get("current"))
            if m is not None and not self._rep_has_config():
                try:
                    self._rep_apply(m)
                except Exception as e:  # um arquivo sumido não pode impedir o servidor de abrir
                    print("Repertório: não consegui restaurar a última música:", e)

    def rep_json(self):
        with self.lock:
            songs = []
            for m in self.rep["songs"]:
                songs.append({"id": m["id"], "name": m["name"], "score": (m.get("score") or {}).get("name"),
                              "audios": [{"group": a["group"], "name": a["name"]} for a in m.get("audio", [])],
                              "bpm": m.get("bpm"), "beats": m.get("beats"), "marks": len(m.get("marks", {}))})
            cur = self._rep_song(self.rep.get("current"))
            dirty = bool(cur) and self._rep_has_config() and self._rep_fp_live() != self._rep_fp_song(cur)
            return {"songs": songs, "current": self.rep.get("current"), "dirty": dirty, "unsaved": cur is None and self._rep_has_config()}

    def _rep_song(self, sid):
        for m in self.rep["songs"]:
            if m["id"] == sid:
                return m
        return None

    def _rep_snapshot(self, m):
        # copia a configuração atual da sessão para a música m (substitui o que ela tinha)
        d = self.rep_dir / ("song-%d" % m["id"])
        if d.exists():
            shutil.rmtree(d, ignore_errors=True)
        d.mkdir(parents=True)
        m["score"] = None
        if self.score:
            shutil.copyfile(self.score["path"], d / "score.bin")
            m["score"] = {"file": "score.bin", "name": self.score["name"], "size": self.score["size"]}
        m["audio"] = []
        for gid, name in self.group_names.items():
            h = self.hubs.get(gid)
            f = h.file if h else None
            if f:
                fn = "audio-%d.bin" % (len(m["audio"]) + 1)
                shutil.copyfile(f["path"], d / fn)
                m["audio"].append({"group": name, "file": fn, "name": f["name"], "mime": f["mime"], "size": f["size"]})
        m["parts"] = {self.group_names[g]: i for g, i in self.score_parts.items() if g in self.group_names}
        m["bpm"] = self.state["bpm"]
        m["beats"] = self.state["beatsPerBar"]
        m["marks"] = json.loads(json.dumps(self.score_marks))
        m["colors"] = {n: (self.group_colors.get(g) or default_color(g)) for g, n in self.group_names.items()}

    def rep_op(self, body):
        # operações do repertório; devolve (status, resposta)
        op = body.get("op")
        if op in ("add", "update"):
            with self.lock:
                if op == "add":
                    name = re.sub(r"[\x00-\x1f]", "", str(body.get("name", ""))).strip()[:60]
                    if not name:
                        name = (self.score or {}).get("name", "Música %d" % self.rep["next"])
                    if len(self.rep["songs"]) >= 200:
                        return 400, {"error": "Repertório cheio (200 músicas)."}
                    if not self.score and not any(h.has_file() for h in self.hubs.values()):
                        return 400, {"error": "Carregue uma partitura ou um áudio antes de salvar a música."}
                    m = {"id": self.rep["next"], "name": name}
                    self.rep["next"] += 1
                    self._rep_snapshot(m)
                    self.rep["songs"].append(m)
                else:
                    m = self._rep_song(body.get("id"))
                    if m is None:
                        return 404, {"error": "Música não encontrada."}
                    self._rep_snapshot(m)
                self.rep["current"] = m["id"]
                self._rep_save()
            return 200, self.rep_json()
        if op == "save":
            self.rep_autosave()
            return 200, self.rep_json()
        if op in ("rename", "remove", "move"):
            with self.lock:
                m = self._rep_song(body.get("id"))
                if m is None:
                    return 404, {"error": "Música não encontrada."}
                if op == "rename":
                    name = re.sub(r"[\x00-\x1f]", "", str(body.get("name", ""))).strip()[:60]
                    if not name:
                        return 400, {"error": "Nome vazio."}
                    m["name"] = name
                elif op == "remove":
                    self.rep["songs"].remove(m)
                    shutil.rmtree(self.rep_dir / ("song-%d" % m["id"]), ignore_errors=True)
                    if self.rep.get("current") == m["id"]:
                        self.rep["current"] = None
                else:
                    i = self.rep["songs"].index(m)
                    j = i + (-1 if body.get("dir") == -1 else 1)
                    if 0 <= j < len(self.rep["songs"]):
                        self.rep["songs"][i], self.rep["songs"][j] = self.rep["songs"][j], self.rep["songs"][i]
                self._rep_save()
            return 200, self.rep_json()
        if op in ("apply", "next", "prev"):
            with self.lock:
                songs = self.rep["songs"]
                if op == "apply":
                    m = self._rep_song(body.get("id"))
                else:
                    if not songs:
                        return 400, {"error": "O repertório está vazio."}
                    ids = [x["id"] for x in songs]
                    cur = self.rep.get("current")
                    i = ids.index(cur) if cur in ids else (-1 if op == "next" else len(ids))
                    j = i + (1 if op == "next" else -1)
                    if not 0 <= j < len(ids):
                        return 400, {"error": "Não há música %s." % ("depois desta" if op == "next" else "antes desta")}
                    m = songs[j]
                if m is None:
                    return 404, {"error": "Música não encontrada."}
                if self.state.get("playing") or any(h.is_playing() for h in self.hubs.values()):
                    return 409, {"error": "Pare a música e o metrônomo antes de trocar de música."}
                self._rep_apply(m)
                self.rep["current"] = m["id"]
                self._rep_save()
            return 200, self.rep_json()
        if op == "new":
            # "Nova música": limpa a configuração ao vivo e solta a música atual da lista,
            # para que o autosave não sobrescreva a música anterior
            with self.lock:
                if self.state.get("playing") or any(h.is_playing() for h in self.hubs.values()):
                    return 409, {"error": "Pare a música e o metrônomo antes de começar outra."}
                self.rep_autosave()
                self._rep_apply({"id": 0, "audio": [], "parts": {}})
                self.score_pos = None
                self.rep["current"] = None
                self._rep_save()
            return 200, self.rep_json()
        return 400, {"error": "Operação inválida."}

    # ---- arquivo .sincro: zip com sincro.json + song-N/score.bin + song-N/audio-K.bin (uma música ou a playlist toda)
    def rep_export(self, ids=None):
        """Monta o .sincro num arquivo temporário e devolve o caminho. ids=None -> playlist toda."""
        with self.lock:
            songs = [m for m in self.rep["songs"] if ids is None or m["id"] in ids]
            if not songs:
                return None
            snap = json.loads(json.dumps(songs))
            dirs = [self.rep_dir / ("song-%d" % m["id"]) for m in songs]
        tmp = tempfile.NamedTemporaryFile(prefix="sincro-", suffix=".sincro", dir=str(self.cache_dir), delete=False)
        tmp.close()
        out = []
        with zipfile.ZipFile(tmp.name, "w", zipfile.ZIP_STORED, allowZip64=True) as z:
            for i, (m, d) in enumerate(zip(snap, dirs), 1):
                e = {"name": m["name"], "bpm": m.get("bpm"), "beats": m.get("beats"), "marks": m.get("marks", {}),
                     "parts": m.get("parts", {}), "colors": m.get("colors", {}), "score": None, "audio": []}
                sc = m.get("score")
                if sc and (d / sc["file"]).exists():
                    z.write(d / sc["file"], "song-%d/score.bin" % i)
                    e["score"] = {"name": sc["name"], "size": sc.get("size", 0)}
                for k, a in enumerate(m.get("audio", []), 1):
                    if (d / a["file"]).exists():
                        z.write(d / a["file"], "song-%d/audio-%d.bin" % (i, k))
                        e["audio"].append({"group": a["group"], "file": "audio-%d.bin" % k, "name": a["name"], "mime": a.get("mime", "application/octet-stream"), "size": a.get("size", 0)})
                out.append(e)
            z.writestr("sincro.json", json.dumps({"format": "sincro", "version": 1, "songs": out}, ensure_ascii=False))
        return tmp.name

    @staticmethod
    def _clean_name(v, n):
        return re.sub(r"[\x00-\x1f/\\]", "", str(v)).strip()[:n]

    def rep_import(self, path, mode):
        """Lê um .sincro com validação (nomes fixos, tamanhos, tipos). mode: add | replace. Devolve (status, resposta)."""
        try:
            z = zipfile.ZipFile(path)
        except (zipfile.BadZipFile, OSError):
            return 400, {"error": "Arquivo .sincro inválido."}
        with z:
            infos = {i.filename: i for i in z.infolist()}
            if len(infos) > 5000 or "sincro.json" not in infos or infos["sincro.json"].file_size > 5_000_000:
                return 400, {"error": "Arquivo .sincro inválido."}
            ok_name = re.compile(r"^song-(\d{1,3})/(score\.bin|audio-\d{1,2}\.bin)$")
            for n, i in infos.items():
                if n != "sincro.json" and (not ok_name.match(n) or i.file_size > MAX_UPLOAD):
                    return 400, {"error": "O .sincro tem arquivos que não reconheço (%s)." % n[:40]}
            try:
                man = json.loads(z.read("sincro.json").decode("utf-8"))
                if man.get("format") != "sincro" or man.get("version") != 1 or not isinstance(man.get("songs"), list):
                    raise ValueError
            except (ValueError, UnicodeDecodeError, AttributeError):
                return 400, {"error": "Arquivo .sincro inválido ou de uma versão que não conheço."}
            if len(man["songs"]) > 200:
                return 400, {"error": "Músicas demais no arquivo."}
            with self.lock:
                if mode == "replace" and (self.state.get("playing") or any(h.is_playing() for h in self.hubs.values())):
                    return 409, {"error": "Pare a música e o metrônomo antes de substituir a playlist."}
                if mode != "replace" and len(self.rep["songs"]) + len(man["songs"]) > 200:
                    return 400, {"error": "Repertório cheio (200 músicas)."}
                if mode == "replace":
                    for m in self.rep["songs"]:
                        shutil.rmtree(self.rep_dir / ("song-%d" % m["id"]), ignore_errors=True)
                    self.rep["songs"] = []
                    self.rep["current"] = None
                added = 0
                for idx, e in enumerate(man["songs"], 1):
                    if not isinstance(e, dict):
                        continue
                    m = {"id": self.rep["next"], "name": self._clean_name(e.get("name") or "Música", 60) or "Música"}
                    d = self.rep_dir / ("song-%d" % m["id"])
                    d.mkdir(parents=True)
                    m["score"] = None
                    sc = e.get("score")
                    if isinstance(sc, dict) and ("song-%d/score.bin" % idx) in infos:
                        with z.open("song-%d/score.bin" % idx) as src, open(d / "score.bin", "wb") as dst:
                            shutil.copyfileobj(src, dst)
                        m["score"] = {"file": "score.bin", "name": self._clean_name(sc.get("name") or "partitura", 120) or "partitura", "size": (d / "score.bin").stat().st_size}
                    m["audio"] = []
                    for a in (e.get("audio") or [])[:MAX_GROUPS]:
                        if not isinstance(a, dict) or not re.match(r"^audio-\d{1,2}\.bin$", str(a.get("file", ""))) or ("song-%d/%s" % (idx, a["file"])) not in infos:
                            continue
                        gname = self._clean_name(a.get("group", ""), 40)
                        if not gname:
                            continue
                        with z.open("song-%d/%s" % (idx, a["file"])) as src, open(d / a["file"], "wb") as dst:
                            shutil.copyfileobj(src, dst)
                        m["audio"].append({"group": gname, "file": a["file"], "name": self._clean_name(a.get("name") or "audio", 120) or "audio",
                                           "mime": self._clean_name(a.get("mime") or "application/octet-stream", 60), "size": (d / a["file"]).stat().st_size})
                    parts = e.get("parts") if isinstance(e.get("parts"), dict) else {}
                    m["parts"] = {self._clean_name(n, 40): int(i) for n, i in parts.items() if self._clean_name(n, 40) and isinstance(i, int) and 0 <= i < 64}
                    try:
                        m["bpm"] = float(e["bpm"]) if e.get("bpm") else None
                        m["beats"] = int(e["beats"]) if e.get("beats") else None
                    except (TypeError, ValueError):
                        m["bpm"], m["beats"] = None, None
                    marks = {}
                    if isinstance(e.get("marks"), dict):
                        for sig, v in list(e["marks"].items())[:64]:
                            try:
                                marks[str(sig)[:300]] = {"marks": [round(float(x), 1) for x in v["marks"]][:5000], "offset": float(v.get("offset", 0))}
                            except (TypeError, ValueError, KeyError, AttributeError):
                                continue
                    m["marks"] = marks
                    cols = e.get("colors") if isinstance(e.get("colors"), dict) else {}
                    m["colors"] = {self._clean_name(n, 40): c.lower() for n, c in cols.items() if isinstance(c, str) and COLOR_RE.match(c)}
                    self.rep["next"] += 1
                    self.rep["songs"].append(m)
                    added += 1
                self._rep_save()
            out = self.rep_json()
            out["added"] = added
            return 200, out

    def _rep_apply(self, m):
        d = self.rep_dir / ("song-%d" % m["id"])
        # grupos: pelo NOME; os que não existem mais são recriados
        ids = {}
        for gid, name in self.group_names.items():
            ids.setdefault(name, gid)
        need = [a["group"] for a in m.get("audio", [])] + list(m.get("parts", {}))
        for name in need:
            if name not in ids and len(self.group_names) < MAX_GROUPS:
                self.group_op({"op": "add", "name": name})
                ids[name] = "g%d" % (self.group_next - 1)
        for name, col in (m.get("colors") or {}).items():
            if name in ids and isinstance(col, str) and COLOR_RE.match(col):
                self.group_colors[ids[name]] = col.lower()
        self.group_rev += 1
        # áudio por grupo: quem não está na música fica sem áudio
        have = {a["group"]: a for a in m.get("audio", [])}
        for gid, name in list(self.group_names.items()):
            h = self.hub(gid)
            a = have.get(name) if ids.get(name) == gid else None
            if a and (d / a["file"]).exists():
                dst = self._fresh("up")
                shutil.copyfile(d / a["file"], dst)
                h.set_file(str(dst), a["name"], a.get("mime", "application/octet-stream"), a.get("size", dst.stat().st_size))
            elif h.has_file():
                h.clear()
        # partitura, partes e marcadores
        sc = m.get("score")
        if sc and (d / sc["file"]).exists():
            dst = self._fresh("score")
            shutil.copyfile(d / sc["file"], dst)
            self.score_set(str(dst), sc["name"], sc.get("size", dst.stat().st_size))
            self.score_parts = {ids[n]: i for n, i in m.get("parts", {}).items() if n in ids}
            self.score_marks = json.loads(json.dumps(m.get("marks", {})))
            self.score_rev += 1
        elif self.score:
            self.score_clear()
        if m.get("bpm"):
            self.command({"type": "settings", "bpm": m["bpm"], "beatsPerBar": m.get("beats") or 4})

    # ---- volumes
    def mix_json(self):
        with self.lock:
            return {"rev": self.mix_rev, "levels": dict(self.mix)}

    def mix_set(self, dev, level):
        if not dev or len(dev) > 80:
            return False
        try:
            v = clamp(float(level), 0.0, 1.0)
        except (TypeError, ValueError):
            return False
        with self.lock:
            if self.mix.get(dev) != v:
                self.mix[dev] = v
                self.mix_rev += 1
        return True

    # ---- aparelhos vistos
    def seen(self, device, name=None):
        if not device:
            return
        with self.lock:
            d = self.devices.setdefault(device, {"name": ""})
            d["seen"] = now_ms()
            if name:
                d["name"] = name

    def devices_json(self):
        now = now_ms()
        with self.lock:
            out = []
            for dev, d in self.devices.items():
                out.append({"id": dev, "name": d.get("name") or dev[:8], "group": self.assign.get(dev, DEFAULT_GROUP),
                            "ageMs": int(now - d.get("seen", 0)), "level": self.mix.get(dev, 1.0)})
            return sorted(out, key=lambda x: x["name"])


SESSION = None
WEB_DIR = None


def query_of(path):
    return {k: v[0] for k, v in urllib.parse.parse_qs(urllib.parse.urlparse(path).query).items()}


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "Sincrono"

    def log_message(self, fmt, *args):  # silencioso: o servidor atende muitas requisições por segundo
        pass

    def parse_request(self):
        self._t_recv = now_ms()  # logo depois de ler a linha da requisição (melhor ponto de carimbo)
        return super().parse_request()

    # ---- utilidades
    def _is_local(self):
        """Pedido vindo deste mesmo computador (o anfitrião), e não de outro aparelho da rede."""
        return self.client_address[0] in ("127.0.0.1", "::1", "::ffff:127.0.0.1")

    def _json(self, status, obj):
        data = (obj if isinstance(obj, str) else json.dumps(obj)).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _body(self):
        n = int(self.headers.get("Content-Length") or 0)
        if n < 0 or n > 65536:
            raise ValueError("corpo grande demais")
        return self.rfile.read(n) if n else b""

    def _body_json(self):
        try:
            return json.loads(self._body().decode("utf-8") or "{}")
        except (ValueError, UnicodeDecodeError):
            return None

    def _sse(self, q, first):
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache, no-transform")
        self.send_header("Connection", "close")
        self.end_headers()
        self.close_connection = True
        try:
            self.wfile.write(first.encode("utf-8"))
            self.wfile.flush()
            while True:
                try:
                    item = q.get(timeout=10)
                except queue.Empty:
                    item = ": ping\n\n"
                if item is None:
                    break
                self.wfile.write(item.encode("utf-8"))
                self.wfile.flush()
        except (OSError, ValueError):
            pass

    # ---- rotas
    def do_GET(self):
        s = SESSION
        path = urllib.parse.urlparse(self.path).path
        q = query_of(self.path)
        if path == "/api/state":
            return self._json(200, s.state)
        if path == "/api/setlist":
            return self._json(200, s.rep_json())
        if path == "/api/setlist/export":
            ids = None
            if q.get("ids"):
                try:
                    ids = {int(x) for x in q["ids"].split(",") if x}
                except ValueError:
                    return self._json(400, {"error": "Lista de músicas inválida."})
            fp = s.rep_export(ids)
            if fp is None:
                return self._json(404, {"error": "Nada para exportar."})
            name = re.sub(r'[^\w .-]', "", q.get("name", "") or "playlist", flags=re.U).strip()[:60] or "playlist"
            try:
                size = os.path.getsize(fp)
                self.send_response(200)
                self.send_header("Content-Type", "application/octet-stream")
                self.send_header("Content-Disposition", "attachment; filename*=UTF-8''" + urllib.parse.quote(name + ".sincro"))
                self.send_header("Content-Length", str(size))
                self.end_headers()
                with open(fp, "rb") as f:
                    shutil.copyfileobj(f, self.wfile, 65536)
            finally:
                try:
                    os.remove(fp)
                except OSError:
                    pass
            return
        if path == "/events":
            fila = queue.Queue()
            with s.lock:
                s.state_clients.append(fila)
                first = "event: state\ndata: %s\n\n" % json.dumps(s.state)
            try:
                return self._sse(fila, first)
            finally:
                with s.lock:
                    if fila in s.state_clients:
                        s.state_clients.remove(fila)
        if path == "/events/audio":
            gid = q.get("group", DEFAULT_GROUP)
            h = s.hub(gid)
            if h is None:
                return self._json(404, {"error": "Grupo não encontrado."})
            fila = queue.Queue()
            entry = (gid, fila)
            with s.lock:
                s.audio_clients.append(entry)
            try:
                return self._sse(fila, "event: audio\ndata: %s\n\n" % h.snapshot())
            finally:
                with s.lock:
                    if entry in s.audio_clients:
                        s.audio_clients.remove(entry)
        if path == "/api/audio":
            h = s.hub(q.get("group", DEFAULT_GROUP))
            if h is None:
                return self._json(404, {"error": "Grupo não encontrado."})
            dev = q.get("device", "")
            s.seen(dev)
            try:
                ready = int(q.get("ready", "0"))
            except ValueError:
                ready = 0
            h.register(dev, ready)
            return self._json(200, h.snapshot())
        if path == "/api/audio/file":
            h = s.hub(q.get("group", DEFAULT_GROUP))
            try:
                fid = int(q.get("id", ""))
            except ValueError:
                fid = -1
            info = h.current_file(fid) if h else None
            if not info or not os.path.isfile(info["path"]):
                return self._json(404, {"error": "Não encontrado."})
            size = os.path.getsize(info["path"])
            self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(size))
            self.end_headers()
            with open(info["path"], "rb") as f:
                while True:
                    chunk = f.read(65536)
                    if not chunk:
                        break
                    self.wfile.write(chunk)
            return
        if path == "/api/score":
            return self._json(200, s.score_json())
        if path == "/api/score/file":
            try:
                fid = int(q.get("id", ""))
            except ValueError:
                fid = -1
            info = s.score_file(fid)
            if not info or not os.path.isfile(info["path"]):
                return self._json(404, {"error": "Não encontrado."})
            return self._file(info["path"], "application/octet-stream")
        if path == "/api/mix":
            return self._json(200, s.mix_json())
        if path == "/api/groups":
            return self._json(200, s.groups_json())
        if path == "/api/info":
            port = self.server.server_address[1]
            urls = ["http://%s:%d" % (ip, port) for ip in lan_addresses()] or ["http://127.0.0.1:%d" % port]
            return self._json(200, {"host": "pc", "upload": True, "urls": urls, "local": self._is_local(),
                                    "calibration": CABLE is not None})
        if path == "/api/devices":
            return self._json(200, s.devices_json())
        if path == "/api/discover":
            items, available = discover(self.server.server_address[1])
            return self._json(200, {"sessions": items, "available": available})
        if path.startswith("/api/calib"):
            if path == "/api/calib":
                s.seen(q.get("device"), q.get("name"))
            if CABLE is None:
                return self._json(404, {"error": "Este servidor não oferece calibração automática (falta numpy)."})
            status, reply = CABLE.handle("GET", path, q, None, local=self._is_local())
            return self._json(status, reply)
        if path == "/admin":  # o painel agora é a própria página inicial
            self.send_response(302)
            self.send_header("Location", "/")
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        if path.startswith("/api/"):
            return self._json(404, {"error": "Não encontrado."})
        return self._asset(path)

    def do_POST(self):
        s = SESSION
        path = urllib.parse.urlparse(self.path).path
        q = query_of(self.path)
        if path == "/api/sync":
            t_recv = getattr(self, "_t_recv", now_ms())
            body = self._body_json() or {}
            sent = now_ms()
            client_sent = int(body.get("clientSentAt", 0) or 0) if isinstance(body, dict) else 0
            return self._json(200, '{"clientSentAt":%d,"serverReceivedAt":%.3f,"serverSentAt":%.3f}' % (client_sent, t_recv, sent))
        if path == "/api/audio/upload":
            return self._upload(q)
        if path == "/api/setlist/import":
            return self._import_sincro(q)
        if path == "/api/score/upload":
            return self._upload_score(q)
        body = self._body_json()
        if body is None or not isinstance(body, dict):
            return self._json(400, {"error": "JSON inválido."})
        if path.startswith("/api/calib"):
            if CABLE is None:
                return self._json(404, {"error": "Este servidor não oferece calibração automática (falta numpy)."})
            status, reply = CABLE.handle("POST", path, q, body, local=self._is_local())
            return self._json(status, reply)
        if path == "/api/score/clear":
            s.score_clear()
            return self._json(200, s.score_json())
        if path == "/api/score/pos":
            try:
                ms = body.get("ms")
                s.score_pos = None if ms is None else max(0.0, float(ms))
            except (TypeError, ValueError):
                return self._json(400, {"error": "Posição inválida."})
            return self._json(200, s.score_json())
        if path == "/api/setlist":
            status, reply = s.rep_op(body)
            return self._json(status, reply)
        if path == "/api/score/marks":
            sig = body.get("sig")
            if not isinstance(sig, str) or not sig or len(sig) > 300:
                return self._json(400, {"error": "Áudio inválido."})
            m = body.get("marks")
            if m is None:
                s.score_marks.pop(sig, None)
            else:
                try:
                    m = [round(float(x), 1) for x in m]
                    off = float(body.get("offset", 0))
                except (TypeError, ValueError):
                    return self._json(400, {"error": "Marcadores inválidos."})
                if not (1 <= len(m) <= 20000) or any(b < a for a, b in zip(m, m[1:])) or m[0] < 0 or abs(off) > 5000:
                    return self._json(400, {"error": "Marcadores inválidos (devem crescer, e o ajuste fica entre -5 s e 5 s)."})
                s.score_marks[sig] = {"marks": m, "offset": off}
            with s.lock:
                s.score_rev += 1
            return self._json(200, s.score_json())
        if path == "/api/score/part":
            if not s.score_part(body.get("group"), body.get("part")):
                return self._json(400, {"error": "Grupo ou parte inválidos (importe a partitura antes)."})
            return self._json(200, s.score_json())
        if path == "/api/command":
            if body.get("type") in ("play", "restart"):
                s.score_pos = None           # ao tocar, o cursor volta a seguir o relógio
            status, reply = s.command(body)
            return self._json(status, reply)
        if path == "/api/audio/cmd":
            if body.get("type") == "play":
                s.score_pos = None
            gid = q.get("group", DEFAULT_GROUP)
            if gid == "all":
                status, reply = s.audio_all(body)
                return self._json(status, reply)
            h = s.hub(gid)
            if h is None:
                return self._json(404, {"error": "Grupo não encontrado."})
            status, reply = h.command(body)
            return self._json(status, reply)
        if path == "/api/mix":
            if not body.get("device") or "level" not in body or not s.mix_set(body["device"], body["level"]):
                return self._json(400, {"error": "Pedido inválido."})
            return self._json(200, s.mix_json())
        if path == "/api/groups":
            if not s.group_op(body):
                return self._json(400, {"error": "Operação de grupo inválida."})
            return self._json(200, s.groups_json())
        return self._json(404, {"error": "Não encontrado."})

    def _upload(self, q):
        s = SESSION
        h = s.hub(q.get("group", DEFAULT_GROUP))
        name = re.sub(r"[\x00-\x1f/\\]", "", q.get("name", "audio")).strip()[:120] or "audio"
        try:
            n = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            n = 0
        if h is None:
            return self._json(404, {"error": "Grupo não encontrado."})
        if n <= 0 or n > MAX_UPLOAD:
            return self._json(400, {"error": "Arquivo vazio ou grande demais (máximo %d MB)." % (MAX_UPLOAD // 1_000_000)})
        target = s.cache_dir / ("up-%d.bin" % int(time.time() * 1000))
        left = n
        with open(target, "wb") as f:
            while left > 0:
                chunk = self.rfile.read(min(65536, left))
                if not chunk:
                    break
                f.write(chunk)
                left -= len(chunk)
        if left > 0:
            os.remove(target)
            return self._json(400, {"error": "Envio interrompido."})
        h.set_file(str(target), name, self.headers.get("X-File-Type", "application/octet-stream"), n)
        return self._json(200, s.groups_json())

    def _import_sincro(self, q):
        s = SESSION
        mode = "replace" if q.get("mode") == "replace" else "add"
        try:
            n = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            n = 0
        if n <= 0 or n > MAX_IMPORT:
            return self._json(400, {"error": "Arquivo vazio ou grande demais (máximo %d MB)." % (MAX_IMPORT // 1_000_000)})
        target = s.cache_dir / ("import-%d.sincro" % int(time.time() * 1000))
        left = n
        try:
            with open(target, "wb") as f:
                while left > 0:
                    chunk = self.rfile.read(min(65536, left))
                    if not chunk:
                        break
                    f.write(chunk)
                    left -= len(chunk)
            if left > 0:
                return self._json(400, {"error": "Envio interrompido."})
            status, reply = s.rep_import(str(target), mode)
            return self._json(status, reply)
        finally:
            try:
                os.remove(target)
            except OSError:
                pass

    def _upload_score(self, q):
        s = SESSION
        name = re.sub(r"[\x00-\x1f/\\]", "", q.get("name", "partitura")).strip()[:120] or "partitura"
        try:
            n = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            n = 0
        if n <= 0 or n > MAX_SCORE:
            return self._json(400, {"error": "Arquivo vazio ou grande demais (máximo %d MB)." % (MAX_SCORE // 1_000_000)})
        target = s.cache_dir / ("score-%d.bin" % int(time.time() * 1000))
        left = n
        with open(target, "wb") as f:
            while left > 0:
                chunk = self.rfile.read(min(65536, left))
                if not chunk:
                    break
                f.write(chunk)
                left -= len(chunk)
        if left > 0:
            os.remove(target)
            return self._json(400, {"error": "Envio interrompido."})
        s.score_set(str(target), name, n)
        return self._json(200, s.score_json())

    def _file(self, path, ctype):
        try:
            data = Path(path).read_bytes()
        except OSError:
            return self._json(404, {"error": "Não encontrado."})
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _asset(self, path):
        if WEB_DIR is None:
            return self._json(404, {"error": "Pasta da página web não encontrada (use --web)."})
        rel = "index.html" if path == "/" else path.lstrip("/")
        target = (WEB_DIR / rel).resolve()
        if WEB_DIR.resolve() not in target.parents and target != WEB_DIR.resolve():
            return self._json(404, {"error": "Não encontrado."})
        types = {".html": "text/html; charset=utf-8", ".js": "text/javascript; charset=utf-8",
                 ".css": "text/css; charset=utf-8", ".webmanifest": "application/manifest+json; charset=utf-8",
                 ".json": "application/json; charset=utf-8"}
        return self._file(target, types.get(target.suffix, "application/octet-stream"))


def lan_addresses():
    found = []
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if not ip.startswith("127.") and ip not in found:
                found.append(ip)
    except OSError:
        pass
    try:  # o endereço usado para sair da rede costuma ser o certo
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("10.255.255.255", 1))
        ip = s.getsockname()[0]
        s.close()
        if ip not in found and not ip.startswith("127."):
            found.insert(0, ip)
    except OSError:
        pass
    return found


def discover(own_port, wait=2.0):
    """Sessões do Sincrono na rede (celulares anfitriões ou outros computadores), via mDNS. (lista, disponível?)"""
    try:
        from zeroconf import ServiceBrowser, Zeroconf
    except ImportError:
        return [], False
    own = set(lan_addresses()) | {"127.0.0.1"}
    found = {}

    class Listener:
        def add_service(self, zc, type_, name):
            try:
                info = zc.get_service_info(type_, name, timeout=1500)
                if not info:
                    return
                for addr in info.parsed_addresses():
                    if ":" in addr:
                        continue  # só IPv4
                    if addr in own and info.port == own_port:
                        return  # esta própria sessão
                    label = name.replace("._metronomo._tcp.local.", "")
                    found[name] = {"name": label, "url": "http://%s:%d" % (addr, info.port)}
                    return
            except Exception:
                pass

        def update_service(self, zc, type_, name):
            pass

        def remove_service(self, zc, type_, name):
            pass

    zc = Zeroconf()
    try:
        ServiceBrowser(zc, "_metronomo._tcp.local.", Listener())
        time.sleep(wait)
    finally:
        zc.close()
    return sorted(found.values(), key=lambda x: x["name"]), True


def announce(port):
    try:
        from zeroconf import ServiceInfo, Zeroconf
    except ImportError:
        print("  (para aparecer na lista 'Sessões encontradas' dos apps: pip install zeroconf)")
        return None
    ips = [socket.inet_aton(ip) for ip in lan_addresses()]
    if not ips:
        return None
    name = "Metronomo Computador (%s)" % socket.gethostname()[:20]
    info = ServiceInfo("_metronomo._tcp.local.", name + "._metronomo._tcp.local.", addresses=ips, port=port, properties={})
    zc = Zeroconf()
    zc.register_service(info)
    print("  anunciado na rede como '%s'" % name)
    return zc


def find_web_dir(explicit):
    candidates = [Path(explicit)] if explicit else [
        Path(__file__).parent / "web",
        Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "assets" / "web",
    ]
    for c in candidates:
        if (c / "index.html").is_file():
            return c
    return None


def find_chromium():
    """Procura Chrome, Edge ou Chromium (navegadores que aceitam liberar o autoplay por parâmetro)."""
    import shutil, os, sys
    names = ["google-chrome", "google-chrome-stable", "chromium", "chromium-browser", "microsoft-edge", "msedge", "chrome"]
    for n in names:
        w = shutil.which(n)
        if w:
            return w
    fixed = []
    if sys.platform.startswith("win"):
        for base in filter(None, [os.environ.get("PROGRAMFILES"), os.environ.get("PROGRAMFILES(X86)"), os.environ.get("LOCALAPPDATA")]):
            fixed += [base + "\\Google\\Chrome\\Application\\chrome.exe", base + "\\Microsoft\\Edge\\Application\\msedge.exe"]
    elif sys.platform == "darwin":
        fixed += ["/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
                  "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
                  "/Applications/Chromium.app/Contents/MacOS/Chromium"]
    for f in fixed:
        if os.path.isfile(f):
            return f
    return None


def open_player(port):
    """Abre a página do Sincrono neste computador JÁ com o som liberado.

    Navegadores bloqueiam som automático até o primeiro clique. Chrome/Edge/Chromium aceitam o
    parâmetro --autoplay-policy=no-user-gesture-required; usamos um perfil só do Sincrono para que
    ele valha mesmo com o navegador normal já aberto. Sem Chrome/Edge, abre o navegador padrão
    (aí é preciso um clique em "Ativar som")."""
    import subprocess
    url = "http://localhost:%d/" % port
    exe = find_chromium()
    if exe:
        profile = str(Path.home() / ".sincrono-browser")
        try:
            subprocess.Popen([exe, "--autoplay-policy=no-user-gesture-required", "--user-data-dir=" + profile,
                              "--no-first-run", "--no-default-browser-check", "--app=" + url],
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            print("  página aberta com som automático (janela do Sincrono)")
            return
        except Exception as e:
            print("  não consegui abrir o Chrome/Edge (%s); usando o navegador padrão" % e)
    __import__("webbrowser").open(url)
    print("  navegador padrão aberto: clique uma vez em \"Ativar som\" (Chrome/Edge abrem sem esse clique)")


def main():
    global SESSION, WEB_DIR
    ap = argparse.ArgumentParser(description="Servidor do Sincrono para o computador.")
    ap.add_argument("--port", type=int, default=3000)
    ap.add_argument("--web", help="pasta da página web (padrão: detecta)")
    ap.add_argument("--no-browser", action="store_true", help="não abrir a página no navegador deste computador")
    ap.add_argument("--cache", default=str(Path.home() / ".sincrono-server"), help="pasta dos arquivos de áudio enviados")
    args = ap.parse_args()
    global CABLE
    SESSION = Session(args.cache)
    SESSION.rep_restore()

    def _autosave_loop():
        last = None
        while True:
            time.sleep(3)
            try:
                with SESSION.lock:
                    fp = SESSION._rep_fp_live()
                if fp == last:      # só grava quando a configuração parou de mudar
                    SESSION.rep_autosave()
                last = fp
            except Exception as e:
                print('Repertório: erro ao guardar sozinho:', e)
    threading.Thread(target=_autosave_loop, daemon=True).start()
    if calibracao_cabo is not None and calibracao_cabo.np is not None:
        CABLE = calibracao_cabo.CableCalibration(now_ms)
        pass
    else:
        print("  (calibração indisponível: pip install numpy sounddevice)")
    WEB_DIR = find_web_dir(args.web)

    httpd = ThreadingHTTPServer(("0.0.0.0", args.port), Handler)
    httpd.daemon_threads = True
    httpd.request_queue_size = 512  # muitos aparelhos entrando ao mesmo tempo
    port = httpd.server_address[1]
    print("Sincrono aberto em http://localhost:%d  (deixe esta janela do terminal aberta)" % port)
    if WEB_DIR is None:
        print("  AVISO: pasta da página web não encontrada; use --web.")
    zc = announce(port)
    if not args.no_browser:
        # Abre a página neste computador: ela também toca o metrônomo e a música aqui (clique em "Ativar som").
        threading.Timer(1.0, lambda: open_player(port)).start()
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\nEncerrando.")
    finally:
        if zc:
            zc.close()
        httpd.server_close()


if __name__ == "__main__":
    main()
