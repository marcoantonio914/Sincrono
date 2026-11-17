"""Teste da calibração por soma (mesa) com gravação SIMULADA, sem placa de som."""
import sys
import time
import numpy as np
import calibracao_cabo as cc

SR = 48000
rng = np.random.default_rng(7)
ok = True


def check(name, cond, extra=""):
    global ok
    ok &= bool(cond)
    print(("OK   " if cond else "FALHA"), name, extra)


def frac_delay(sig, frac):
    n = len(sig) + 64
    spec = np.fft.rfft(np.pad(sig, (0, 64)))
    spec *= np.exp(-2j * np.pi * np.fft.rfftfreq(n) * frac)
    return np.fft.irfft(spec, n)


def simulate_mix(delays, start_at, gains=None, input_latency_ms=37.0, noise=0.003, rec_lead_ms=1000, channels=1):
    """delays: lista de atrasos reais (ms) dos aparelhos, na ordem dos slots. Todos somados em 1 ou 2 canais."""
    n = len(delays)
    total = (cc.REPEATS + 1) * n * cc.SLOT_MS
    dur = rec_lead_ms / 1000 + total / 1000 + 1.5
    rec_start = start_at - rec_lead_ms
    rec = noise * rng.standard_normal((int(dur * SR), channels))
    ch = cc.chirp(SR, 1.0)
    for slot, d in enumerate(delays):
        g = (gains or [0.5] * n)[slot]
        for r in range(cc.REPEATS + 1):
            t = start_at + (r * n + slot) * cc.SLOT_MS + d + input_latency_ms
            i = (t - rec_start) * SR / 1000.0
            i0 = int(np.floor(i))
            seg = frac_delay(ch, i - i0)
            for c in range(channels):
                rec[i0:i0 + len(seg), c] += g * seg / channels * (1.0 if channels == 1 else 1.0)
    return rec, rec_start


def run_plan(rec, rs, n, start_at, ids):
    mix = rec if rec.ndim == 1 else rec.sum(axis=1)
    plan = {ids[s]: [start_at + (r * n + s) * cc.SLOT_MS for r in range(cc.REPEATS + 1)] for s in range(n)}
    return cc.analyze(mix, SR, rs, plan)


start_at = 2_000_000.0

# 1) 11 aparelhos (computador + 10 celulares) somados num único sinal
true = [20.0] + [float(x) for x in rng.uniform(-30, 90, size=10)]
ids = ["pc"] + ["tel%d" % i for i in range(1, 11)]
rec, rs = simulate_mix(true, start_at)
res = cc.solve(run_plan(rec[:, 0], rs, 11, start_at, ids), "pc")
worst = max(abs(res[ids[i]]["offsetMs"] - (true[i] - true[0])) for i in range(1, 11))
check("11 aparelhos numa mistura: todos medidos", len(res) == 11)
check("erro máximo < 0,05 ms", worst < 0.05, f"pior erro {worst:.4f} ms")
check("confiança alta em todos", all(r["quality"] == "high" for r in res.values()))

# 2) a mesa entrega 2 canais (soma L+R); latência de entrada e início da gravação diferentes
rec, rs = simulate_mix(true[:4], start_at, channels=2, input_latency_ms=120.0, rec_lead_ms=700)
res = cc.solve(run_plan(rec, rs, 4, start_at, ids[:4]), "pc")
check("2 canais somados + latência de entrada alta", all(abs(res[ids[i]]["offsetMs"] - (true[i] - true[0])) < 0.05 for i in range(1, 4)))

# 3) um aparelho mudo, outro estourado, outro com cabo fraco
rec, rs = simulate_mix(true[:5], start_at, gains=[0.5, 0.0, 0.5, 5.0, 0.5])
raw = run_plan(rec[:, 0], rs, 5, start_at, ids[:5])
check("aparelho mudo identificado", "não chegou som" in raw["tel1"]["error"], raw["tel1"]["error"][:50])
check("aparelho estourado identificado", "estourou" in raw["tel3"]["error"], raw["tel3"]["error"][:50])
check("os demais continuam medidos", raw["pc"]["ok"] and raw["tel2"]["ok"] and raw["tel4"]["ok"])

# 4) referência muda: erro claro
rec, rs = simulate_mix(true[:3], start_at, gains=[0.0, 0.5, 0.5])
try:
    cc.solve(run_plan(rec[:, 0], rs, 3, start_at, ids[:3]), "pc"); check("referência muda gera erro", False)
except ValueError as e:
    check("referência muda gera erro", True, str(e)[:55])

# 5) ruído forte: sem falso positivo
rec, rs = simulate_mix(true[:3], start_at, noise=0.6, gains=[0.05] * 3)
raw = run_plan(rec[:, 0], rs, 3, start_at, ids[:3])
check("ruído forte não gera medida falsa", not any(v["ok"] and v["spreadMs"] < 0.5 for v in raw.values()))

# 6) sessão completa pelo protocolo (como celular/página): um clique = todos
now = lambda: time.time() * 1000
hub = cc.CableCalibration(now)
cc.LEAD_MS = 1500
truth = {"ref": 20.0, "tel1": 31.0, "tel2": 14.0}


def fake_recorder(index, duration_s, now_ms):
    delays = [truth[d] for d, _ in hub.devices]
    rec, rs = simulate_mix(delays, hub.start_at, channels=2)
    return rec, SR, rs


hub.recorder = fake_recorder
st, j = hub.handle("POST", "/api/calib/start", {}, {})
check("sem referência: erro claro", st == 400 and "Abra o Sincrono neste computador" in j["error"], j.get("error", "")[:40])
hub.handle("GET", "/api/calib", {"device": "ref", "name": "Computador"}, None, local=True)
st, j = hub.handle("POST", "/api/calib/start", {}, {})
check("sem celulares: erro claro", st == 400 and "Nenhum outro aparelho" in j["error"], j.get("error", "")[:40])
for d, n in (("tel2", "Celular 2"), ("tel1", "Celular 1")):
    hub.handle("GET", "/api/calib", {"device": d, "name": n}, None)
hub.handle("GET", "/api/calib", {"device": "ref", "name": "Computador"}, None, local=True)
st, j = hub.handle("POST", "/api/calib/start", {"initiator": "tel1"}, {"initiator": "tel1"})  # o botão do celular
check("celular inicia a calibração", st == 200 and j["state"] == "running" and j["cable"], str(st))
check("referência primeiro, depois por nome", [d["id"] for d in j["devices"]] == ["ref", "tel1", "tel2"])
for _ in range(60):
    time.sleep(0.5)
    hub.handle("GET", "/api/calib", {"device": "tel1", "name": "Celular 1"}, None)
    hub.handle("GET", "/api/calib", {"device": "tel2", "name": "Celular 2"}, None)
    hub.handle("GET", "/api/calib", {"device": "ref", "name": "Computador"}, None, local=True)
    st, j = hub.handle("GET", "/api/calib", {"device": "tel1", "name": "Celular 1"}, None)
    if j["state"] != "running":
        break
check("sessão terminou", j["state"] == "done", j.get("message", ""))
r = j["results"]
check("celular 1 = +11 ms", abs(r["tel1"]["offsetMs"] - 11.0) < 0.05, f"{r['tel1']['offsetMs']:+.3f}")
check("celular 2 = -6 ms", abs(r["tel2"]["offsetMs"] + 6.0) < 0.05, f"{r['tel2']['offsetMs']:+.3f}")
check("referência = 0", r["ref"]["offsetMs"] == 0.0)
print("\nTUDO CERTO" if ok else "\nHÁ FALHAS")
sys.exit(0 if ok else 1)
