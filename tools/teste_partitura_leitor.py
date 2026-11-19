#!/usr/bin/env python3
"""Testa o leitor score.js (ordem de execução, andamento, partes) num Chromium de verdade.  python3 tools/teste_partitura_leitor.py"""
import sys
from pathlib import Path
from playwright.sync_api import sync_playwright

RAIZ = Path(__file__).resolve().parent.parent
falhas = []


def check(nome, ok, extra=""):
    print(("  ok  " if ok else " FALHA"), nome, "" if ok else extra)
    if not ok:
        falhas.append(nome)


with sync_playwright() as p:
    b = p.chromium.launch()
    page = b.new_page()
    erros = []
    page.on("pageerror", lambda e: erros.append(str(e)))
    page.set_content("<body></body>")
    page.add_script_tag(path=str(RAIZ / "app/src/main/assets/web/score.js"))
    xml = (RAIZ / "docs/exemplos-partitura/completa.musicxml").read_text(encoding="utf-8")
    r = page.evaluate("""(x) => { const s = SincronoScore.parseText(x);
      return { parts: s.parts.map(p => [p.name, p.staves]), n: s.measures.length, order: s.order.map(o => o.m + 1),
               total: s.totalMs, bpm: s.initialBpm, l0: s.locate(0), l1: s.locate(2400), tempoAt7: s.order.find(o => o.m === 6).segs[0].bpm,
               ms2: s.order[1].ms0, lenQ: s.measures.map(m => m.lenQ) }; }""", xml)
    check("3 partes: Soprano, Violoncelo, Piano (2 pautas)", r["parts"] == [["Soprano", 1], ["Violoncelo", 1], ["Piano", 2]], r["parts"])
    check("8 compassos", r["n"] == 8, r["n"])
    check("ordem de execução com repetição e casas", r["order"] == [1, 2, 3, 1, 2, 4, 5, 6, 7, 8], r["order"])
    check("andamento inicial 100", r["bpm"] == 100, r["bpm"])
    check("compasso 2 começa em 2400 ms", abs(r["ms2"] - 2400) < 1, r["ms2"])
    check("andamento 80 no compasso 7", r["tempoAt7"] == 80, r["tempoAt7"])
    # 9 compassos a 100 (2400 ms cada) + compassos 7 e 8 a 80 (3000 ms cada)... conferir: 8 compassos a 100 = 19200 ; 2 a 80
    esperado = 8 * 2400 + 2 * 3000
    check("duração total considera repetição e rit.", abs(r["total"] - esperado) < 1, (r["total"], esperado))
    check("locate(0) = compasso 1", r["l0"]["k"] == 0 and r["l0"]["q"] == 0, r["l0"])
    check("locate(2400) = ordem 1 (compasso 2)", r["l1"]["k"] == 1 and r["l1"]["m"] == 1, r["l1"])
    check("sem erros de JavaScript", not erros, erros)
    b.close()
print("\n" + ("TUDO CERTO" if not falhas else "%d FALHA(S): %s" % (len(falhas), falhas)))
sys.exit(1 if falhas else 0)
