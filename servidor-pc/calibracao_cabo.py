"""Calibração por cabo, com a mesa de som somando todos os aparelhos (um botão só).

Como funciona
  * A saída de fone de cada aparelho (celulares, o computador) vai por cabo a uma entrada da mesa.
    A mesa soma tudo e entrega ao computador pelo USB (ou por uma entrada de linha).
  * Todos os aparelhos tocam um "chirp" (2–6 kHz, 40 ms), cada um no seu horário combinado (slot).
    Como só um toca por vez, quem gravar sabe de quem é cada som pelo horário: não precisa de um
    canal por aparelho nem de configurar nada.
  * O computador grava, mede o instante exato de chegada de cada chirp (filtro casado + envelope +
    interpolação parabólica) e compara com a referência: a página do próprio computador (o anfitrião),
    que também vai por cabo. O atraso de entrada da interface e o início da gravação cancelam.
  * Cada aparelho recebe o seu ajuste e o aplica sozinho (mesmo protocolo /api/calib dos apps).

Dependências (só para calibrar): numpy e sounddevice (pip install numpy sounddevice).
O servidor continua funcionando sem elas; só a calibração fica indisponível.
"""
import json
import threading
import time

try:
    import numpy as np
except Exception:  # pragma: no cover
    np = None

F0, F1, DURATION = 2000.0, 6000.0, 0.04
SLOT_MS = 600
REPEATS = 4
CABLE_AMPLITUDE = 0.5
LEAD_MS = 4500
MIN_RATIO = 7.0          # pico / nível médio do envelope para aceitar a detecção
MIN_PEAK = 0.02          # nível mínimo (0..1) no canal
CLIP_PEAK = 0.98
SEARCH_BEFORE_MS = 400   # janela de busca ao redor do horário combinado (cobre a latência de entrada)
SEARCH_AFTER_MS = 400
PEER_TTL_MS = 6000.0


def chirp(sample_rate, amplitude=1.0):
    n = int(round(DURATION * sample_rate))
    t = np.arange(n) / sample_rate
    window = 0.5 * (1 - np.cos(2 * np.pi * t / DURATION))
    phase = 2 * np.pi * (F0 * t + (F1 - F0) / (2 * DURATION) * t * t)
    return amplitude * window * np.sin(phase)


def _envelope_of_correlation(x, template):
    """Envelope (módulo do sinal analítico) da correlação de x com o chirp, por FFT.
    O índice i do resultado é o instante em que o chirp COMEÇA em x."""
    n = len(x) + len(template)
    size = 1 << (n - 1).bit_length()
    fx = np.fft.rfft(x, size)
    ft = np.fft.rfft(template, size)
    corr_spec = fx * np.conj(ft)
    # sinal analítico: zera as frequências negativas e dobra as positivas
    full = np.zeros(size, dtype=complex)
    full[: len(corr_spec)] = corr_spec
    full[1: len(corr_spec) - 1] *= 2
    analytic = np.fft.ifft(full)
    return np.abs(analytic)[: len(x)]


def detect_arrival(x, sample_rate, center_index, before, after):
    """Procura um chirp em x perto de center_index. Devolve (índice fracionário do início, razão) ou None."""
    lo = max(0, int(center_index - before))
    hi = min(len(x), int(center_index + after) + int(DURATION * sample_rate))
    if hi - lo < int(DURATION * sample_rate) * 2:
        return None
    seg = x[lo:hi]
    env = _envelope_of_correlation(seg, chirp(sample_rate))
    valid = env[: len(seg) - int(DURATION * sample_rate)]
    if len(valid) < 8:
        return None
    k = int(np.argmax(valid))
    peak = float(valid[k])
    base = float(np.median(valid)) + 1e-12
    ratio = peak / base
    if 0 < k < len(valid) - 1:
        a, b, c = float(valid[k - 1]), float(valid[k]), float(valid[k + 1])
        denom = a - 2 * b + c
        frac = 0.5 * (a - c) / denom if denom != 0 else 0.0
    else:
        frac = 0.0
    return lo + k + frac, ratio


def analyze(recording, sample_rate, rec_start_ms, plan, repeats=REPEATS):
    """Analisa uma gravação.

    recording: array (amostras,) ou (amostras, canais), valores -1..1
    rec_start_ms: horário (relógio do servidor) do 1º quadro gravado (só precisa ser coerente)
    plan: {chave: [horários combinados (ms) dos chirps dessa chave]}. A chave é o número do canal
          (recording 2D) ou qualquer nome, p.ex. o id do aparelho (recording 1D = mistura somada).
    Devolve {chave: dict(ok, offsetMs (bruto: chegada - combinado), spreadMs, count, peak, clipped, error)}.
    """
    out = {}
    before = SEARCH_BEFORE_MS * sample_rate / 1000.0
    after = SEARCH_AFTER_MS * sample_rate / 1000.0
    dur = int(DURATION * sample_rate)
    for key, times in plan.items():
        info = dict(ok=False, offsetMs=0.0, spreadMs=0.0, count=0, peak=0.0, clipped=False, error="")
        out[key] = info
        if recording.ndim == 1:
            x = recording
        elif not isinstance(key, int) or key >= recording.shape[1]:
            info["error"] = "a interface não tem este canal"
            continue
        else:
            x = recording[:, key]
        # nível só nas janelas deste aparelho (na mistura, os outros tocam em outros horários)
        peak = 0.0
        for t in times:
            c = (t - rec_start_ms) * sample_rate / 1000.0
            lo, hi = max(0, int(c - before)), min(len(x), int(c + after) + dur)
            if hi > lo:
                peak = max(peak, float(np.max(np.abs(x[lo:hi]))))
        info["peak"] = peak
        info["clipped"] = peak >= CLIP_PEAK
        if info["clipped"]:
            info["error"] = "o sinal estourou: abaixe o volume deste aparelho ou o ganho da mesa"
            continue
        if peak < MIN_PEAK:
            info["error"] = "não chegou som deste aparelho: confira o cabo, o volume e se o app está aberto com o som ativo"
            continue
        offsets = []
        for t in times:
            center = (t - rec_start_ms) * sample_rate / 1000.0
            found = detect_arrival(x, sample_rate, center, before, after)
            if found is None:
                continue
            idx, ratio = found
            if ratio < MIN_RATIO:
                continue
            arrival_ms = rec_start_ms + idx * 1000.0 / sample_rate
            offsets.append(arrival_ms - t)
        info["count"] = len(offsets)
        if len(offsets) < max(2, (repeats + 1) // 2 + 1):
            info["error"] = "o som deste aparelho chegou fraco ou instável: aumente um pouco o volume e fique em silêncio"
            continue
        offsets.sort()
        mid = len(offsets) // 2
        median = offsets[mid] if len(offsets) % 2 else (offsets[mid - 1] + offsets[mid]) / 2
        info.update(ok=True, offsetMs=median, spreadMs=offsets[-1] - offsets[0])
    return out


def quality(spread_ms, count, repeats=REPEATS):
    if count < repeats or spread_ms > 2.0:
        return "low"
    if spread_ms <= 0.5:
        return "high"
    return "medium"


def solve(plan_results, reference_channel):
    """plan_results: saída de analyze(). Devolve {chave: dict(offsetMs relativo à referência, spreadMs, quality, count)}
    ou levanta ValueError se a referência não foi medida."""
    ref = plan_results.get(reference_channel)
    if not ref or not ref["ok"]:
        raise ValueError("O som deste computador (a referência) não foi medido: "
                         + ((ref or {}).get("error") or "confira o cabo dele até a mesa"))
    out = {}
    for ch, r in plan_results.items():
        if not r["ok"]:
            continue
        spread = r["spreadMs"] if ch == reference_channel else max(r["spreadMs"], ref["spreadMs"])
        offset = 0.0 if ch == reference_channel else r["offsetMs"] - ref["offsetMs"]
        out[ch] = dict(offsetMs=offset, spreadMs=spread, count=r["count"], quality=quality(spread, r["count"]))
    return out


# ----------------------------------------------------------------------------------------------
# Gravação (sounddevice) — separada para poder ser trocada por uma simulação nos testes.

VIRTUAL_INPUTS = ("pipewire", "pulse", "default", "sysdefault")


def list_interfaces():
    try:
        import sounddevice as sd
    except Exception as e:
        return [], "Instale as dependências: pip install numpy sounddevice (%s)" % e
    items = []
    for i, d in enumerate(sd.query_devices()):
        if d.get("max_input_channels", 0) > 0:
            ch = d["max_input_channels"]
            if d["name"] in VIRTUAL_INPUTS:
                ch = min(ch, 2)  # servidores de som mostram dezenas de canais virtuais
            items.append({"index": i, "name": d["name"], "channels": ch,
                          "sampleRate": int(d.get("default_samplerate") or 48000)})
    return items, ""


def input_candidates(sd):
    """Ordem automática: interfaces USB (a mesa) primeiro, depois os servidores de som do sistema."""
    devs = sd.query_devices()
    usb, virtual, rest = [], [], []
    for i, d in enumerate(devs):
        if d.get("max_input_channels", 0) <= 0:
            continue
        name = d["name"].lower()
        if d["name"] in VIRTUAL_INPUTS:
            virtual.append(i)
        elif "usb" in name:
            usb.append(i)
        else:
            rest.append(i)
    virtual.sort(key=lambda i: VIRTUAL_INPUTS.index(devs[i]["name"]))
    return usb + virtual + rest


def record_with_sounddevice(device_index, duration_s, now_ms):
    """Grava duration_s segundos. device_index None = automático. Devolve (array, sample_rate, rec_start_ms)."""
    import sounddevice as sd
    candidates = [device_index] if device_index is not None else input_candidates(sd)
    last_error = None
    for idx in candidates:
        info = sd.query_devices(idx)
        channels = info["max_input_channels"]
        channels = min(channels, 2) if info["name"] in VIRTUAL_INPUTS else min(channels, 16)
        sr = int(info.get("default_samplerate") or 48000)
        try:
            start_ms = now_ms()
            data = sd.rec(int(duration_s * sr), samplerate=sr, channels=max(channels, 1), device=idx, dtype="float32")
            sd.wait()
            return data, sr, start_ms
        except Exception as e:  # dispositivo ocupado etc.: tenta o próximo
            last_error = e
    raise RuntimeError("Não consegui gravar do computador (%s). Confira se a mesa está ligada por USB." % (last_error or "nenhuma entrada de áudio"))


class CableCalibration:
    """Calibração no servidor do computador (mesmo protocolo /api/calib dos apps). Um botão: calibra todos."""

    HOST_ID = "pc-host"

    def __init__(self, now_ms, recorder=None):
        self.now_ms = now_ms
        self.recorder = recorder or record_with_sounddevice
        self.lock = threading.RLock()
        self.peers = {}          # device -> dict(name, seen, auto, cal, local)
        self.interface_index = None   # None = automática
        self.id = 0
        self.state = "idle"
        self.message = ""
        self.start_at = 0
        self.devices = []        # [(id, name)] — referência primeiro
        self.results = {}

    # ---- aparelhos
    def register(self, q, local=False):
        device = q.get("device", "")
        if not device:
            return
        with self.lock:
            p = self.peers.setdefault(device, {"name": "Aparelho", "seen": 0.0, "auto": 0.0, "cal": False, "local": False})
            p["name"] = q.get("name") or "Aparelho"
            p["seen"] = self.now_ms()
            p["local"] = bool(local)
            try:
                p["auto"] = float(q.get("auto", 0) or 0)
            except ValueError:
                p["auto"] = 0.0
            p["cal"] = q.get("cal") == "1"

    def live(self):
        now = self.now_ms()
        return {k: v for k, v in self.peers.items() if now - v["seen"] < PEER_TTL_MS}

    def reference_id(self):
        """A página deste computador (a mais recente) é a referência: o anfitrião."""
        local = [(v["seen"], k) for k, v in self.live().items() if v["local"]]
        return max(local)[1] if local else ""

    # ---- protocolo
    def session_json(self):
        with self.lock:
            live = self.live()
            ref = self.reference_id()
            return {
                "id": self.id, "state": self.state, "mode": "all", "cable": True, "amp": CABLE_AMPLITUDE,
                "startAt": self.start_at, "slotMs": SLOT_MS, "repeats": REPEATS,
                "host": self.HOST_ID, "recorder": self.HOST_ID, "target": "", "recorders": [],
                "devices": [{"id": i, "name": n} for i, n in self.devices],
                "peers": [{"id": k, "name": v["name"], "host": k == ref, "calibrated": v["cal"], "canRecord": False,
                           "route": "", "reference": k == ref} for k, v in live.items()],
                "results": self.results, "message": self.message,
                "interface": self.interface_index,
            }

    def handle(self, method, path, query, body, local=False):
        """Devolve (status, dict)."""
        with self.lock:
            if method == "GET" and path == "/api/calib":
                self.register(query, local)
                return 200, self.session_json()
            if method == "GET" and path == "/api/calib/interfaces":
                items, err = list_interfaces()
                return 200, {"interfaces": items, "selected": self.interface_index, "error": err}
            if method == "POST" and path == "/api/calib/interface":
                idx = (body or {}).get("index")
                self.interface_index = int(idx) if idx not in (None, "", "auto") else None
                return 200, self.session_json()
            if method == "POST" and path == "/api/calib/start":
                return self._start()
            if method == "POST" and path == "/api/calib/result":
                return 200, self.session_json()  # só o computador grava; apps não enviam resultado
        return 404, {"error": "Não encontrado."}

    def _start(self):
        if self.state == "running":
            return 400, {"error": "Já existe uma calibração em andamento."}
        if np is None:
            return 400, {"error": "Falta o numpy. Rode: pip install numpy sounddevice"}
        if self.recorder is record_with_sounddevice:
            try:
                import sounddevice  # noqa: F401
            except Exception:
                return 400, {"error": "Falta o sounddevice. Rode: pip install sounddevice (no Linux: sudo apt install libportaudio2)"}
        live = self.live()
        ref = self.reference_id()
        if not ref:
            return 400, {"error": "Abra o Sincrono neste computador: a página dele é a referência da calibração."}
        others = sorted(((v["name"], k) for k, v in live.items() if k != ref and not v["local"]))
        if not others:
            return 400, {"error": "Nenhum outro aparelho conectado. Abra o app nos celulares e entre nesta sessão."}
        self.devices = [(ref, live[ref]["name"])] + [(k, n) for n, k in others]
        self.id += 1
        self.state = "running"
        self.message = ""
        self.results = {}
        self.start_at = int(self.now_ms() + LEAD_MS)
        threading.Thread(target=self._run, args=(self.id, list(self.devices), self.start_at), daemon=True).start()
        return 200, self.session_json()

    def _run(self, session_id, devices, start_at):
        n = len(devices)
        total_ms = (REPEATS + 1) * n * SLOT_MS
        try:
            lead = 1.0
            wait = (start_at - lead * 1000 - self.now_ms()) / 1000.0
            if wait > 0:
                time.sleep(wait)
            duration = lead + total_ms / 1000.0 + 1.5
            rec, sr, rec_start = self.recorder(self.interface_index, duration, self.now_ms)
            mix = rec if rec.ndim == 1 else rec.sum(axis=1)   # a mesa pode entregar 2 canais: soma
            plan = {dev: [start_at + (r * n + i) * SLOT_MS for r in range(REPEATS + 1)] for i, (dev, _) in enumerate(devices)}
            raw = analyze(mix, sr, rec_start, plan)
            if float(np.max(np.abs(mix))) < MIN_PEAK:
                raise RuntimeError("Nenhum som chegou pela entrada de áudio do computador. Confira se a mesa está ligada "
                                   "ao computador por USB e se, nas configurações de som, a ENTRADA é a da mesa.")
            solved = solve(raw, devices[0][0])
            results, problems = {}, []
            for dev, name in devices:
                if dev in solved:
                    s = solved[dev]
                    results[dev] = {"name": name, "offsetMs": s["offsetMs"], "spreadMs": s["spreadMs"],
                                    "quality": s["quality"], "count": s["count"]}
                else:
                    problems.append("%s: %s" % (name, raw[dev]["error"] or "não detectado"))
            with self.lock:
                if self.id != session_id:
                    return
                self.results = results
                self.state = "done" if results else "failed"
                self.message = ("Não medidos: " + "; ".join(problems)) if problems else ""
        except Exception as e:
            with self.lock:
                if self.id == session_id:
                    self.state = "failed"
                    self.message = str(e)
