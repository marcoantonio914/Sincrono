#!/usr/bin/env python3
"""Tira capturas da partitura de exemplo para olhar à mão.  python3 tools/captura_partitura.py SAIDA_DIR [largura]"""
import sys
from pathlib import Path
from playwright.sync_api import sync_playwright
R = Path(__file__).resolve().parent.parent
out = Path(sys.argv[1]); out.mkdir(parents=True, exist_ok=True)
W = int(sys.argv[2]) if len(sys.argv) > 2 else 500
xml = (R / "docs/exemplos-partitura/completa.musicxml").read_text(encoding="utf-8")
with sync_playwright() as p:
    b = p.chromium.launch(); pg = b.new_page(viewport={"width": W, "height": 520}, device_scale_factor=2)
    errs = []; pg.on("pageerror", lambda e: errs.append(str(e))); pg.on("console", lambda m: errs.append(m.text) if m.type == "error" else None)
    pg.set_content('<body style="margin:0;background:#fff;color:#111"><div id="c" style="width:%dpx;height:500px;overflow:auto;position:relative"></div></body>' % (W - 10))
    pg.add_script_tag(path=str(R / "app/src/main/assets/web/score.js")); pg.add_script_tag(path=str(R / "app/src/main/assets/web/score-view.js"))
    for idx, nome in enumerate(["soprano", "violoncelo", "piano"]):
        for mode in ["pages", "continuous"]:
            pg.evaluate("""([x, i, mode]) => { const c = document.getElementById('c'); const s = SincronoScore.parseText(x);
              window.v = new SincronoScore.ScoreView(c, { mode, zoom: 1 }); v.setScore(s, i); v.setCursor({ k: 5, q: 1.5 }, false); }""", [xml, idx, mode])
            pg.screenshot(path=str(out / ("%s-%s.png" % (nome, mode))))
    print("erros:", errs)
    b.close()
