#!/usr/bin/env python3
"""Testa claves e recursos de orquestra (docs/exemplos-partitura/orquestra.musicxml) e o K284 se estiver em docs/exemplos-partitura/.
   python3 tools/gerar_exemplo_orquestra.py && python3 tools/teste_partitura_orquestra.py"""
import json, sys
from pathlib import Path
from playwright.sync_api import sync_playwright

RAIZ = Path(__file__).resolve().parent.parent
W = RAIZ / "app/src/main/assets/web"
xml = (RAIZ / "docs/exemplos-partitura/orquestra.musicxml").read_text(encoding="utf-8")
falhas = []


def check(nome, ok, extra=""):
    print(("  ok  " if ok else " FALHA"), nome, "" if ok else extra)
    if not ok:
        falhas.append(nome)


with sync_playwright() as p:
    b = p.chromium.launch()
    pg = b.new_page(viewport={"width": 900, "height": 500})
    erros = []
    pg.on("pageerror", lambda e: erros.append(str(e)))
    pg.set_content('<div id="c" style="width:900px;height:500px;overflow:auto"></div>')
    pg.add_script_tag(path=str(W / "score.js")); pg.add_script_tag(path=str(W / "score-view.js"))
    pg.evaluate("""(x) => { window.s = SincronoScore.parseText(x); window.v = new SincronoScore.ScoreView(document.getElementById('c'), { mode: 'pages', zoom: 1 }); v.setScore(s, 0); }""", xml)
    check("8 partes lidas", pg.evaluate("s.parts.length") == 8)

    # ---- armaduras por clave (grau diatônico oitava*7+passo): sol, fá, dó alto, dó tenor
    kp = lambda f, sign, line, oct_=0: pg.evaluate("([f,sg,ln,o]) => SincronoScore._test.keyPositions(f, {sign: sg, line: ln, oct: o})", [f, sign, line, oct_])
    check("sol: sustenidos F5 C5 G5", kp(3, "G", 2) == [38, 35, 39], kp(3, "G", 2))
    check("sol: bemóis B4 E5 A4", kp(-3, "G", 2) == [34, 37, 33], kp(-3, "G", 2))
    check("fá: sustenidos F3 C3 G3", kp(3, "F", 4) == [24, 21, 25], kp(3, "F", 4))
    check("fá: bemóis B2 E3 A2", kp(-3, "F", 4) == [20, 23, 19], kp(-3, "F", 4))
    check("dó alto: sustenidos F4 C4 G4", kp(3, "C", 3) == [31, 28, 32], kp(3, "C", 3))
    check("dó tenor: sustenidos F3 C4 G3", kp(3, "C", 4) == [24, 28, 25], kp(3, "C", 4))
    check("sol com 8vb: mesma posição na pauta (base uma oitava abaixo)", [d + 7 for d in kp(2, "G", 2, -1)] == kp(2, "G", 2))

    # ---- leitor
    r = pg.evaluate("""() => { const P = s.parts; const fg = P[3].measures[2];
      return { chg: fg.clefChanges.map(c => [c.q, c.clef.sign, c.clef.line]), startClef: fg.clefs[1].sign,
               harm: P[0].measures[1].harm.map(h => h.text), octs: P[0].measures[1].dirs.filter(d => d.kind === 'octave').length,
               harpDyn: P[6].measures[0].dirs.filter(d => d.kind === 'dyn').map(d => d.text + '@' + d.staff),
               lyr: P[5].measures[0].notes[0].lyrics.length, staves: [P[6].staves, P[7].staves],
               marks: P[0].measures[1].notes[0].marks.concat(P[0].measures[1].notes[2].marks, P[0].measures[1].notes[3].marks, P[0].measures[2].notes[2].marks, P[0].measures[2].notes[3].marks, P[2].measures[1].notes[3].marks, P[2].measures[2].notes[0].marks, P[6].measures[0].notes[0].marks),
               fer: P[0].measures[3].notes.some(n => n.fermata), grace: P[0].measures[3].notes.some(n => n.grace), head: P[4].measures[0].notes[0].head,
               perc: P[4].measures[0].clefs[1].sign, oct8: P[5].measures[0].clefs[1].oct, alto: P[2].measures[0].clefs[1].line }; }""")
    check("troca de clave no meio do compasso (fagote, tempo 2: dó tenor)", r["chg"] == [[2, "C", 4]] and r["startClef"] == "F", r)
    check("cifra lida (Gm7)", "Gm7" in r["harm"], r)
    check("8va lido", r["octs"] == 1, r)
    check("dinâmicas da harpa com a pauta de cada uma", r["harpDyn"] == ["p@1", "mf@2"], r)
    check("2 versos de letra", r["lyr"] == 2, r)
    check("harpa 2 pautas e órgão 3", r["staves"] == [2, 3], r)
    check("ornamentos/articulações/arcadas lidos", all(k in r["marks"] for k in ["trill-mark", "stac", "acc", "mordent", "turn", "upbow", "downbow", "arp"]), r["marks"])
    check("fermata e apojatura lidas", r["fer"] and r["grace"], r)
    check("clave de percussão, 8vb e dó alto lidas", r["perc"] == "percussion" and r["oct8"] == -1 and r["alto"] == 3, r)

    # ---- desenho
    def textos(i):
        pg.evaluate("(i) => v.setPart(i)", i)
        return pg.evaluate("() => [...document.querySelectorAll('#c svg text')].map(t => [t.textContent, parseFloat(t.getAttribute('y')), parseFloat(t.getAttribute('x'))])")

    t = textos(0)
    names = [x[0] for x in t]
    check("flauta: cifra Gm7, 8va, trinado (tr) desenhados", "Gm7" in names and "8va" in names and "tr" in names, names)
    gm, va, tr = [next(x for x in t if x[0] == k) for k in ("Gm7", "8va", "tr")]
    check("8va, cifra e trinado não ficam na mesma altura", len({round(gm[1]), round(va[1]), round(tr[1])}) == 3, (gm, va, tr))
    t = textos(6)
    pdy = next(x for x in t if x[0] == "p"); mfy = next(x for x in t if x[0] == "mf")
    check("harpa: dinâmica da pauta 2 fica abaixo da pauta 2 (bem abaixo da da pauta 1)", mfy[1] - pdy[1] > 60, (pdy, mfy))
    check("harpa: pedal Ped. desenhado", any(x[0] == "Ped." for x in t))
    t = textos(5)
    check("coro: dois versos em alturas diferentes", {x[0]: x[1] for x in t}.get("dia", 0) > {x[0]: x[1] for x in t}.get("ve", 99) + 8)
    check("coro 8vb: o '8' da clave é desenhado", any(x[0] == "8" for x in t))
    pg.evaluate("(i) => v.setPart(i)", 4)
    check("percussão: cabeças em x desenhadas", pg.evaluate("() => document.querySelectorAll('#c svg path[d*=\"L\"][stroke-width=\"1.8\"]').length") >= 16)
    pg.evaluate("(i) => v.setPart(i)", 3)
    check("fagote: clave pequena no meio do compasso (3 claves de dó/fá no desenho além das iniciais)", pg.evaluate("() => document.querySelectorAll('#c svg g[transform*=\"scale(0.72)\"]').length") == 1)
    pg.evaluate("(i) => v.setPart(i)", 7)
    check("órgão com 3 pautas desenha 15 linhas por sistema", pg.evaluate("() => document.querySelectorAll('#c svg g[stroke-width=\"1\"] line').length") % 15 == 0)

    # K284 (se existir): cifras em algarismos romanos, dinâmicas por pauta, sem erro
    k = RAIZ / "docs/exemplos-partitura/K284-2.xml"
    if k.exists():
        pg.evaluate("(x) => { window.s = SincronoScore.parseText(x); v.setScore(s, 0); }", k.read_text(encoding="utf-8"))
        names = pg.evaluate("() => [...document.querySelectorAll('#c svg text')].map(t => t.textContent)")
        check("K284: cifras de análise (V7, I) desenhadas e nada de 'N.C.'", "V7" in names and "N.C." not in names, names[:20])
        check("K284: ornamentos desenhados (trinado/mordente/grupeto)", pg.evaluate("() => document.querySelectorAll('#c svg path[d*=\"l2.3\"]').length") > 3 or "tr" in names)
    check("sem erros de JavaScript", not erros, erros)
    b.close()

print("\nFALHAS:", falhas) if falhas else print("\nTUDO CERTO")
sys.exit(1 if falhas else 0)
