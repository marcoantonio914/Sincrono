#!/usr/bin/env python3
"""v38: esticar o áudio sem mudar o tom (WSOLA).   python3 tools/teste_esticar.py"""
import sys
from pathlib import Path
from playwright.sync_api import sync_playwright
RAIZ = Path(__file__).resolve().parent.parent
js = (RAIZ / "app/src/main/assets/web/stretch.js").read_text()
falhas = []
def check(n, ok, extra=""):
    print(("  ok  " if ok else " FALHA"), n, "" if ok else extra)
    if not ok: falhas.append(n)
with sync_playwright() as p:
    b = p.chromium.launch(); pg = b.new_page(); pg.goto("about:blank"); pg.add_script_tag(content=js)
    for f, hz in ((0.7, 880), (1.5, 440), (0.5, 660)):
        r = pg.evaluate("""async ([f, hz]) => {
          const sr = 22050, n = sr * 8, a = new Float32Array(n), b2 = new Float32Array(n);
          for (let i = 0; i < n; i++) { a[i] = 0.6 * Math.sin(2 * Math.PI * hz * i / sr) + 0.2 * Math.sin(2 * Math.PI * hz * 2 * i / sr); b2[i] = a[i] * 0.5; }
          const t0 = performance.now();
          const o = await window.sincronoStretch([a, b2], sr, f);
          const ms = performance.now() - t0;
          const x = o[0]; let cross = 0, first = -1, last = -1, cnt = 0;
          const lo = Math.floor(x.length * 0.2), hi = Math.floor(x.length * 0.8);   // miolo, sem as pontas
          for (let i = lo + 1; i < hi; i++) if (x[i - 1] < 0 && x[i] >= 0) { cross++; }
          const hzOut = cross / ((hi - lo) / sr);
          let peak = 0; for (let i = lo; i < hi; i++) peak = Math.max(peak, Math.abs(x[i]));
          const blob = window.sincronoWav(o, sr);
          return { len: x.length / sr, hzOut, peak, ms, wav: blob.size, ch: o.length };
        }""", [f, hz])
        check("fator %.1f: duração %.2f s (esperado %.2f)" % (f, r["len"], 8 / f), abs(r["len"] - 8 / f) < 0.1, r)
        check("fator %.1f: tom preservado (%.1f Hz, esperado %d)" % (f, r["hzOut"], hz), abs(r["hzOut"] - hz) / hz < 0.03, r)
        check("fator %.1f: nível preservado (pico %.2f) e estéreo mantido, WAV %d bytes, %d ms" % (f, r["peak"], r["wav"], r["ms"]), 0.6 < r["peak"] < 1.1 and r["ch"] == 2 and r["wav"] == 44 + int(r["len"] * 22050) * 4, r)
    b.close()
print("\n%s" % ("TUDO OK" if not falhas else "FALHAS: %s" % falhas)); sys.exit(1 if falhas else 0)
