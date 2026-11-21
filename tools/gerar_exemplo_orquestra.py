#!/usr/bin/env python3
"""Gera docs/exemplos-partitura/orquestra.musicxml: partitura de teste com claves e recursos de orquestra
(sol, 8vb, dó alto/tenor, fá, percussão, harpa 2 pautas, órgão 3 pautas, troca de clave no meio do compasso,
8va, pedal, hairpins, cifras, ornamentos, apojatura, fermata, arcadas, dois versos de letra).   python3 tools/gerar_exemplo_orquestra.py"""
from pathlib import Path
DIV = 2  # divisões por semínima


def note(step, octv, dur=2, typ="quarter", alter=None, extra="", staff=None, voice=1, lyric="", grace=False, head=None, chord=False, rest=False, tie=None, notations=""):
    s = "<note>"
    if grace: s += '<grace slash="yes"/>'
    if chord: s += "<chord/>"
    if rest: s += "<rest/>"
    else:
        s += "<pitch><step>%s</step>%s<octave>%d</octave></pitch>" % (step, ("<alter>%d</alter>" % alter) if alter else "", octv)
    if not grace: s += "<duration>%d</duration>" % dur
    s += "<voice>%d</voice><type>%s</type>" % (voice, typ)
    if head: s += "<notehead>%s</notehead>" % head
    if staff: s += "<staff>%d</staff>" % staff
    if lyric: s += lyric
    if notations: s += "<notations>%s</notations>" % notations
    return s + "</note>"


def attrs(clefs, fifths=-2, beats=4, staves=None, transpose=None, first=True):
    a = "<attributes>"
    if first: a += "<divisions>%d</divisions><key><fifths>%d</fifths></key><time><beats>%d</beats><beat-type>4</beat-type></time>" % (DIV, fifths, beats)
    if staves: a += "<staves>%d</staves>" % staves
    for n, (sign, line, octc) in clefs.items():
        a += '<clef number="%d"><sign>%s</sign>%s%s</clef>' % (n, sign, ("<line>%d</line>" % line) if line else "", ("<clef-octave-change>%d</clef-octave-change>" % octc) if octc else "")
    if transpose: a += "<transpose>%s</transpose>" % transpose
    return a + "</attributes>"


def dyn(d, staff=None, place="below"):
    return '<direction placement="%s"><direction-type><dynamics><%s/></dynamics></direction-type>%s</direction>' % (place, d, ("<staff>%d</staff>" % staff) if staff else "")


def wedge(t, staff=None):
    return '<direction placement="below"><direction-type><wedge type="%s"/></direction-type>%s</direction>' % (t, ("<staff>%d</staff>" % staff) if staff else "")


def harm(step, kind, text=None):
    return '<harmony><root><root-step>%s</root-step></root><kind%s>%s</kind></harmony>' % (step, (' text="%s"' % text) if text is not None else "", kind)


def measure(n, body):
    return '<measure number="%d">%s</measure>' % (n, body)


def lyr(num, text, syl="single"):
    return '<lyric number="%d"><syllabic>%s</syllabic><text>%s</text></lyric>' % (num, syl, text)


parts = []

# 1 Flauta: cifras, trinado, apojatura, 8va, hairpin, fermata
m = [measure(1, attrs({1: ("G", 2, 0)}) +
             '<direction placement="above"><direction-type><metronome><beat-unit>quarter</beat-unit><per-minute>96</per-minute></metronome></direction-type><sound tempo="96"/></direction>' +
             dyn("mf") + harm("B", "major") + note("D", 5) + note("F", 5) + wedge("crescendo") + note("B", 5, 2) + note("A", 5) + wedge("stop")),
     measure(2, harm("G", "minor-seventh") + '<direction placement="above"><direction-type><octave-shift type="down" size="8"/></direction-type></direction>' +
             note("G", 6, notations="<ornaments><trill-mark/></ornaments>") + note("A", 6) + note("B", 6, notations="<articulations><staccato/></articulations>") + note("C", 7, notations="<articulations><accent/></articulations>")),
     measure(3, note("D", 7) + note("C", 7) + '<direction><direction-type><octave-shift type="stop" size="8"/></direction-type></direction>' + note("B", 5, notations="<ornaments><mordent/></ornaments>") + note("A", 5, notations="<ornaments><turn/></ornaments>")),
     measure(4, note("E", 5, grace=True, typ="eighth") + note("F", 5, 8, "whole", notations="<fermata/>"))]
parts.append(("Flauta", "Fl.", m, 1))

# 2 Clarinete em Sib (transpositor)
m = [measure(1, attrs({1: ("G", 2, 0)}, fifths=0, transpose="<diatonic>-1</diatonic><chromatic>-2</chromatic>") + dyn("p") + "".join(note(s, 4) for s in "CDEF")),
     measure(2, "".join(note(s, 4) for s in "GABC".replace("C", "B")) ),
     measure(3, note("C", 5, 4, "half") + note("G", 4, 4, "half")), measure(4, note("C", 5, 8, "whole"))]
parts.append(("Clarinete em Si♭", "Cl.", m, 1))

# 3 Viola: clave de dó (alto)
m = [measure(1, attrs({1: ("C", 3, 0)}) + dyn("mp") + note("C", 4) + note("E", 4) + note("G", 4) + note("B", 4, alter=-1)),
     measure(2, note("A", 4) + note("F", 4) + note("D", 4) + note("C", 4, notations="<technical><up-bow/></technical>")),
     measure(3, note("G", 3, 4, "half", notations="<technical><down-bow/></technical>") + note("C", 4, 4, "half")), measure(4, note("C", 4, 8, "whole"))]
parts.append(("Viola", "Vla.", m, 1))

# 4 Fagote: fá, troca para tenor no meio do compasso 3 e volta ao fá
m = [measure(1, attrs({1: ("F", 4, 0)}) + dyn("mf") + note("B", 2, alter=-1) + note("C", 3) + note("D", 3) + note("E", 3, alter=-1)),
     measure(2, note("F", 3) + note("G", 3) + note("A", 3) + note("B", 3, alter=-1)),
     measure(3, note("C", 3, 4, "half") + '<attributes><clef><sign>C</sign><line>4</line></clef></attributes>' + note("C", 4) + note("D", 4)),
     measure(4, '<attributes><clef><sign>F</sign><line>4</line></clef></attributes>' + note("B", 2, 8, "whole", alter=-1))]
parts.append(("Fagote", "Fg.", m, 1))

# 5 Percussão: clave de percussão, cabeças x
m = [measure(1, attrs({1: ("percussion", 2, 0)}, fifths=0) + dyn("f") + "".join(note("A", 4, 1, "eighth", head="x") for _ in range(8))),
     measure(2, "".join(note("A", 4, 1, "eighth", head="x") for _ in range(8))),
     measure(3, note("C", 5, 2) + note("A", 4, 2, head="x") + note("C", 5, 2) + note("A", 4, 2, head="x")), measure(4, note("A", 4, 8, "whole", head="x"))]
parts.append(("Percussão", "Perc.", m, 1))

# 6 Coro de tenores: clave de sol 8vb, dois versos
m = [measure(1, attrs({1: ("G", 2, -1)}) + dyn("mf") + note("C", 3, lyric=lyr(1, "A", "begin") + lyr(2, "Ó", "begin")) + note("D", 3, lyric=lyr(1, "ve", "end") + lyr(2, "dia", "end")) + note("E", 3, alter=-1, lyric=lyr(1, "Ma") + lyr(2, "de")) + note("F", 3, lyric=lyr(1, "ri") + lyr(2, "luz"))),
     measure(2, note("G", 3, 4, "half", lyric=lyr(1, "a") + lyr(2, "e")) + note("F", 3, 4, "half", lyric=lyr(1, "ca") + lyr(2, "sol"))),
     measure(3, note("E", 3, 8, "whole", alter=-1, lyric=lyr(1, "ra") + lyr(2, "mar"))), measure(4, note("C", 3, 8, "whole", lyric=lyr(1, "ah") + lyr(2, "ah")))]
parts.append(("Tenores", "T.", m, 1))

# 7 Harpa: 2 pautas, pedal, dinâmica por pauta, arpejo
m = [measure(1, attrs({1: ("G", 2, 0), 2: ("F", 4, 0)}, staves=2) + dyn("p", 1) +
             '<direction placement="below"><direction-type><pedal type="start"/></direction-type><staff>2</staff></direction>' +
             note("D", 5, notations="<arpeggiate/>") + note("F", 5, chord=True) + note("B", 5, 6, "half", alter=-1) + "<backup><duration>8</duration></backup>" + dyn("mf", 2) +
             note("B", 2, 4, "half", alter=-1, staff=2) + note("F", 3, 4, "half", staff=2)),
     measure(2, note("D", 5, 8, "whole") + "<backup><duration>8</duration></backup>" + '<direction placement="below"><direction-type><pedal type="stop"/></direction-type><staff>2</staff></direction>' + note("G", 2, 8, "whole", staff=2)),
     measure(3, note("C", 5, 8, "whole") + "<backup><duration>8</duration></backup>" + note("C", 3, 8, "whole", staff=2)),
     measure(4, note("D", 5, 8, "whole") + "<backup><duration>8</duration></backup>" + note("B", 2, 8, "whole", alter=-1, staff=2))]
parts.append(("Harpa", "Hp.", m, 2))

# 8 Órgão: 3 pautas
m = [measure(1, attrs({1: ("G", 2, 0), 2: ("G", 2, 0), 3: ("F", 4, 0)}, staves=3) + note("D", 5, 8, "whole", staff=1) + "<backup><duration>8</duration></backup>" + note("B", 4, 8, "whole", alter=-1, staff=2) + "<backup><duration>8</duration></backup>" + note("B", 2, 8, "whole", alter=-1, staff=3))]
for k in range(3): m.append(measure(k + 2, note("C", 5, 8, "whole", staff=1) + "<backup><duration>8</duration></backup>" + note("A", 4, 8, "whole", staff=2) + "<backup><duration>8</duration></backup>" + note("F", 2, 8, "whole", staff=3)))
parts.append(("Órgão", "Org.", m, 3))

x = ['<?xml version="1.0" encoding="UTF-8"?><score-partwise version="3.1"><work><work-title>Exemplo de orquestra</work-title></work><part-list>']
for i, (nm, ab, _, _) in enumerate(parts, 1):
    x.append('<score-part id="P%d"><part-name>%s</part-name><part-abbreviation>%s</part-abbreviation></score-part>' % (i, nm, ab))
x.append("</part-list>")
for i, (_, _, ms, _) in enumerate(parts, 1):
    x.append('<part id="P%d">%s</part>' % (i, "".join(ms)))
x.append("</score-partwise>")
out = Path(__file__).resolve().parent.parent / "docs/exemplos-partitura/orquestra.musicxml"
out.write_text("".join(x), encoding="utf-8")
print("gerado", out, len(parts), "partes")
