#!/usr/bin/env python3
"""Pauta oculta (MusicXML staff-lines 0 / print-object="no"): só cifras e letra.   python3 tools/teste_partitura_sem_pauta.py"""
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
    page = b.new_page(viewport={"width": 1000, "height": 420})
    erros = []
    page.on("pageerror", lambda e: erros.append(str(e)))
    page.set_content('<body style="margin:0"><div id="c" style="width:980px;height:400px;overflow:auto;position:relative"></div></body>')
    page.add_script_tag(path=str(WEB / "score.js"))
    page.add_script_tag(path=str(WEB / "score-view.js"))
    xml = (RAIZ / "docs/exemplos-partitura/cifra-e-letra.musicxml").read_text(encoding="utf-8")
    r = page.evaluate("""(x) => { const c = document.getElementById('c'); const s = SincronoScore.parseText(x);
      const v = new SincronoScore.ScoreView(c, { mode: 'pages', zoom: 1 }); v.setScore(s, 0); v.setCursor({ k: 1, q: 1 }, false);
      const svg = c.querySelector('svg'); const t = Array.from(svg.querySelectorAll('text')).map((e) => e.textContent);
      const ls = Array.from(svg.querySelectorAll('line')); const horiz = ls.filter((e) => e.getAttribute('y1') === e.getAttribute('y2')).length;
      const xs = Array.from(svg.querySelectorAll('text')).filter((e) => e.getAttribute('font-weight') === 'bold' && e.getAttribute('y') === svg.querySelector('text[font-weight=bold]').getAttribute('y')).map((e) => e.getBoundingClientRect());
      let overlap = 0; for (let i = 1; i < xs.length; i++) if (xs[i].left < xs[i - 1].right - 1) overlap++;
      const cur = v.pointAt(1, 1);
      return { noStaff: !!s.parts[0].noStaff, lines: horiz, bars: ls.length - horiz, paths: svg.querySelectorAll('path').length,
        ell: svg.querySelectorAll('ellipse').length, texts: t, overlap, curH: cur.bottom - cur.top }; }""", xml)
    check("a pauta é marcada como oculta", r["noStaff"], r)
    check("sem linhas de pauta, clave, notas nem pausas", r["lines"] == 0 and r["paths"] == 0 and r["ell"] <= 1, r)
    check("barras de compasso aparecem como traços verticais", r["bars"] >= 8, r)
    check("cifras e letra continuam desenhadas", "Eu" in r["texts"] and "quero" in r["texts"] and "Cm7" in r["texts"], r["texts"][:20])
    check("alteração não duplicada: A♭13(♯11)", "A♭13(♯11)" in r["texts"] and not any("(♯11)(" in t for t in r["texts"]), r["texts"])
    check("cifras da primeira linha não se sobrepõem", r["overlap"] == 0, r)
    check("cursor cobre cifra e letra sem passar do sistema", 40 < r["curH"] < 120, r)
    # compassos marcados com "|" e {tempo}/{compasso}: o tempo do arquivo tem de bater com o do metrônomo
    xml3 = (RAIZ / "docs/exemplos-partitura/cifra-e-letra-compassos.musicxml").read_text(encoding="utf-8")
    r3 = page.evaluate("""(x) => { const s = SincronoScore.parseText(x);
      const l = s.locate(5000);
      return { n: s.measures.length, lens: Array.from(new Set(s.measures.map((m) => m.lenQ))), bpm: s.initialBpm, total: Math.round(s.totalMs), k: l ? l.k : -1 }; }""", xml3)
    check("12 compassos de 2 tempos a 90 bpm", r3["n"] == 12 and r3["lens"] == [2] and r3["bpm"] == 90, r3)
    check("duração total = 12 x 2 x 60000/90 = 16000 ms", r3["total"] == 16000, r3)
    check("aos 5 s o cursor está no compasso 4 (índice 3)", r3["k"] == 3, r3)
    # partitura normal não muda
    xml2 = (RAIZ / "docs/exemplos-partitura/completa.musicxml").read_text(encoding="utf-8")
    r2 = page.evaluate("""(x) => { const c = document.getElementById('c'); const s = SincronoScore.parseText(x);
      const v = new SincronoScore.ScoreView(c, { mode: 'pages', zoom: 1 }); v.setScore(s, 0);
      return { noStaff: !!s.parts[0].noStaff, ell: c.querySelector('svg').querySelectorAll('ellipse').length }; }""", xml2)
    check("partitura normal continua com pauta e notas", (not r2["noStaff"]) and r2["ell"] > 10, r2)
    check("sem erros de JavaScript", not erros, erros)
    b.close()

print("\nTUDO CERTO" if not falhas else "\nFALHAS: %s" % falhas)
raise SystemExit(1 if falhas else 0)
