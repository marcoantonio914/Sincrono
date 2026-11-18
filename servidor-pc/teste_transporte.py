"""Testa o transporte do áudio (tocar, pausar, parar) sem rede: tudo agendado no relógio do servidor,
então todos os aparelhos agem no MESMO instante."""
import json
import os
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sincrono_server as S

fails = 0
clock = [0.0]
S.now_ms = lambda: clock[0]


def check(cond, name, detail=""):
    global fails
    print(("OK    " if cond else "FALHA ") + name + (f"  [{detail}]" if detail and not cond else ""))
    if not cond:
        fails += 1


def make_hub():
    h = S.Hub(None)
    f = tempfile.NamedTemporaryFile(delete=False, suffix=".wav"); f.write(b"x"); f.close()
    h.set_file(f.name, "teste.wav", "audio/wav", 1)
    return h


def snap(h):
    return json.loads(h.snapshot())


# 1) tocar, pausar, voltar a tocar: retoma do ponto onde parou
h = make_hub(); clock[0] = 0.0
h.command({"type": "play"})
s = snap(h); check(s["playing"] and s["startAt"] == int(S.AUDIO_LEAD_MS), "tocar agenda o início com antecedência")
clock[0] = 5000.0
h.command({"type": "pause"})
s = snap(h); check(s["stopAt"] == int(5000 + S.AUDIO_STOP_MS), "pausar agenda a parada com antecedência")
clock[0] = 7000.0
s = snap(h); check(not s["playing"] and abs(s["positionMs"] - (6200 - 2500)) < 1, "depois da parada a posição fica onde parou", s["positionMs"])
h.command({"type": "play"})
s = snap(h); check(s["playing"] and abs(s["positionMs"] - 3700) < 1 and s["startAt"] == int(7000 + S.AUDIO_LEAD_MS), "voltar a tocar retoma do mesmo ponto, com início agendado")

# 2) parar com música tocando: parada agendada e volta ao início
clock[0] = 12000.0
h.command({"type": "stop"})
s = snap(h); check(s["playing"] and s["stopAt"] == int(12000 + S.AUDIO_STOP_MS) and s["resetOnStop"], "parar agenda a parada (não corta na hora) e marca volta ao início")
clock[0] = 13600.0
s = snap(h); check(not s["playing"] and s["positionMs"] == 0.0 and not s["resetOnStop"], "depois da parada: posição 0 e parado")

# 3) parar já parado/pausado volta ao início na hora
h = make_hub(); clock[0] = 0.0
h.command({"type": "seek", "positionMs": 8000.0})
h.command({"type": "stop"})
s = snap(h); check(not s["playing"] and s["positionMs"] == 0.0, "parar com a música parada volta ao início")

# 4) parar antes de começar (contagem inicial) cancela
h = make_hub(); clock[0] = 0.0
h.command({"type": "play"}); clock[0] = 500.0; h.command({"type": "stop"})
s = snap(h); check(not s["playing"] and s["positionMs"] == 0.0, "parar durante a contagem cancela o início")

# 5) tocar durante uma parada agendada cancela o "voltar ao início"
h = make_hub(); clock[0] = 0.0
h.command({"type": "play"}); clock[0] = 5000.0; h.command({"type": "stop"}); clock[0] = 5200.0
h.command({"type": "play"})
s = snap(h); check(s["playing"] and not s["resetOnStop"] and s["stopAt"] is None, "tocar depois de parar recomeça e limpa a marca")

# 6) pausa pendente + parar = vira parada
h = make_hub(); clock[0] = 0.0
h.command({"type": "play"}); clock[0] = 5000.0; h.command({"type": "pause"}); clock[0] = 5100.0; h.command({"type": "stop"})
clock[0] = 7000.0
s = snap(h); check(not s["playing"] and s["positionMs"] == 0.0, "parar depois de pausar (parada ainda agendada) volta ao início")

# 7) vários grupos recebem o MESMO instante
a, b = make_hub(), make_hub(); clock[0] = 100.0
at = 20000.0
a.command({"type": "play"}, at); b.command({"type": "play"}, at)
check(snap(a)["startAt"] == snap(b)["startAt"] == int(at + S.AUDIO_LEAD_MS), "tocar todos: mesmo início em todos os grupos")
clock[0] = 25000.0; at = 25000.0
a.command({"type": "stop"}, at); b.command({"type": "stop"}, at)
check(snap(a)["stopAt"] == snap(b)["stopAt"] == int(at + S.AUDIO_STOP_MS), "parar todos: mesma parada em todos os grupos")

print("\ntudo certo" if fails == 0 else f"\n{fails} falha(s)")
sys.exit(1 if fails else 0)
