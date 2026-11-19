#!/usr/bin/env python3
"""Gera docs/exemplos-partitura/completa.musicxml: soprano, violoncelo e piano (2 pautas).
Tem repetição com casas 1/2, tercina, mudança de andamento, dinâmica e letra.  python3 tools/gerar_exemplos_partitura.py"""
from pathlib import Path
from xml.sax.saxutils import escape

DIV = 12  # divisões por semínima
TYPES = {"whole": 48, "half": 24, "quarter": 12, "eighth": 6, "16th": 3}


def note(p, typ, *, dots=0, beam=None, tie=None, slur=None, tuplet=None, tm=False, lyric=None, chord=False, staff=None, voice=1, stem=None, acc=None):
    d = TYPES[typ]
    if dots:
        d = d * 3 // 2
    if tm:
        d = d * 2 // 3
    x = "<note>"
    if chord:
        x += "<chord/>"
    if p == "R":
        x += "<rest/>"
    else:
        st, alt, octv = p[0], 0, int(p[-1])
        if "#" in p: alt = 1
        if "b" in p[1:-1]: alt = -1
        x += "<pitch><step>%s</step>%s<octave>%d</octave></pitch>" % (st, "<alter>%d</alter>" % alt if alt else "", octv)
    x += "<duration>%d</duration>" % d
    if tie:
        for t in tie: x += '<tie type="%s"/>' % t
    x += "<voice>%d</voice><type>%s</type>" % (voice, typ) + "<dot/>" * dots
    if acc: x += "<accidental>%s</accidental>" % acc
    if tm: x += "<time-modification><actual-notes>3</actual-notes><normal-notes>2</normal-notes></time-modification>"
    if stem: x += "<stem>%s</stem>" % stem
    if staff: x += "<staff>%d</staff>" % staff
    if beam: x += '<beam number="1">%s</beam>' % beam
    nots = ""
    if tie:
        for t in tie: nots += '<tied type="%s"/>' % t
    if slur: nots += '<slur type="%s" number="1"/>' % slur
    if tuplet: nots += '<tuplet type="%s"/>' % tuplet
    if nots: x += "<notations>%s</notations>" % nots
    if lyric: x += "<lyric number=\"1\"><syllabic>single</syllabic><text>%s</text></lyric>" % escape(lyric)
    return x + "</note>"


def measure(n, body, *, attrs="", left="", right="", dirs=""):
    return '<measure number="%d">%s%s%s%s%s</measure>' % (n, attrs, left, dirs, body, right)


def attributes(clef=None, key=1, time=True, staves=None, first=False):
    a = "<attributes>"
    if first: a += "<divisions>%d</divisions>" % DIV
    a += "<key><fifths>%d</fifths></key>" % key
    if time: a += "<time><beats>4</beats><beat-type>4</beat-type></time>"
    if staves: a += "<staves>%d</staves>" % staves
    for c in clef: a += c
    return a + "</attributes>"


G = '<clef><sign>G</sign><line>2</line></clef>'
F = '<clef><sign>F</sign><line>4</line></clef>'
G1 = '<clef number="1"><sign>G</sign><line>2</line></clef>'
F2 = '<clef number="2"><sign>F</sign><line>4</line></clef>'
TEMPO = lambda bpm, text="": ('<direction placement="above"><direction-type>%s<metronome><beat-unit>quarter</beat-unit><per-minute>%d</per-minute></metronome></direction-type><sound tempo="%d"/></direction>'
                               % ('<words>%s</words>' % text if text else '', bpm, bpm))
DYN = lambda d: '<direction placement="below"><direction-type><dynamics><%s/></dynamics></direction-type></direction>' % d
FWD = '<barline location="left"><bar-style>heavy-light</bar-style><repeat direction="forward"/></barline>'
END1_L = '<barline location="left"><ending number="1" type="start">1.</ending></barline>'
END1_R = '<barline location="right"><bar-style>light-heavy</bar-style><ending number="1" type="stop"/><repeat direction="backward"/></barline>'
END2_L = '<barline location="left"><ending number="2" type="start">2.</ending></barline>'
END2_R = '<barline location="right"><ending number="2" type="discontinue"/></barline>'
FINAL = '<barline location="right"><bar-style>light-heavy</bar-style></barline>'


def q(p, **k): return note(p, "quarter", **k)


def soprano():
    m = []
    m.append(measure(1, q("G4", lyric="La") + q("A4", lyric="la") + q("B4", lyric="la") + q("C5", lyric="la"),
                     attrs=attributes([G], first=True), left=FWD, dirs=TEMPO(100, "Andante") + DYN("mf")))
    m.append(measure(2, note("D5", "eighth", beam="begin") + note("C5", "eighth", beam="end") + q("B4") + note("A4", "half")))
    m.append(measure(3, q("G4") + q("A4") + note("B4", "half"), left=END1_L, right=END1_R))
    m.append(measure(4, q("G4") + q("F#4", acc="sharp") + note("G4", "half"), left=END2_L, right=END2_R))
    m.append(measure(5, note("C5", "eighth", beam="begin", tuplet="start", tm=True) + note("D5", "eighth", beam="continue", tm=True)
                     + note("E5", "eighth", beam="end", tuplet="stop", tm=True) + q("D5") + note("C5", "half")))
    m.append(measure(6, note("B4", "eighth", beam="begin", dots=0) + note("C5", "eighth", beam="end") + note("D5", "quarter", dots=1) + note("E5", "eighth") + q("D5")))
    m.append(measure(7, q("C5", tie=["start"]) + q("C5", tie=["stop"]) + q("B4") + q("A4"), dirs=TEMPO(80, "rit.")))
    m.append(measure(8, note("G4", "whole"), right=FINAL))
    return m


def cello():
    m = []
    m.append(measure(1, note("G2", "half") + note("D3", "half"), attrs=attributes([F], first=True), left=FWD, dirs=TEMPO(100, "Andante") + DYN("mp")))
    m.append(measure(2, note("G2", "half") + note("D3", "half")))
    m.append(measure(3, note("G2", "half") + note("B2", "half"), left=END1_L, right=END1_R))
    m.append(measure(4, note("G2", "whole"), left=END2_L, right=END2_R))
    m.append(measure(5, note("C3", "half") + note("D3", "half")))
    m.append(measure(6, note("G2", "half") + note("D3", "half")))
    m.append(measure(7, note("C3", "half") + note("D3", "half"), dirs=TEMPO(80, "rit.")))
    m.append(measure(8, note("G2", "whole"), right=FINAL))
    return m


def piano():
    def bar(top, bottom, **k):
        # pauta 1 (voz 1), backup, pauta 2 (voz 2)
        return measure(k.pop("n"), top + "<backup><duration>48</duration></backup>" + bottom, **k)

    def s1(p, t, **k): return note(p, t, staff=1, **k)

    def s2(p, t, **k): return note(p, t, staff=2, voice=2, **k)
    m = []
    m.append(bar(s1("D5", "half") + s1("B4", "half") + "", s2("G3", "whole"), n=1, attrs=attributes([G1, F2], staves=2, first=True), left=FWD, dirs=TEMPO(100, "Andante") + DYN("p")))
    m.append(bar(s1("C5", "half") + s1("A4", "half"), s2("D3", "whole"), n=2))
    m.append(bar(s1("B4", "half") + s1("D5", "half"), s2("G3", "whole"), n=3, left=END1_L, right=END1_R))
    m.append(bar(s1("B4", "whole"), s2("G3", "whole"), n=4, left=END2_L, right=END2_R))
    m.append(bar(s1("E5", "half") + s1("D5", "half"), s2("C3", "whole"), n=5))
    m.append(bar(s1("D5", "half") + s1("C5", "half"), s2("G3", "whole"), n=6))
    m.append(bar(s1("C5", "half") + s1("A4", "half"), s2("D3", "whole"), n=7, dirs=TEMPO(80, "rit.")))
    m.append(bar(s1("B4", "whole"), s2("G2", "whole"), n=8, right=FINAL))
    return m


def build():
    parts = [("P1", "Soprano", "S.", soprano()), ("P2", "Violoncelo", "Vc.", cello()), ("P3", "Piano", "Pno.", piano())]
    x = '<?xml version="1.0" encoding="UTF-8"?>\n<score-partwise version="3.1"><work><work-title>Exemplo Sincrono</work-title></work><part-list>'
    for pid, name, ab, _ in parts:
        x += '<score-part id="%s"><part-name>%s</part-name><part-abbreviation>%s</part-abbreviation></score-part>' % (pid, name, ab)
    x += "</part-list>"
    for pid, _, _, ms in parts:
        x += '<part id="%s">%s</part>' % (pid, "".join(ms))
    return x + "</score-partwise>\n"


if __name__ == "__main__":
    out = Path(__file__).resolve().parent.parent / "docs/exemplos-partitura"
    out.mkdir(parents=True, exist_ok=True)
    (out / "completa.musicxml").write_text(build(), encoding="utf-8")
    print("gerado", out / "completa.musicxml")
