#!/usr/bin/env python3
"""Testa o desenho (score-view.js) e o cursor num Chromium de verdade.   python3 tools/teste_partitura_desenho.py"""
import sys
from pathlib import Path
from playwright.sync_api import sync_playwright

RAIZ = Path(__file__).resolve().parent.parent
WEB = RAIZ / "app/src/main/assets/web"
falhas = []


def check(nome, ok, extra=""):
    print(("  ok  " if ok else " FALHA"), nome, "" if ok else extra)
    if not ok:
        falhas.append(nome)


with sync_playwright() as p:
    b = p.chromium.launch()
    page = b.new_page(viewport={"width": 500, "height": 360})
    erros = []
    page.on("pageerror", lambda e: erros.append(str(e)))
    page.set_content('<body style="margin:0"><div id="c" style="width:480px;height:340px;overflow:auto;position:relative"></div></body>')
    page.add_script_tag(path=str(WEB / "score.js"))
    page.add_script_tag(path=str(WEB / "score-view.js"))
    xml = (RAIZ / "docs/exemplos-partitura/completa.musicxml").read_text(encoding="utf-8")
    ev = page.evaluate
    for i, nome in enumerate(["Soprano", "Violoncelo", "Piano"]):
        r = ev("""([x, i]) => { const c = document.getElementById('c'); const s = SincronoScore.parseText(x); const out = {};
          const part = s.parts[i];
          const nNotes = part.measures.reduce((a, m) => a + m.notes.filter(n => !n.rest && !n.grace).length, 0);
          for (const mode of ['continuous', 'pages']) {
            const v = new SincronoScore.ScoreView(c, { mode, zoom: 1 }); v.setScore(s, i);
            const svg = c.querySelector('svg'); const xs = [];
            for (let k = 0; k < s.measures.length; k++) xs.push(v.pointAt(k, 0).x);
            out[mode] = { systems: v.systems.length, ell: svg.querySelectorAll('ellipse').length, mono: xs.every((x, j) => j === 0 || x > xs[j - 1]),
              sysOf: s.measures.map((m, k) => v.geo[k].sys), nNotes, w: parseFloat(svg.getAttribute('width')) };
          } return out; }""", [xml, i])
        c, pg = r["continuous"], r["pages"]
        check(f"{nome}: contínuo = 1 linha, x crescente", c["systems"] == 1 and c["mono"], c)
        check(f"{nome}: páginas quebram em várias linhas (largura 480)", pg["systems"] >= 2 and pg["sysOf"] == sorted(pg["sysOf"]), pg)
        check(f"{nome}: uma cabeça para cada nota", c["ell"] >= c["nNotes"], c)
    r = ev("""([x]) => { const c = document.getElementById('c'); const s = SincronoScore.parseText(x);
      const v = new SincronoScore.ScoreView(c, { mode: 'continuous', zoom: 2 }); v.setScore(s, 0);
      const read = () => ({ x: parseFloat(v.curLine.getAttribute('x1')), shown: v.curG.style.display !== 'none', hlw: parseFloat(v.curHl.getAttribute('width')) });
      v.setCursor(null, false); const hidden = v.curG.style.display === 'none';
      v.setCursor({ k: 0, q: 0 }, true); const a = read(); const sl0 = c.scrollLeft;
      v.setCursor({ k: 0, q: 2 }, true); const b2 = read();
      v.setCursor({ k: 9, q: 0 }, true); const c3 = read(); const sl1 = c.scrollLeft;
      v.setCursor({ k: 3, q: 0 }, false); const rep = read();     // 4ª execução = compasso 1 de novo
      v.pausedUntil = Date.now() + 5000; v.setCursor({ k: 0, q: 0 }, true); const sl2 = c.scrollLeft;
      v.setMode('pages'); v.setCursor({ k: 7, q: 0 }, false); const pgs = read();
      return { hidden, a, b2, c3, sl0, sl1, sl2, rep, pgs }; }""", [xml])
    check("cursor some com null", r["hidden"])
    check("cursor anda para a direita dentro do compasso", r["b2"]["x"] > r["a"]["x"] + 5, r)
    check("cursor no fim está mais à direita", r["c3"]["x"] > r["b2"]["x"], r)
    check("repetição: execução 4 volta ao compasso 1", abs(r["rep"]["x"] - r["a"]["x"]) < 1, r)
    check("destaque do compasso tem largura", r["a"]["hlw"] > 20, r)
    check("rolagem acompanha o cursor", r["sl1"] > r["sl0"] + 10, r)
    check("rolagem pausa quando o usuário mexe", r["sl2"] == r["sl1"], r)
    check("cursor aparece depois de trocar para páginas", r["pgs"]["shown"], r)
    txt = ev("() => [...document.querySelectorAll('svg text')].map(t => t.textContent).join('|')")
    check("texto do andamento e dinâmica no SVG", "= 100" in txt and "mf" in txt and "Andante" in txt, txt[:200])
    check("número da tercina (3) desenhado", "3" in txt.split("|"), txt[:300])
    check("sem erros de JavaScript", not erros, erros)
    b.close()
print("\n" + ("TUDO CERTO" if not falhas else "%d FALHA(S): %s" % (len(falhas), falhas)))
sys.exit(1 if falhas else 0)
