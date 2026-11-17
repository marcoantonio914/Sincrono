"""Mostra, ao vivo, o nível de cada canal de ENTRADA da interface de áudio.

Uso:   python diagnostico_interface.py            (lista as interfaces e usa a primeira "USB")
       python diagnostico_interface.py 3          (usa a interface de número 3)
       python diagnostico_interface.py 13 2       (interface 13, com 2 canais)

Serve para descobrir o que chega em cada canal: toque algo no computador, ligue o cabo do celular,
mexa no pan da mesa, e veja qual canal "acende". Ctrl+C para sair.
"""
import sys
import time

import numpy as np
import sounddevice as sd

devs = sd.query_devices()
cands = [(i, d) for i, d in enumerate(devs) if d["max_input_channels"] > 0 and d["max_input_channels"] <= 64]
print("Interfaces com entrada:")
for i, d in cands:
    print("  %2d  %s  (%d canais)" % (i, d["name"], d["max_input_channels"]))

if len(sys.argv) > 1:
    idx = int(sys.argv[1])
else:
    usb = [i for i, d in cands if "usb" in d["name"].lower()]
    if not usb:
        sys.exit("Nenhuma interface USB encontrada; passe o número: python diagnostico_interface.py N")
    idx = usb[0]
d = devs[idx]
ch = d["max_input_channels"]
if len(sys.argv) > 2:
    ch = int(sys.argv[2])
elif d["name"] in ("pipewire", "pulse", "default", "sysdefault"):
    ch = 2  # servidores de som mostram dezenas de canais "virtuais"; a Teyun tem 2
sr = int(d["default_samplerate"])
print("\nUsando: %s — %d canais, %d Hz. Ctrl+C para sair.\n" % (d["name"], ch, sr))

peaks = np.zeros(ch)
started = False


def cb(indata, frames, t, status):
    global peaks
    peaks = np.maximum(peaks * 0.9, np.abs(indata).max(axis=0))


with sd.InputStream(device=idx, channels=ch, samplerate=sr, callback=cb):
    try:
        while True:
            time.sleep(0.15)
            lines = []
            for c in range(ch):
                n = int(min(1.0, peaks[c]) * 30)
                tag = " ESTOUROU" if peaks[c] >= 0.98 else ""
                lines.append("canal %d |%-30s| %.3f%s" % (c + 1, "#" * n, peaks[c], tag))
            if started:
                sys.stdout.write("\x1b[%dA" % ch)  # sobe o cursor e redesenha as barras
            started = True
            print("\n".join(lines))
    except KeyboardInterrupt:
        print("\nFim.")
