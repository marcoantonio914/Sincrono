"""Teste da calibração por cabo com gravação multicanal SIMULADA (sem placa de som)."""
import time, sys
import numpy as np
import calibracao_cabo as cc

SR = 48000
rng = np.random.default_rng(7)

def simulate(delays_ms, start_at, n_dev, input_latency_ms=37.0, rec_start_ms=None, noise=0.003, gains=None, order=None):
    """delays_ms[canal] = atraso real do aparelho (ms). order[canal] = índice do slot do aparelho."""
    chans = len(delays_ms)
    total = (cc.REPEATS + 1) * n_dev * cc.SLOT_MS
    dur = 1.0 + total / 1000 + 1.5
    rec_start = start_at - 1000 if rec_start_ms is None else rec_start_ms
    rec = noise * rng.standard_normal((int(dur * SR), chans))
    ch = cc.chirp(SR, 1.0)
    for c in range(chans):
        g = (gains or [0.5] * chans)[c]
        slot = (order or list(range(chans)))[c]
        for r in range(cc.REPEATS + 1):
            t = start_at + (r * n_dev + slot) * cc.SLOT_MS + delays_ms[c] + input_latency_ms
            i = (t - rec_start) * SR / 1000.0
            i0 = int(np.floor(i)); frac = i - i0
            # atraso fracionário por interpolação de fase (FFT)
            n = len(ch) + 64
            spec = np.fft.rfft(np.pad(ch, (0, 64)))
            spec *= np.exp(-2j * np.pi * np.fft.rfftfreq(n) * frac)
            seg = np.fft.irfft(spec, n)
            rec[i0:i0 + n, c] += g * seg
    return rec, rec_start

ok = True
def check(name, cond, extra=""):
    global ok
    ok &= bool(cond)
    print(("OK   " if cond else "FALHA"), name, extra)

# 1) 4 canais: referência (c0) + 3 aparelhos com atrasos conhecidos
true = [20.0, 32.3, 15.3, 58.9]      # atraso real de cada aparelho (ms); c0 = referência
start_at = 1_000_000.0
rec, rs = simulate(true, start_at, 4)
plan = {c: [start_at + (r * 4 + c) * cc.SLOT_MS for r in range(cc.REPEATS + 1)] for c in range(4)}
res = cc.solve(cc.analyze(rec, SR, rs, plan), 0)
for c in range(1, 4):
    want = true[c] - true[0]
    got = res[c]["offsetMs"]
    check(f"canal {c}: esperado {want:+.2f} ms", abs(got - want) < 0.05, f"medido {got:+.3f} ms, variação {res[c]['spreadMs']:.3f}, {res[c]['quality']}")

# 2) início da gravação com erro grande (não pode importar, só a referência)
rec, rs = simulate(true, start_at, 4, rec_start_ms=start_at - 1000, input_latency_ms=120.0)
res = cc.solve(cc.analyze(rec, SR, rs, plan), 0)
check("latência de entrada comum cancela", abs(res[1]["offsetMs"] - 12.3) < 0.05, f"{res[1]['offsetMs']:+.3f}")

# 3) canal sem sinal e canal estourado
rec, rs = simulate(true, start_at, 4, gains=[0.5, 0.0, 0.5, 5.0])
raw = cc.analyze(rec, SR, rs, plan)
check("canal mudo detectado", not raw[1]["ok"] and "nenhum sinal" in raw[1]["error"], raw[1]["error"])
check("canal estourado detectado", not raw[3]["ok"] and "saturou" in raw[3]["error"], raw[3]["error"])

# 4) referência ausente
try:
    cc.solve(raw, 1); check("referência ausente gera erro", False)
except ValueError as e:
    check("referência ausente gera erro", True, str(e)[:60])

# 5) muito ruído: deve cair em baixa confiança ou não detectar, nunca inventar
rec, rs = simulate(true, start_at, 4, noise=0.6, gains=[0.05] * 4)
raw = cc.analyze(rec, SR, rs, plan)
check("ruído forte não gera falso positivo", all((not v["ok"]) or v["spreadMs"] > 0 for v in raw.values()))

# 6) sessão completa com gravador falso (protocolo igual ao dos apps)
clock0 = time.time() * 1000
now = lambda: time.time() * 1000
hub = cc.CableCalibration(now, recorder=None)
cc.LEAD_MS = 1500  # acelera o teste
def fake_recorder(index, channels, duration_s, now_ms):
    n = len(hub.devices)
    order = [None] * channels
    for i, (d, _) in enumerate(hub.devices):
        order[hub.channel_of[d]] = i
    delays = [0.0] * channels
    truth = {"ref": 20.0, "tel1": 31.0, "tel2": 14.0}
    for d, _ in hub.devices:
        delays[hub.channel_of[d]] = truth[d]
    rec, rs = simulate(delays, hub.start_at, n, order=order)
    # a "gravação" começa 1 s antes do início, como no código real
    return rec, SR, rs
hub.recorder = fake_recorder
for d, n in (("ref", "Computador"), ("tel1", "Celular 1"), ("tel2", "Celular 2")):
    hub.register({"device": d, "name": n})
st, _ = hub.handle("POST", "/api/calib/map", {}, {"channels": {"ref": 0, "tel1": 1, "tel2": 2}, "reference": "ref", "interface": 0, "interfaceChannels": 3})
check("mapa aceito", st == 200)
st, j = hub.handle("POST", "/api/calib/map", {}, {"channels": {"ref": 0, "tel1": 0}})
check("canal repetido recusado", st == 400, j.get("error", ""))
hub.handle("POST", "/api/calib/map", {}, {"channels": {"ref": 0, "tel1": 1, "tel2": 2}, "reference": "ref", "interface": 0, "interfaceChannels": 3})
st, j = hub.handle("POST", "/api/calib/start", {}, {})
check("sessão iniciada", st == 200 and j["state"] == "running" and j["cable"] and [d["id"] for d in j["devices"]] == ["ref", "tel1", "tel2"])
for _ in range(60):
    time.sleep(0.5)
    st, j = hub.handle("GET", "/api/calib", {"device": "tel1", "name": "Celular 1"}, None)
    if j["state"] != "running": break
check("sessão terminou", j["state"] == "done", j.get("message", ""))
r = j["results"]
check("resultado celular 1 = +11 ms", abs(r["tel1"]["offsetMs"] - 11.0) < 0.05, f"{r['tel1']['offsetMs']:+.3f}")
check("resultado celular 2 = -6 ms", abs(r["tel2"]["offsetMs"] + 6.0) < 0.05, f"{r['tel2']['offsetMs']:+.3f}")
check("referência = 0", r["ref"]["offsetMs"] == 0.0)
print("\nTUDO CERTO" if ok else "\nHÁ FALHAS")
sys.exit(0 if ok else 1)
