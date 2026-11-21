// Leitor de partituras MusicXML (.musicxml / .xml / .mxl) do Sincrono.
// Sem bibliotecas externas: roda no navegador e na WebView do Android, sem internet.
//
// SincronoScore.load(ArrayBuffer) -> Promise<Score>
//   score.title, score.parts[i] = { id, name, abbr, staves, measures: [...] }
//   score.measures[m]  = estrutura comum (compasso, tamanho em semínimas, repetições)
//   score.order[k]     = ordem REAL de execução (com repetições, casas 1/2, da capo, dal segno, coda, fine)
//   score.locate(ms)   = em que compasso/tempo a música está ms depois do início
(function (root) {
  'use strict';

  // ---------------------------------------------------------------- arquivo .mxl (zip)
  function u16(d, o) { return d[o] | (d[o + 1] << 8); }
  function u32(d, o) { return (d[o] | (d[o + 1] << 8) | (d[o + 2] << 16) | (d[o + 3] << 24)) >>> 0; }

  async function inflateRaw(bytes) {
    if (typeof DecompressionStream === 'undefined') throw new Error('Este navegador não abre arquivos .mxl. Exporte como .musicxml.');
    const ds = new DecompressionStream('deflate-raw');
    const w = ds.writable.getWriter();
    w.write(bytes); w.close();
    return new Uint8Array(await new Response(ds.readable).arrayBuffer());
  }

  async function unzip(buf) {
    const d = new Uint8Array(buf);
    let eocd = -1;
    for (let i = d.length - 22; i >= Math.max(0, d.length - 70000); i--) {
      if (u32(d, i) === 0x06054b50) { eocd = i; break; }
    }
    if (eocd < 0) throw new Error('Arquivo .mxl inválido.');
    const count = u16(d, eocd + 10);
    let p = u32(d, eocd + 16);
    const files = {};
    const dec = new TextDecoder('utf-8');
    for (let n = 0; n < count; n++) {
      if (u32(d, p) !== 0x02014b50) break;
      const method = u16(d, p + 10), csize = u32(d, p + 20);
      const nlen = u16(d, p + 28), xlen = u16(d, p + 30), clen = u16(d, p + 32), lho = u32(d, p + 42);
      const name = dec.decode(d.subarray(p + 46, p + 46 + nlen));
      const dataStart = lho + 30 + u16(d, lho + 26) + u16(d, lho + 28);
      const raw = d.subarray(dataStart, dataStart + csize);
      files[name] = { method, raw };
      p += 46 + nlen + xlen + clen;
    }
    async function read(name) {
      const f = files[name];
      if (!f) return null;
      const bytes = f.method === 0 ? f.raw : await inflateRaw(f.raw);
      return dec.decode(bytes);
    }
    return { names: Object.keys(files), read };
  }

  async function xmlTextOf(buf) {
    const d = new Uint8Array(buf);
    if (d.length > 3 && d[0] === 0x50 && d[1] === 0x4b) { // "PK": .mxl
      const z = await unzip(buf);
      let root = null;
      const cont = await z.read('META-INF/container.xml');
      if (cont) {
        const m = /full-path="([^"]+)"/.exec(cont);
        if (m) root = m[1];
      }
      if (!root) root = z.names.find((n) => /\.(xml|musicxml)$/i.test(n) && !/^META-INF\//i.test(n));
      if (!root) throw new Error('Não achei a partitura dentro do .mxl.');
      return z.read(root);
    }
    let text = new TextDecoder('utf-8').decode(d);
    if (text.charCodeAt(0) === 0xfeff) text = text.slice(1);
    return text;
  }

  // ---------------------------------------------------------------- XML
  function kids(el, tag) { const r = []; for (const c of el.children) if (c.tagName === tag) r.push(c); return r; }
  function kid(el, tag) { if (!el) return null; for (const c of el.children) if (c.tagName === tag) return c; return null; }
  function text(el, tag, dflt) { const c = tag ? kid(el, tag) : el; return c ? c.textContent.trim() : dflt; }
  function num(el, tag, dflt) { const t = text(el, tag); const v = t === undefined ? NaN : parseFloat(t); return Number.isFinite(v) ? v : dflt; }
  const STEP = { C: 0, D: 1, E: 2, F: 3, G: 4, A: 5, B: 6 };
  const UNIT_Q = { whole: 4, half: 2, quarter: 1, eighth: 0.5, '16th': 0.25, '32nd': 0.125, '64th': 0.0625, breve: 8 };

  function parseDirection(dir, pos, div, m) {
    const place = dir.getAttribute('placement') || '';
    const dstaff = parseInt(text(dir, 'staff', '1'), 10) || 1;
    const before = m.dirs.length;
    const off = num(dir, 'offset', 0) / div;
    const q = Math.max(0, pos / div + off);
    for (const dt of kids(dir, 'direction-type')) {
      for (const c of dt.children) {
        if (c.tagName === 'words') {
          const t = c.textContent.trim();
          if (t) m.dirs.push({ q, kind: 'words', text: t, place, italic: /italic/.test(c.getAttribute('font-style') || '') });
        } else if (c.tagName === 'rehearsal') {
          m.dirs.push({ q, kind: 'rehearsal', text: c.textContent.trim(), place: 'above' });
        } else if (c.tagName === 'dynamics') {
          const d = c.children[0];
          if (d) m.dirs.push({ q, kind: 'dyn', text: d.tagName === 'other-dynamics' ? d.textContent.trim() : d.tagName, place: place || 'below' });
        } else if (c.tagName === 'metronome') {
          const unit = text(c, 'beat-unit', 'quarter');
          const dots = kids(c, 'beat-unit-dot').length;
          const pm = num(c, 'per-minute', NaN);
          if (Number.isFinite(pm)) {
            m.dirs.push({ q, kind: 'metro', unit, dots, perMinute: pm, place: 'above' });
            m.metro.push({ q, bpm: pm * (UNIT_Q[unit] || 1) * (dots ? 1.5 : 1) });
          }
        } else if (c.tagName === 'wedge') {
          m.dirs.push({ q, kind: 'wedge', type: c.getAttribute('type'), place: place || 'below' });
        } else if (c.tagName === 'octave-shift') {
          const sz = parseInt(c.getAttribute('size') || '8', 10) || 8;
          m.dirs.push({ q, kind: 'octave', type: c.getAttribute('type'), size: sz, place: 'above' });
        } else if (c.tagName === 'pedal') {
          m.dirs.push({ q, kind: 'pedal', type: c.getAttribute('type'), place: 'below' });
        } else if (c.tagName === 'dashes') {
          m.dirs.push({ q, kind: 'dashes', type: c.getAttribute('type'), place: place || 'above' });
        } else if (c.tagName === 'segno') {
          m.dirs.push({ q, kind: 'segno', place: 'above' });
        } else if (c.tagName === 'coda') {
          m.dirs.push({ q, kind: 'coda', place: 'above' });
        }
      }
    }
    for (let i = before; i < m.dirs.length; i++) m.dirs[i].staff = dstaff;
    const snd = kid(dir, 'sound');
    if (snd) parseSound(snd, q, m);
  }

  function parseSound(snd, q, m) {
    const t = parseFloat(snd.getAttribute('tempo'));
    if (Number.isFinite(t) && t > 0) m.tempos.push({ q, bpm: t });
    for (const k of ['dacapo', 'dalsegno', 'tocoda', 'fine', 'segno', 'coda']) {
      const v = snd.getAttribute(k);
      if (v) m.sound[k] = v;
    }
  }

  const KIND = {
    major: '', minor: 'm', augmented: 'aug', diminished: 'dim', dominant: '7', 'major-seventh': 'maj7', 'minor-seventh': 'm7',
    'diminished-seventh': 'dim7', 'augmented-seventh': 'aug7', 'half-diminished': 'm7\u266d5', 'major-minor': 'm(maj7)',
    'major-sixth': '6', 'minor-sixth': 'm6', 'dominant-ninth': '9', 'major-ninth': 'maj9', 'minor-ninth': 'm9',
    'dominant-11th': '11', 'dominant-13th': '13', 'suspended-second': 'sus2', 'suspended-fourth': 'sus4', power: '5', 'dominant-seventh': '7',
  };
  const ALT = { '-2': '\u266d\u266d', '-1': '\u266d', '0': '', '1': '\u266f', '2': '\u266f\u266f' };
  function harmonyText(h) {
    const k = kid(h, 'kind');
    const kindTxt = k ? k.getAttribute('text') : null;
    const kindName = k ? k.textContent.trim() : '';
    const rt = kid(h, 'root');
    // MuseScore guarda análise em algarismos romanos (I, V7...) como kind="none" + text="V7" e raiz oculta (text="")
    let rootTxt = '';
    if (rt) {
      const rs = kid(rt, 'root-step');
      const ra = rs ? rs.getAttribute('text') : null;
      rootTxt = ra !== null ? ra : (text(rt, 'root-step', '') + (ALT[String(num(rt, 'root-alter', 0))] || ''));
    }
    if (kindName === 'none') return kindTxt ? kindTxt : (rootTxt ? '' : 'N.C.');
    let suf = kindTxt !== null ? kindTxt : (KIND[kindName] !== undefined ? KIND[kindName] : kindName);
    let out = rootTxt + suf;
    for (const d of kids(h, 'degree')) {
      const v = text(d, 'degree-value', ''), al = ALT[String(num(d, 'degree-alter', 0))] || '', ty = text(d, 'degree-type', 'add');
      out += (ty === 'subtract' ? '(no ' : '(') + al + v + ')';
    }
    const b = kid(h, 'bass');
    if (b) out += '/' + text(b, 'bass-step', '') + (ALT[String(num(b, 'bass-alter', 0))] || '');
    return out;
  }

  function parsePart(partEl, info) {
    const part = { id: info.id, name: info.name, abbr: info.abbr, staves: 1, measures: [] };
    let div = 1;
    let clefs = {};
    let key = 0, time = { b: 4, t: 4 };
    for (const me of kids(partEl, 'measure')) {
      const m = {
        number: me.getAttribute('number') || String(part.measures.length + 1),
        implicit: me.getAttribute('implicit') === 'yes',
        notes: [], dirs: [], tempos: [], metro: [], sound: {},
        repeatStart: false, repeatEnd: false, repeatTimes: 2, ending: null,
        endingStop: null, bar: { left: null, right: null },
        newClefs: null, newKey: null, newTime: null,
        clefs: null, key: 0, time: null, // estado vigente no começo do compasso
        maxEnd: 0,
      };
      m.clefs = clefs; m.key = key; m.time = time;      // estado vigente no começo do compasso
      m.clefChanges = []; m.harm = [];
      let pos = 0, lastStart = 0, lastDur = 0;
      for (const el of me.children) {
        const tag = el.tagName;
        if (tag === 'attributes') {
          const dv = num(el, 'divisions', NaN); if (Number.isFinite(dv) && dv > 0) div = dv;
          const st = num(el, 'staves', NaN); if (Number.isFinite(st)) part.staves = Math.max(part.staves, st);
          const cl = kids(el, 'clef');
          const atStart = pos === 0;
          if (cl.length) {
            const nc = Object.assign({}, clefs);
            const made = {};
            for (const c of cl) {
              const stN = parseInt(c.getAttribute('number') || '1', 10);
              const def = { sign: text(c, 'sign', 'G'), line: num(c, 'line', NaN), oct: num(c, 'clef-octave-change', 0) };
              nc[stN] = def; made[stN] = def;
            }
            clefs = nc;
            if (atStart) { m.newClefs = nc; m.clefs = nc; }
            else for (const k of Object.keys(made)) m.clefChanges.push({ q: pos / div, staff: parseInt(k, 10), clef: made[k] });
          }
          const kf = kid(el, 'key');
          if (kf) { key = num(kf, 'fifths', 0); if (atStart) { m.newKey = key; m.key = key; } }
          const ti = kid(el, 'time');
          if (ti) {
            const sym = ti.getAttribute('symbol');
            time = { b: num(ti, 'beats', 4), t: num(ti, 'beat-type', 4), symbol: sym || '' };
            if (atStart) { m.newTime = time; m.time = time; }
          }
        } else if (tag === 'note') {
          const grace = !!kid(el, 'grace');
          const chord = !!kid(el, 'chord');
          const rest = kid(el, 'rest');
          const durDiv = grace ? 0 : num(el, 'duration', 0);
          const start = chord ? lastStart : pos;
          const n = {
            start: start / div, dur: durDiv / div, voice: text(el, 'voice', '1'), staff: parseInt(text(el, 'staff', '1'), 10) || 1,
            rest: !!rest, measureRest: rest ? rest.getAttribute('measure') === 'yes' : false, chord, grace,
            type: text(el, 'type', ''), dots: kids(el, 'dot').length,
            stem: text(el, 'stem', ''), accidental: text(el, 'accidental', ''), beams: {}, tie: [], slur: [], tuplet: null, tupletMark: null,
            staccato: false, fermata: false, lyric: '', lyrics: [], marks: [], graceSlash: false,
            head: text(el, 'notehead', ''), cue: !!kid(el, 'cue'), staffChange: false,
          };
          if (grace) n.graceSlash = kid(el, 'grace').getAttribute('slash') === 'yes';
          if (rest) {
            const ds = text(rest, 'display-step'); const dov = num(rest, 'display-octave', NaN);
            if (ds && Number.isFinite(dov)) n.pitch = { step: ds, octave: dov, alter: 0, display: true };
          } else {
            const p = kid(el, 'pitch') || kid(el, 'unpitched');
            if (p) n.pitch = { step: text(p, 'step') || text(p, 'display-step') || 'B', octave: num(p, 'octave', num(p, 'display-octave', 4)), alter: num(p, 'alter', 0) };
          }
          for (const b of kids(el, 'beam')) n.beams[b.getAttribute('number') || '1'] = b.textContent.trim();
          for (const t of kids(el, 'tie')) n.tie.push(t.getAttribute('type'));
          const tm = kid(el, 'time-modification');
          if (tm) n.tuplet = { actual: num(tm, 'actual-notes', 1), normal: num(tm, 'normal-notes', 1) };
          const nots = kid(el, 'notations');
          if (nots) {
            for (const s of kids(nots, 'slur')) n.slur.push({ num: s.getAttribute('number') || '1', type: s.getAttribute('type') });
            for (const t of kids(nots, 'tuplet')) n.tupletMark = t.getAttribute('type');
          }
          if (nots) {
            const ART = { staccato: 'stac', staccatissimo: 'stacc', tenuto: 'ten', accent: 'acc', 'strong-accent': 'marc', 'detached-legato': 'dleg', spiccato: 'stacc' };
            const arts = kid(nots, 'articulations');
            if (arts) for (const a of arts.children) if (ART[a.tagName]) n.marks.push(ART[a.tagName]);
            const orn = kid(nots, 'ornaments');
            if (orn) for (const a of orn.children) {
              if (['trill-mark', 'mordent', 'inverted-mordent', 'turn', 'inverted-turn', 'delayed-turn', 'tremolo', 'shake', 'schleifer'].includes(a.tagName)) {
                if (a.tagName === 'tremolo') n.marks.push('trem' + (parseInt(a.textContent, 10) || 1)); else n.marks.push(a.tagName);
              }
            }
            const tech = kid(nots, 'technical');
            if (tech) for (const a of tech.children) {
              if (a.tagName === 'up-bow') n.marks.push('upbow'); else if (a.tagName === 'down-bow') n.marks.push('downbow');
              else if (a.tagName === 'harmonic') n.marks.push('harm'); else if (a.tagName === 'open') n.marks.push('open');
              else if (a.tagName === 'stopped') n.marks.push('stopped'); else if (a.tagName === 'snap-pizzicato') n.marks.push('snap');
              else if (a.tagName === 'fingering') n.fingering = a.textContent.trim();
            }
            const fm = kid(nots, 'fermata');
            if (fm) { n.fermata = true; n.fermataDown = fm.getAttribute('type') === 'inverted'; }
            if (kid(nots, 'arpeggiate')) n.marks.push('arp');
          }
          n.staccato = n.marks.indexOf('stac') >= 0;
          for (const ly of kids(el, 'lyric')) {
            const t = text(ly, 'text', '');
            if (t) n.lyrics.push({ num: parseInt(ly.getAttribute('number') || '1', 10) || 1, text: t, syl: text(ly, 'syllabic', 'single') });
          }
          if (n.lyrics.length) n.lyric = n.lyrics[0].text;
          m.notes.push(n);
          if (!chord) { lastStart = pos; lastDur = durDiv; pos += durDiv; }
          if (!grace) m.maxEnd = Math.max(m.maxEnd, (start + durDiv) / div);
          void lastDur;
        } else if (tag === 'backup') { pos -= num(el, 'duration', 0); if (pos < 0) pos = 0; }
        else if (tag === 'forward') { pos += num(el, 'duration', 0); m.maxEnd = Math.max(m.maxEnd, pos / div); }
        else if (tag === 'direction') parseDirection(el, pos, div, m);
        else if (tag === 'harmony') {
          const tx = harmonyText(el);
          if (tx) m.harm.push({ q: Math.max(0, pos / div + num(el, 'offset', 0) / div), text: tx });
        }
        else if (tag === 'sound') parseSound(el, pos / div, m);
        else if (tag === 'barline') {
          const loc = el.getAttribute('location') || 'right';
          const rep = kid(el, 'repeat');
          const style = text(el, 'bar-style', '');
          if (loc === 'left') m.bar.left = style || m.bar.left; else if (loc === 'right') m.bar.right = style || m.bar.right;
          if (rep) {
            if (rep.getAttribute('direction') === 'forward') m.repeatStart = true;
            else { m.repeatEnd = true; const tt = parseInt(rep.getAttribute('times') || '', 10); if (tt >= 2) m.repeatTimes = tt; }
          }
          const en = kid(el, 'ending');
          if (en) {
            const nums = (en.getAttribute('number') || '1').split(/[,\s]+/).map((x) => parseInt(x, 10)).filter((x) => x > 0);
            const type = en.getAttribute('type');
            if (type === 'start') m.ending = { numbers: nums, label: en.textContent.trim() || nums.join(', ') + '.' };
            else m.endingStop = { numbers: nums, type };
          }
        }
      }
      part.measures.push(m);
    }
    return part;
  }

  // ---------------------------------------------------------------- ordem de execução
  // Simula a leitura: repetições (com N vezes), casas 1/2/..., da capo, dal segno, to coda, fine.
  function playOrder(ms) {
    const n = ms.length;
    const order = [];
    const segno = {}, coda = {};
    ms.forEach((m, i) => { if (m.sound.segno) segno[m.sound.segno] = i; if (m.sound.coda) coda[m.sound.coda] = i; });
    const spans = {}; // início da casa -> {to, numbers}
    ms.forEach((m, i) => {
      if (!m.ending) return;
      let to = i;
      for (let j = i; j < n; j++) { to = j; if (ms[j].endingStop) break; if (j > i && ms[j].ending) { to = j - 1; break; } }
      spans[i] = { to, numbers: m.ending.numbers };
    });
    let maxEnding = 1;
    Object.values(spans).forEach((s) => s.numbers.forEach((x) => { if (x > maxEnding) maxEnding = x; }));
    const count = {};
    let i = 0, pass = 1, lastStart = 0, jumped = false, guard = 0;
    while (i < n && guard++ < 20000) {
      const m = ms[i];
      if (m.repeatStart) { lastStart = i; }
      const sp = spans[i];
      if (sp) {
        const play = jumped ? sp.numbers.includes(maxEnding) : sp.numbers.includes(pass);
        if (!play) { i = sp.to + 1; continue; }
      }
      order.push(i);
      if (jumped && m.sound.fine) break;
      if (jumped && m.sound.tocoda && coda[m.sound.tocoda] !== undefined) { i = coda[m.sound.tocoda]; continue; }
      if (m.repeatEnd && !jumped) {
        const c = count[i] || 0;
        if (c < m.repeatTimes - 1) { count[i] = c + 1; pass = c + 2; i = lastStart; continue; }
        count[i] = 0; pass = 1;
      }
      if (!jumped && (m.sound.dacapo || m.sound.dalsegno)) {
        const target = m.sound.dacapo ? 0 : (segno[m.sound.dalsegno] !== undefined ? segno[m.sound.dalsegno] : 0);
        jumped = true; i = target; pass = 1; continue;
      }
      i++;
    }
    return order;
  }

  // ---------------------------------------------------------------- montagem
  function build(doc) {
    const rootEl = doc.documentElement;
    if (!rootEl || (rootEl.tagName !== 'score-partwise' && rootEl.tagName !== 'score-timewise')) throw new Error('Este arquivo não é uma partitura MusicXML (score-partwise).');
    if (rootEl.tagName === 'score-timewise') throw new Error('MusicXML "timewise" não é suportado. Exporte como "partwise" (o padrão do MuseScore).');
    const title = text(kid(rootEl, 'work'), 'work-title') || text(kid(rootEl, 'movement-title') ? rootEl : null, 'movement-title') || '';
    const infos = {};
    const order = [];
    for (const sp of kids(kid(rootEl, 'part-list') || rootEl, 'score-part')) {
      infos[sp.getAttribute('id')] = { id: sp.getAttribute('id'), name: text(sp, 'part-name', sp.getAttribute('id')) || sp.getAttribute('id'), abbr: text(sp, 'part-abbreviation', '') };
      order.push(sp.getAttribute('id'));
    }
    const parts = [];
    for (const pe of kids(rootEl, 'part')) {
      const id = pe.getAttribute('id');
      parts.push(parsePart(pe, infos[id] || { id, name: id, abbr: '' }));
    }
    if (!parts.length) throw new Error('A partitura não tem nenhuma parte.');
    const count = Math.max.apply(null, parts.map((p) => p.measures.length));

    // estrutura comum
    const measures = [];
    for (let i = 0; i < count; i++) {
      const c = { index: i, number: '', implicit: false, repeatStart: false, repeatEnd: false, repeatTimes: 2, ending: null, endingStop: null, sound: {}, tempos: [], metro: [], time: { b: 4, t: 4 }, maxEnd: 0, lenQ: 4 };
      for (const p of parts) {
        const m = p.measures[i];
        if (!m) continue;
        c.number = c.number || m.number;
        c.implicit = c.implicit || m.implicit;
        c.repeatStart = c.repeatStart || m.repeatStart;
        if (m.repeatEnd) { c.repeatEnd = true; c.repeatTimes = m.repeatTimes; }
        c.ending = c.ending || m.ending;
        c.endingStop = c.endingStop || m.endingStop;
        Object.assign(c.sound, m.sound);
        for (const t of m.tempos) if (!c.tempos.some((x) => Math.abs(x.q - t.q) < 1e-6)) c.tempos.push(t);
        for (const t of m.metro) if (!c.metro.some((x) => Math.abs(x.q - t.q) < 1e-6)) c.metro.push(t);
        if (m.time) c.time = m.time;
        c.maxEnd = Math.max(c.maxEnd, m.maxEnd);
      }
      const nominal = c.time.b * 4 / c.time.t;
      c.lenQ = (c.implicit && c.maxEnd > 0) ? c.maxEnd : Math.max(nominal, c.maxEnd);
      if (c.lenQ <= 0) c.lenQ = nominal;
      c.tempos.sort((a, b) => a.q - b.q);
      // tempo pelo <metronome> só vale quando não há <sound tempo=...> no mesmo ponto
      for (const t of c.metro) if (!c.tempos.some((x) => Math.abs(x.q - t.q) < 1e-6)) c.tempos.push(t);
      c.tempos.sort((a, b) => a.q - b.q);
      measures.push(c);
    }

    const sc = { title, parts, measures, order: [], totalMs: 0, initialBpm: 120, initialTime: measures[0].time };
    const first = measures.find((m) => m.tempos.length);
    if (first) sc.initialBpm = first.tempos[0].bpm;

    // linha do tempo da execução
    const seq = playOrder(measures);
    let bpm = sc.initialBpm, ms = 0, q = 0;
    for (let k = 0; k < seq.length; k++) {
      const m = measures[seq[k]];
      const segs = []; // trechos com andamento constante dentro do compasso
      let cur = 0;
      const ts = m.tempos.slice();
      for (const t of ts) {
        if (t.q > cur + 1e-9) { segs.push({ q0: cur, q1: t.q, bpm }); cur = t.q; }
        bpm = t.bpm;
      }
      segs.push({ q0: cur, q1: m.lenQ, bpm });
      let acc = ms;
      for (const s of segs) { s.ms0 = acc; acc += (s.q1 - s.q0) * 60000 / s.bpm; s.ms1 = acc; }
      sc.order.push({ k, m: seq[k], q0: q, ms0: ms, ms1: acc, segs });
      ms = acc; q += m.lenQ;
    }
    sc.totalMs = ms;
    sc.totalQ = q;

    sc.locate = function (t) {
      const o = sc.order;
      if (!o.length) return { ended: true };
      if (t < 0) return { k: 0, m: o[0].m, q: 0, ended: false, before: true };
      if (t >= sc.totalMs) return { k: o.length - 1, m: o[o.length - 1].m, q: measures[o[o.length - 1].m].lenQ, ended: true };
      let lo = 0, hi = o.length - 1;
      while (lo < hi) { const mid = (lo + hi + 1) >> 1; if (o[mid].ms0 <= t) lo = mid; else hi = mid - 1; }
      const e = o[lo];
      let qq = 0;
      for (const s of e.segs) {
        if (t < s.ms1 || s === e.segs[e.segs.length - 1]) { qq = s.q0 + (t - s.ms0) * s.bpm / 60000; break; }
      }
      return { k: lo, m: e.m, q: Math.min(Math.max(qq, 0), measures[e.m].lenQ), ended: false };
    };
    sc.msOf = function (k, qq) {
      const e = sc.order[Math.min(Math.max(k, 0), sc.order.length - 1)];
      for (const s of e.segs) if (qq <= s.q1 + 1e-9) return s.ms0 + (qq - s.q0) * 60000 / s.bpm;
      return e.ms1;
    };
    return sc;
  }

  function parseText(xmlText) {
    const doc = new DOMParser().parseFromString(xmlText, 'application/xml');
    const err = doc.getElementsByTagName('parsererror')[0];
    if (err) throw new Error('XML inválido: ' + err.textContent.slice(0, 120));
    return build(doc);
  }

  async function load(buf) { return parseText(await xmlTextOf(buf)); }

  root.SincronoScore = Object.assign(root.SincronoScore || {}, { load, parseText, playOrder, UNIT_Q });
})(typeof window !== 'undefined' ? window : globalThis);
