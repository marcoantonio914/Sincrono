#!/usr/bin/env python3
"""
Simulador de muitos aparelhos para o Sincrono (sem aparelhos reais).

Cada aparelho virtual faz o mesmo que o app: mede o relógio do servidor (POST /api/sync),
mantém as conexões de eventos (/events e /events/audio do seu grupo) e consulta o servidor
(/api/audio, /api/mix, /api/groups, /api/calib). Ele mede:

  * latência de cada tipo de requisição (mediana, p95, máximo) e erros;
  * atraso de distribuição dos comandos: do instante em que o anfitrião emite play/pause
    até cada aparelho receber o evento (mediana, p95, máximo, quantos não receberam);
  * erro da estimativa de relógio de cada aparelho (só quando o servidor roda neste mesmo
    computador, onde o relógio verdadeiro é conhecido), com relógios locais com deslocamento
    e deriva aleatórios, jitter e perda de pacotes injetados e, opcionalmente, assimetria.

Exemplos:
    python3 simulador.py --spawn --devices 100 --groups 4 --duration 30
    python3 simulador.py --host 192.168.0.10:3000 --devices 30 --duration 20   # celular ou outro PC

Só usa a biblioteca padrão (Python 3.8+). Com --spawn sobe o servidor do computador numa
porta livre e também informa o uso de CPU/memória dele.
"""
import argparse
import asyncio
import json
import os
import random
import socket
import statistics
import subprocess
import sys
import time
from pathlib import Path

try:
    import resource
except ImportError:  # Windows
    resource = None


def wall_ms():
    return time.time() * 1000.0


def pct(values, p):
    if not values:
        return float("nan")
    v = sorted(values)
    return v[min(len(v) - 1, int(round(p / 100.0 * (len(v) - 1))))]


class Stats:
    def __init__(self):
        self.lat = {}       # endpoint -> [ms]
        self.errors = {}    # endpoint -> n
        self.fanout = []    # ms entre emitir o comando e o aparelho receber
        self.fanout_missed = 0
        self.expected_events = 0
        self.clock_err = [] # ms (valor absoluto, última estimativa de cada aparelho)
        self.clock_err_t = []  # todas as amostras de erro depois do aquecimento
        self.requests = 0

    def ok(self, ep, ms):
        self.requests += 1
        self.lat.setdefault(ep, []).append(ms)

    def err(self, ep):
        self.requests += 1
        self.errors[ep] = self.errors.get(ep, 0) + 1


class Http:
    """Cliente HTTP/1.1 mínimo com conexão persistente, sobre asyncio."""

    def __init__(self, host, port):
        self.host, self.port = host, port
        self.r = self.w = None

    async def _open(self):
        self.r, self.w = await asyncio.wait_for(asyncio.open_connection(self.host, self.port), 5)

    async def close(self):
        if self.w:
            self.w.close()
        self.r = self.w = None

    async def request(self, method, path, body=None, timeout=6.0):
        data = b"" if body is None else json.dumps(body).encode()
        head = (f"{method} {path} HTTP/1.1\r\nHost: {self.host}\r\nContent-Length: {len(data)}\r\n"
                f"Content-Type: application/json\r\nConnection: keep-alive\r\n\r\n").encode()
        for attempt in (0, 1):
            try:
                if self.w is None:
                    await self._open()
                self.w.write(head + data)
                await self.w.drain()
                status_line = await asyncio.wait_for(self.r.readline(), timeout)
                if not status_line:
                    raise ConnectionError("fechada")
                status = int(status_line.split()[1])
                length = 0
                while True:
                    line = await asyncio.wait_for(self.r.readline(), timeout)
                    if line in (b"\r\n", b"\n", b""):
                        break
                    if line.lower().startswith(b"content-length:"):
                        length = int(line.split(b":")[1])
                payload = await asyncio.wait_for(self.r.readexactly(length), timeout) if length else b""
                return status, payload
            except (OSError, asyncio.TimeoutError, ConnectionError, asyncio.IncompleteReadError):
                await self.close()
                if attempt:
                    raise


class Device:
    def __init__(self, idx, args, stats, group, truth):
        self.i = idx
        self.id = f"sim-{idx:04d}"
        self.name = f"Simulado {idx:03d}"
        self.args, self.st, self.group = args, stats, group
        self.truth = truth
        rnd = random.Random(idx)
        self.rnd = rnd
        self.off = rnd.uniform(-30000, 30000)        # relógio local adiantado/atrasado (ms)
        self.drift = rnd.uniform(-50e-6, 50e-6)      # deriva do relógio local
        self.t_start = wall_ms()
        self.samples = []                            # (rtt, offset)
        self.est = None

    def local_ms(self):
        t = wall_ms()
        return t + self.off + (t - self.t_start) * self.drift

    async def net(self, up=True):
        a = self.args
        base = a.rtt_ms / 2.0
        extra = a.asymmetry_ms / 2.0 * (1 if up else -1)
        d = max(0.0, base + extra + self.rnd.expovariate(1.0 / a.jitter_ms) if a.jitter_ms > 0 else base + extra)
        await asyncio.sleep(d / 1000.0)

    async def lost(self):
        if self.args.loss > 0 and self.rnd.random() < self.args.loss:
            await asyncio.sleep(1.0)  # pacote perdido: o app só tenta de novo depois
            return True
        return False

    async def call(self, http, ep, method, path, body=None):
        t0 = time.perf_counter()
        if await self.lost():
            self.st.err(ep)
            return None
        await self.net(True)
        try:
            status, payload = await http.request(method, path, body)
        except Exception:
            self.st.err(ep)
            return None
        await self.net(False)
        ms = (time.perf_counter() - t0) * 1000
        if status >= 500:
            self.st.err(ep)
            return None
        self.st.ok(ep, ms)
        return status, payload

    async def sync_loop(self, stop):
        http = Http(*self.args.target)
        n = 0
        while not stop.is_set():
            t0 = self.local_ms()
            t0m = time.perf_counter()
            if await self.lost():
                self.st.err("sync")
                continue
            await self.net(True)
            try:
                status, payload = await http.request("POST", "/api/sync", {"clientSentAt": 0})
                await self.net(False)
                t3 = self.local_ms()
                rtt = (time.perf_counter() - t0m) * 1000
                o = json.loads(payload)
                t1, t2 = o["serverReceivedAt"], o["serverSentAt"]
                offset = ((t1 - t0) + (t2 - t3)) / 2.0     # servidor - local
                self.st.ok("sync", rtt)
                self.samples.append((rtt, offset))
                self.samples = self.samples[-12:]
                self.est = min(self.samples, key=lambda s: s[0])[1]   # menor RTT das últimas 12 (como o app)
                if n >= 12 and self.args.local:
                    true = -(self.off + (wall_ms() - self.t_start) * self.drift)
                    self.st.clock_err_t.append(abs(self.est - true))
            except Exception:
                self.st.err("sync")
            n += 1
            await asyncio.sleep(0.2 if n < 12 else 2.0)
        await http.close()
        if self.args.local and self.est is not None:
            true = -(self.off + (wall_ms() - self.t_start) * self.drift)
            self.st.clock_err.append(abs(self.est - true))

    async def sse_loop(self, stop, path, handler):
        while not stop.is_set():
            try:
                r, w = await asyncio.wait_for(asyncio.open_connection(*self.args.target), 5)
                w.write(f"GET {path} HTTP/1.1\r\nHost: x\r\nAccept: text/event-stream\r\n\r\n".encode())
                await w.drain()
                while not stop.is_set():
                    line = await asyncio.wait_for(r.readline(), 15)
                    if not line:
                        break
                    if line.startswith(b"data:"):
                        handler(line[5:].strip())
                w.close()
            except Exception:
                pass
            if not stop.is_set():
                await asyncio.sleep(1)

    def on_state(self, raw):
        try:
            s = json.loads(raw)
        except ValueError:
            return
        key = ("state", s.get("revision"))
        issued = self.truth["issued"].get(key)
        if issued is None and self.truth.get("pending") is not None:
            issued = self.truth["issued"][key] = self.truth["pending"]
        if issued is not None and (key, self.id) not in self.truth["seen"]:
            self.truth["seen"].add((key, self.id))
            self.st.fanout.append(wall_ms() - issued)

    def on_audio(self, raw):
        try:
            s = json.loads(raw)
        except ValueError:
            return
        key = ("audio", self.group, s.get("revision"))
        issued = self.truth["issued"].get(key)
        if issued is None and self.truth.get("pending") is not None:
            issued = self.truth["issued"][key] = self.truth["pending"]
        if issued is not None and (key, self.id) not in self.truth["seen"]:
            self.truth["seen"].add((key, self.id))
            self.st.fanout.append(wall_ms() - issued)

    async def poll_loop(self, stop, ep, path_fn, period):
        http = Http(*self.args.target)
        await asyncio.sleep(self.rnd.uniform(0, period))
        while not stop.is_set():
            await self.call(http, ep, "GET", path_fn())
            await asyncio.sleep(period)
        await http.close()

    async def run(self, stop):
        q = lambda s: s
        tasks = [
            self.sync_loop(stop),
            self.sse_loop(stop, "/events", self.on_state),
            self.sse_loop(stop, f"/events/audio?group={self.group}", self.on_audio),
            self.poll_loop(stop, "audio", lambda: f"/api/audio?device={self.id}&ready=1&group={self.group}", 1.5),
            self.poll_loop(stop, "mix", lambda: "/api/mix", 1.0),
            self.poll_loop(stop, "groups", lambda: "/api/groups", 1.0),
            self.poll_loop(stop, "calib", lambda: f"/api/calib?device={self.id}&name={self.name.replace(' ', '+')}", 1.5),
        ]
        await asyncio.gather(*tasks)


def free_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    p = s.getsockname()[1]
    s.close()
    return p


def cpu_seconds(pid):
    try:
        f = open(f"/proc/{pid}/stat").read().rsplit(")", 1)[1].split()
        return (int(f[11]) + int(f[12])) / os.sysconf("SC_CLK_TCK")
    except (OSError, IndexError, ValueError):
        return None


def rss_mb(pid):
    try:
        for line in open(f"/proc/{pid}/status"):
            if line.startswith("VmRSS"):
                return int(line.split()[1]) / 1024
    except OSError:
        pass
    return None


async def controller(args, stats, truth, stop, groups):
    """O anfitrião: emite comandos de metrônomo e de áudio e anota o instante de emissão."""
    http = Http(*args.target)
    for g in range(2, groups + 1):
        await http.request("POST", "/api/groups", {"op": "add", "name": f"Grupo {g}"})
    blob = os.urandom(20000)
    # um arquivo pequeno por grupo (para as consultas devolverem estado real)
    for g in range(1, groups + 1):
        r, w = await asyncio.open_connection(*args.target)
        w.write((f"POST /api/audio/upload?group=g{g}&name=sim.wav HTTP/1.1\r\nHost: x\r\nContent-Length: {len(blob)}\r\nConnection: close\r\n\r\n").encode() + blob)
        await w.drain()
        await r.read()
        w.close()
    return http


async def main_async(args):
    stats = Stats()
    truth = {"issued": {}, "seen": set()}
    stop = asyncio.Event()
    groups = args.groups
    ctl = await controller(args, stats, truth, stop, groups)
    devices = []
    for i in range(args.devices):
        g = 1 + (i % groups)
        devices.append(Device(i, args, stats, f"g{g}", truth))
    tasks = []
    # os aparelhos entram escalonados, como pessoas conectando
    async def start_all():
        for d in devices:
            tasks.append(asyncio.ensure_future(d.run(stop)))
            await asyncio.sleep(args.ramp / max(1, args.devices))
    starter = asyncio.ensure_future(start_all())
    await asyncio.sleep(args.ramp + 1.0)
    # atribui aparelhos aos grupos pelo painel (como o anfitrião faria)
    for d in devices:
        if d.group != "g1":
            await ctl.request("POST", "/api/groups", {"op": "assign", "device": d.id, "group": d.group})
    t_end = time.time() + args.duration
    k = 0
    while time.time() < t_end:
        await asyncio.sleep(min(args.command_every, max(0.1, t_end - time.time())))
        if time.time() >= t_end:
            break
        # O instante de emissão é anotado ANTES de enviar; o primeiro evento novo de cada
        # revisão que chega durante a janela define a qual comando ele pertence.
        truth["pending"] = wall_ms()
        kind = "play" if k % 2 == 0 else "pause"
        await ctl.request("POST", "/api/command", {"type": kind, "bpm": 120, "beatsPerBar": 4})
        await ctl.request("POST", "/api/audio/cmd?group=all", {"type": kind})
        await asyncio.sleep(2.0)
        truth["pending"] = None
        k += 1
        await asyncio.sleep(min(2.5, args.command_every / 2))
    stop.set()
    await asyncio.sleep(1.0)
    for t in tasks:
        t.cancel()
    await asyncio.gather(*tasks, return_exceptions=True)
    starter.cancel()
    stats.expected_events = k * len(devices) * 2
    return stats, k


def report(args, stats, commands, server_pid, cpu0, t0):
    dur = time.time() - t0
    print("\n=== Resultado do simulador ===")
    print(f"aparelhos: {args.devices}  grupos: {args.groups}  duração: {args.duration:.0f}s  "
          f"RTT injetado: {args.rtt_ms:.0f} ms  jitter: {args.jitter_ms:.0f} ms  perda: {args.loss*100:.1f}%  assimetria: {args.asymmetry_ms:.0f} ms")
    print(f"requisições: {stats.requests}  ({stats.requests / max(1, dur):.0f}/s)  erros: {sum(stats.errors.values())}")
    print("\nlatência por requisição (ms, inclui o atraso de rede injetado):")
    print(f"  {'tipo':8s} {'n':>6s} {'mediana':>9s} {'p95':>9s} {'máx':>9s} {'erros':>6s}")
    for ep in sorted(stats.lat):
        v = stats.lat[ep]
        print(f"  {ep:8s} {len(v):6d} {statistics.median(v):9.1f} {pct(v,95):9.1f} {max(v):9.1f} {stats.errors.get(ep,0):6d}")
    if stats.fanout:
        v = stats.fanout
        print(f"\ndistribuição de comandos ({commands} rodadas, {len(v)} recebimentos de eventos):")
        missing = max(0, getattr(stats, "expected_events", 0) - len(v))
        print(f"  eventos esperados {getattr(stats, 'expected_events', 0)}, não recebidos na janela de 2 s: {missing} (inclui aparelhos que perderam a conexão)")
        print(f"  atraso do comando até o aparelho: mediana {statistics.median(v):.1f} ms, p95 {pct(v,95):.1f} ms, máx {max(v):.1f} ms")
        print("  (o comando é agendado ~2,5 a 3 s à frente; só atrasos perto disso seriam problema)")
    if args.local and stats.clock_err_t:
        v = stats.clock_err_t
        print("\nerro da estimativa de relógio (ms; verdadeiro conhecido porque o servidor está neste computador):")
        print(f"  todas as amostras: mediana {statistics.median(v):.2f}, p95 {pct(v,95):.2f}, máx {max(v):.2f}")
        if stats.clock_err:
            print(f"  última estimativa de cada aparelho: mediana {statistics.median(stats.clock_err):.2f}, p95 {pct(stats.clock_err,95):.2f}, máx {max(stats.clock_err):.2f}")
        if args.asymmetry_ms:
            print(f"  (assimetria injetada de {args.asymmetry_ms:.1f} ms causa erro fixo ≈ {args.asymmetry_ms/2:.1f} ms: nenhuma medição por troca de mensagens a enxerga)")
    if server_pid:
        cpu1 = cpu_seconds(server_pid)
        if cpu1 is not None and cpu0 is not None:
            print(f"\nservidor: CPU média {(cpu1 - cpu0) / max(1, dur) * 100:.0f}% de um núcleo, memória {rss_mb(server_pid) or 0:.0f} MB")
    print("\nLeitura: se a latência do 'sync' e a distribuição de comandos ficam estáveis (p95 próximo da mediana)")
    print("e os erros ~0 com o número de aparelhos desejado, o servidor aguenta essa carga NESTE cenário simulado.")
    print("Isto NÃO simula o Wi-Fi real (colisões, retransmissão, aparelhos dormindo): teste também com aparelhos de verdade.")


def main():
    ap = argparse.ArgumentParser(description="Simulador de muitos aparelhos do Sincrono.")
    ap.add_argument("--host", help="endereço do servidor (IP:porta). Sem isso, use --spawn.")
    ap.add_argument("--spawn", action="store_true", help="sobe o servidor do computador numa porta livre para o teste")
    ap.add_argument("--devices", type=int, default=50)
    ap.add_argument("--groups", type=int, default=3)
    ap.add_argument("--duration", type=float, default=30, help="segundos de teste após todos entrarem")
    ap.add_argument("--ramp", type=float, default=5, help="segundos para os aparelhos entrarem (escalonado)")
    ap.add_argument("--command-every", type=float, default=8, help="segundos entre comandos do anfitrião")
    ap.add_argument("--rtt-ms", type=float, default=4, help="RTT base injetado (rede)")
    ap.add_argument("--jitter-ms", type=float, default=2, help="jitter médio (exponencial) por sentido")
    ap.add_argument("--loss", type=float, default=0.0, help="probabilidade de perda por requisição (0 a 1)")
    ap.add_argument("--asymmetry-ms", type=float, default=0, help="diferença fixa ida - volta (ms)")
    args = ap.parse_args()

    if resource:
        soft, hard = resource.getrlimit(resource.RLIMIT_NOFILE)
        need = args.devices * 6 + 200
        if soft < need:
            try:
                resource.setrlimit(resource.RLIMIT_NOFILE, (min(hard, need), hard))
            except (ValueError, OSError):
                print(f"AVISO: limite de arquivos abertos ({soft}) pode ser baixo para {args.devices} aparelhos (use ulimit -n {need}).")

    proc = None
    server_pid = None
    if args.spawn:
        port = free_port()
        here = Path(__file__).resolve().parent
        proc = subprocess.Popen([sys.executable, str(here / "sincrono_server.py"), "--port", str(port), "--cache", str(here / ".sim-cache")],
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        server_pid = proc.pid
        args.target = ("127.0.0.1", port)
        args.local = True
        for _ in range(50):
            try:
                socket.create_connection(args.target, 0.2).close()
                break
            except OSError:
                time.sleep(0.1)
    elif args.host:
        host, _, port = args.host.partition(":")
        args.target = (host, int(port or 3000))
        args.local = host in ("127.0.0.1", "localhost")
    else:
        ap.error("informe --host IP:porta ou --spawn")
    cpu0 = cpu_seconds(server_pid) if server_pid else None
    t0 = time.time()
    try:
        stats, commands = asyncio.run(main_async(args))
        report(args, stats, commands, server_pid, cpu0, t0)
    finally:
        if proc:
            proc.terminate()


if __name__ == "__main__":
    main()
