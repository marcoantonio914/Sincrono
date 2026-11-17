"""Calibração por cabo com interface de áudio multicanal (ex.: Teyun Q-12, 2 canais; ou 8, 16 canais...).

Como funciona
  * Cada aparelho (celular ou página do navegador) é ligado por cabo a UMA entrada da interface.
  * Os aparelhos tocam um "chirp" (2–6 kHz, 40 ms; o mesmo da calibração acústica) nos horários
    combinados, cada um no seu slot. A interface grava todos os canais ao mesmo tempo.
  * Para cada canal, medimos o instante exato em que o chirp chegou (filtro casado + envelope +
    interpolação parabólica). Como todos os canais compartilham o relógio da interface, a
    diferença entre canais é a diferença de latência entre os aparelhos.
  * A referência é um aparelho escolhido (normalmente a página do próprio computador, ligada a
    um canal): o ajuste de cada aparelho = (chegada dele - horário combinado) - (o mesmo da
    referência). O atraso de entrada da interface e o horário de início da gravação cancelam.

Com N canais, N aparelhos são medidos numa única rodada. Mais aparelhos que canais: o painel
pede rodadas, trocando os cabos entre elas (cada rodada mede até N aparelhos + a referência).

Dependências (só para calibrar): numpy e sounddevice (pip install numpy sounddevice).
O servidor continua funcionando sem elas; só a calibração por cabo fica indisponível.
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


def analyze(recording, sample_rate, rec_start_ms, channel_plan, repeats=REPEATS):
    """Analisa uma gravação multicanal.

    recording: array (amostras, canais), valores -1..1
    rec_start_ms: horário (relógio do servidor) do 1º quadro gravado (só precisa ser coerente entre canais)
    channel_plan: {canal(0-based): [horários combinados (ms) dos chirps desse canal]}
    Devolve {canal: dict(ok, offsetMs (bruto: chegada - combinado), spreadMs, count, peak, clipped, erro)}.
    """
    out = {}
    for ch, times in channel_plan.items():
        info = dict(ok=False, offsetMs=0.0, spreadMs=0.0, count=0, peak=0.0, clipped=False, error="")
        out[ch] = info
        if recording.ndim == 1:
            x = recording
        elif ch >= recording.shape[1]:
            info["error"] = "a interface não tem este canal"
            continue
        else:
            x = recording[:, ch]
        info["peak"] = float(np.max(np.abs(x))) if len(x) else 0.0
        info["clipped"] = info["peak"] >= CLIP_PEAK
        if info["clipped"]:
            info["error"] = "sinal saturou (estourou): diminua o ganho da interface ou o volume do aparelho"
            continue
        if info["peak"] < MIN_PEAK:
            info["error"] = "quase nenhum sinal neste canal: confira o cabo, o ganho e o volume"
            continue
        offsets = []
        for t in times:
            center = (t - rec_start_ms) * sample_rate / 1000.0
            found = detect_arrival(x, sample_rate, center, SEARCH_BEFORE_MS * sample_rate / 1000.0,
                                   SEARCH_AFTER_MS * sample_rate / 1000.0)
            if found is None:
                continue
            idx, ratio = found
            if ratio < MIN_RATIO:
                continue
            arrival_ms = rec_start_ms + idx * 1000.0 / sample_rate
            offsets.append(arrival_ms - t)
        info["count"] = len(offsets)
        if len(offsets) < max(2, (repeats + 1) // 2 + 1):
            info["error"] = "o som do aparelho não foi detectado de forma confiável neste canal"
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
    """plan_results: saída de analyze(). Devolve {canal: dict(offsetMs relativo à referência, spreadMs, quality, count)}
    ou levanta ValueError se a referência não foi medida."""
    ref = plan_results.get(reference_channel)
    if not ref or not ref["ok"]:
        raise ValueError("A referência (o computador ligado à interface) não foi medida: "
                         + ((ref or {}).get("error") or "confira o cabo dela"))
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

def list_interfaces():
    try:
        import sounddevice as sd
    except Exception as e:
        return [], "Instale as dependências: pip install numpy sounddevice (%s)" % e
    items = []
    for i, d in enumerate(sd.query_devices()):
        if d.get("max_input_channels", 0) > 0:
            ch = d["max_input_channels"]
            if d["name"] in ("pipewire", "pulse", "default", "sysdefault"):
                ch = min(ch, 2)  # servidores de som mostram dezenas de canais virtuais; a interface real tem poucos
            items.append({"index": i, "name": d["name"], "channels": ch,
                          "sampleRate": int(d.get("default_samplerate") or 48000)})
    return items, ""


def record_with_sounddevice(device_index, channels, duration_s, now_ms):
    """Grava `channels` canais por duration_s. Devolve (array, sample_rate, rec_start_ms)."""
    import sounddevice as sd
    info = sd.query_devices(device_index)
    sr = int(info.get("default_samplerate") or 48000)
    start_ms = now_ms()
    data = sd.rec(int(duration_s * sr), samplerate=sr, channels=channels, device=device_index, dtype="float32")
    sd.wait()
    return data, sr, start_ms


class CableCalibration:
    """Sessão de calibração por cabo no servidor do computador (mesmo protocolo /api/calib dos apps)."""

    HOST_ID = "pc-host"

    def __init__(self, now_ms, recorder=None):
        self.now_ms = now_ms
        self.recorder = recorder or record_with_sounddevice
        self.lock = threading.RLock()
        self.peers = {}          # device -> dict(name, seen, auto, cal)
        self.channel_of = {}     # device -> canal (0-based)
        self.reference = ""      # device de referência
        self.interface_index = None
        self.interface_channels = 0
        self.id = 0
        self.state = "idle"
        self.message = ""
        self.start_at = 0
        self.devices = []        # [(id, name)] — referência primeiro
        self.results = {}
        self.last_detail = {}

    # ---- protocolo
    def register(self, q):
        device = q.get("device", "")
        if not device:
            return
        with self.lock:
            p = self.peers.setdefault(device, {"name": "Aparelho", "seen": 0.0, "auto": 0.0, "cal": False})
            p["name"] = q.get("name") or "Aparelho"
            p["seen"] = self.now_ms()
            try:
                p["auto"] = float(q.get("auto", 0) or 0)
            except ValueError:
                p["auto"] = 0.0
            p["cal"] = q.get("cal") == "1"

    def live(self):
        now = self.now_ms()
        return {k: v for k, v in self.peers.items() if now - v["seen"] < PEER_TTL_MS}

    def session_json(self):
        with self.lock:
            live = self.live()
            return {
                "id": self.id, "state": self.state, "mode": "all", "cable": True, "amp": CABLE_AMPLITUDE,
                "startAt": self.start_at, "slotMs": SLOT_MS, "repeats": REPEATS,
                "host": self.HOST_ID, "recorder": self.HOST_ID, "target": "", "recorders": [],
                "devices": [{"id": i, "name": n} for i, n in self.devices],
                "peers": [{"id": k, "name": v["name"], "host": False, "calibrated": v["cal"], "canRecord": False,
                           "route": "", "channel": self.channel_of.get(k, -1), "reference": k == self.reference}
                          for k, v in live.items()],
                "results": self.results, "message": self.message,
                "interface": {"index": self.interface_index, "channels": self.interface_channels},
            }

    def handle(self, method, path, query, body):
        """Devolve (status, dict)."""
        with self.lock:
            if method == "GET" and path == "/api/calib":
                self.register(query)
                return 200, self.session_json()
            if method == "GET" and path == "/api/calib/interfaces":
                items, err = list_interfaces()
                return 200, {"interfaces": items, "error": err}
            if method == "POST" and path == "/api/calib/map":
                return self._set_map(body or {})
            if method == "POST" and path == "/api/calib/start":
                return self._start()
            if method == "POST" and path == "/api/calib/result":
                return 200, self.session_json()  # só o computador grava; apps não enviam resultado
        return 404, {"error": "Não encontrado."}

    def _set_map(self, body):
        ch = body.get("channels", {})
        if not isinstance(ch, dict):
            return 400, {"error": "Formato inválido."}
        clean = {}
        for dev, c in ch.items():
            try:
                c = int(c)
            except (TypeError, ValueError):
                continue
            if c >= 0:
                clean[str(dev)] = c
        if len(set(clean.values())) != len(clean):
            return 400, {"error": "Dois aparelhos no mesmo canal. Cada aparelho precisa de um canal próprio."}
        self.channel_of = clean
        self.reference = str(body.get("reference") or "")
        if "interface" in body:
            self.interface_index = body["interface"]
        if "interfaceChannels" in body:
            try:
                self.interface_channels = int(body["interfaceChannels"])
            except (TypeError, ValueError):
                pass
        return 200, self.session_json()

    def _start(self):
        if self.state == "running":
            return 400, {"error": "Já existe uma calibração em andamento."}
        if np is None:
            return 400, {"error": "Falta o numpy. Rode: pip install numpy sounddevice"}
        live = self.live()
        mapped = [d for d in self.channel_of if d in live]
        if self.reference not in mapped:
            return 400, {"error": "Escolha a referência (normalmente a página deste computador) e ligue-a a um canal."}
        if len(mapped) < 2:
            return 400, {"error": "Ligue pelo menos um outro aparelho a um canal da interface."}
        if self.interface_index is None:
            return 400, {"error": "Escolha a interface de áudio."}
        if max(self.channel_of[d] for d in mapped) >= max(self.interface_channels, 1):
            return 400, {"error": "Há aparelho ligado a um canal que a interface não tem."}
        ordered = [self.reference] + [d for d in mapped if d != self.reference]
        self.devices = [(d, live[d]["name"]) for d in ordered]
        self.id += 1
        self.state = "running"
        self.message = ""
        self.results = {}
        self.start_at = int(self.now_ms() + LEAD_MS)
        threading.Thread(target=self._run, args=(self.id, list(self.devices)), daemon=True).start()
        return 200, self.session_json()

    def _run(self, session_id, devices):
        n = len(devices)
        total_ms = (REPEATS + 1) * n * SLOT_MS
        try:
            lead = 1.0
            wait = (self.start_at - lead * 1000 - self.now_ms()) / 1000.0
            if wait > 0:
                time.sleep(wait)
            duration = lead + total_ms / 1000.0 + 1.5
            rec, sr, rec_start = self.recorder(self.interface_index, max(self.interface_channels, 1), duration, self.now_ms)
            plan = {}
            ids = [d for d, _ in devices]
            for i, dev in enumerate(ids):
                plan[self.channel_of[dev]] = [self.start_at + (r * n + i) * SLOT_MS for r in range(REPEATS + 1)]
            raw = analyze(rec, sr, rec_start, plan)
            ref_ch = self.channel_of[self.reference]
            solved = solve(raw, ref_ch)
            results = {}
            problems = []
            for dev, name in devices:
                ch = self.channel_of[dev]
                if ch in solved:
                    s = solved[ch]
                    results[dev] = {"name": name, "offsetMs": s["offsetMs"], "spreadMs": s["spreadMs"],
                                    "quality": s["quality"], "count": s["count"]}
                else:
                    problems.append("%s (canal %d): %s" % (name, ch + 1, raw[ch]["error"] or "não detectado"))
            with self.lock:
                if self.id != session_id:
                    return
                self.results = results
                self.last_detail = raw
                self.state = "done" if results else "failed"
                self.message = "" if not problems else "Não medidos: " + "; ".join(problems)
                if not results:
                    self.message = self.message or "Nenhum aparelho foi medido."
        except Exception as e:  # erro de gravação, referência ausente etc.
            with self.lock:
                if self.id == session_id:
                    self.state = "failed"
                    self.message = str(e)
