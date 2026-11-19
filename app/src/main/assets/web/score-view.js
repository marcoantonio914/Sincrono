// Desenho da partitura em SVG + cursor (Sincrono). Carregar DEPOIS de score.js. Sem bibliotecas externas.
//
//   const v = new SincronoScore.ScoreView(divComRolagem, { mode: 'continuous' | 'pages', zoom: 1 });
//   v.setScore(score, indiceDaParte);      // desenha UMA parte (piano = 2 pautas)
//   v.setCursor({ k, q }, follow);         // k = posição na ordem de execução, q = tempo (semínimas) dentro do compasso
//   v.setCursor({ m, q }, follow);         // ou direto pelo índice do compasso escrito
//   v.setCursor(null);                     // esconde
//   v.setMode('pages'); v.setZoom(1.4);
//
// O desenho é feito UMA vez; depois só o cursor se move. Não desenha: notas de adorno, oitavas 8va, pedal,
// crescendo/decrescendo (vira texto), vozes cruzadas complexas, letras com mais de um verso.
(function (root) {
  'use strict';
  const NS = 'http://www.w3.org/2000/svg';
  const SP = 10;                 // espaço entre linhas da pauta
  const STAFF_H = 4 * SP;
  const TOP_PAD = 62;            // espaço acima da pauta (andamento, casas, número do compasso)
  const BOT_PAD = 64;            // espaço abaixo (dinâmicas, letra)
  const STAFF_GAP = 62;          // entre pautas do piano
  const STEP = { C: 0, D: 1, E: 2, F: 3, G: 4, A: 5, B: 6 };
  const SHARP_D = [3, 0, 4, 1, 5, 2, 6];           // F C G D A E B (grau dentro da oitava)
  const FLAT_D = [6, 2, 5, 1, 4, 0, 3];            // B E A D G C F
  const SEC = { whole: 4, half: 2, quarter: 1, eighth: 0.5, '16th': 0.25, '32nd': 0.125, '64th': 0.0625, breve: 8 };
  const BEAMS = { eighth: 1, '16th': 2, '32nd': 3, '64th': 4 };

  const f1 = (n) => (Math.round(n * 10) / 10).toString();
  const esc = (t) => String(t).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

  // grau diatônico (oitava*7 + passo) da linha de baixo da pauta, conforme a clave
  function clefBase(c) {
    const sign = (c && c.sign) || 'G';
    const line = (c && Number.isFinite(c.line)) ? c.line : (sign === 'F' ? 4 : sign === 'C' ? 3 : 2);
    const ref = sign === 'G' ? 32 : sign === 'F' ? 24 : 28;
    return ref - 2 * (line - 1) + 7 * ((c && c.oct) || 0);
  }
  const degree = (p) => p.octave * 7 + STEP[p.step];

  function keyPositions(fifths, clef) {
    const sign = (clef && clef.sign) || 'G';
    const base = clefBase(clef);
    const out = [];
    const list = fifths > 0 ? SHARP_D : FLAT_D;
    // oitava de referência: agudo dentro da pauta
    const sharpTreble = [39, 35, 38, 34, 30, 33, 29], flatTreble = [34, 37, 33, 36, 32, 35, 31];
    const ref = fifths > 0 ? sharpTreble : flatTreble;
    const shift = sign === 'F' ? -14 : sign === 'C' ? -7 : 0;
    const octShift = (clef && clef.oct ? 7 * clef.oct : 0);
    void list; void base;
    for (let i = 0; i < Math.abs(fifths) && i < 7; i++) out.push(ref[i] + shift + octShift);
    return out;
  }

  class ScoreView {
    constructor(container, opts) {
      opts = opts || {};
      this.box = container;
      this.mode = opts.mode || 'continuous';
      this.zoom = opts.zoom || 1;
      this.score = null; this.partIdx = 0;
      this.systems = []; this.geo = [];
      this.pausedUntil = 0;
      this.cur = null;
      this.svg = null;
      const pause = () => { this.pausedUntil = Date.now() + 4000; };
      container.addEventListener('wheel', pause, { passive: true });
      container.addEventListener('touchstart', pause, { passive: true });
      container.addEventListener('pointerdown', pause, { passive: true });
    }

    setScore(score, partIdx) { this.score = score; this.partIdx = partIdx || 0; this.render(); }
    setPart(i) { this.partIdx = i; this.render(); }
    setMode(m) { this.mode = m; this.render(); }
    setZoom(z) { this.zoom = z; this.render(); }

    // ------------------------------------------------------------ medidas e posições
    layoutMeasure(part, i) {
      const sc = this.score;
      const m = part.measures[i] || { notes: [], dirs: [], clefs: {}, key: 0, time: sc.measures[i].time };
      const cm = sc.measures[i];
      const lenQ = cm.lenQ;
      const notes = m.notes.filter((n) => !n.grace);
      const starts = [];
      for (const n of notes) { const s = Math.round(n.start * 1e4) / 1e4; if (starts.indexOf(s) < 0) starts.push(s); }
      starts.sort((a, b) => a - b);
      if (!starts.length) starts.push(0);
      const slots = starts.map((s, idx) => {
        const next = idx + 1 < starts.length ? starts[idx + 1] : lenQ;
        const dur = Math.max(0.0625, next - s);
        const acc = notes.some((n) => Math.abs(n.start - s) < 1e-4 && n.accidental) ? 11 : 0;
        const w = 15 + Math.min(dur, 4) * 20 + acc;
        return { s, dur, acc, w };
      });
      let off = 0;
      for (const sl of slots) { sl.off = off; off += sl.w; }
      return { m, cm, lenQ, slots, inner: off, notes };
    }

    slotX(lay, q, scl) {
      // x relativo ao início do espaço interno do compasso
      const sl = lay.slots;
      const head = (k) => (sl[k].off + sl[k].acc + 6) * scl;
      let k = 0;
      for (let j = 0; j < sl.length; j++) if (sl[j].s <= q + 1e-6) k = j;
      const x0 = head(k);
      const next = k + 1 < sl.length ? sl[k + 1].s : lay.lenQ;
      const x1 = k + 1 < sl.length ? head(k + 1) : lay.inner * scl - 4;
      const span = Math.max(1e-6, next - sl[k].s);
      return x0 + Math.max(0, Math.min(1, (q - sl[k].s) / span)) * (x1 - x0);
    }

    // ------------------------------------------------------------ desenho
    render() {
      const sc = this.score;
      if (!sc) return;
      const part = sc.parts[this.partIdx] || sc.parts[0];
      const nst = Math.max(1, part.staves || 1);
      const z = this.zoom;
      const availW = Math.max(240, this.box.clientWidth / z - 24);
      const lays = sc.measures.map((_, i) => this.layoutMeasure(part, i));

      // largura de cada compasso (sem justificar) e quebra em sistemas
      const prefixW = (i) => {
        const m = lays[i].m;
        const c1 = (m.clefs && m.clefs[1]) || { sign: 'G' };
        void c1;
        return 38 + Math.abs(m.key || 0) * 9 + (m.time ? 26 : 0) + 8;
      };
      const midChange = (i) => {
        const m = lays[i].m; let w = 0;
        if (i > 0 && m.newClefs) w += 26;
        if (i > 0 && m.newKey !== null && m.newKey !== undefined) w += Math.abs(m.newKey) * 9 + 8;
        if (i > 0 && m.newTime) w += 26;
        return w;
      };
      const leftRep = (i) => (lays[i].cm.repeatStart ? 12 : 0);
      this.systems = [];
      let sys = null;
      for (let i = 0; i < lays.length; i++) {
        const L = lays[i];
        const minInner = Math.max(L.inner, 46);
        L.minInner = minInner;
        L.mid = midChange(i);
        L.nat = 12 + leftRep(i) + L.mid + minInner + 10;
        const firstW = prefixW(i);
        if (!sys) sys = { ms: [], pre: firstW, w: firstW };
        if (this.mode === 'pages' && sys.ms.length && sys.w + L.nat > availW) {
          this.systems.push(sys); sys = { ms: [], pre: firstW, w: firstW };
        }
        sys.ms.push(i); sys.w += L.nat;
      }
      if (sys && sys.ms.length) this.systems.push(sys);

      const sysH = TOP_PAD + STAFF_H + (nst > 1 ? STAFF_GAP + STAFF_H : 0) + BOT_PAD;
      const out = [];
      this.geo = new Array(lays.length);
      const ties = {}; const slurs = {};
      let totalW = 0;
      this.systems.forEach((S, si) => {
        const last = si === this.systems.length - 1;
        let extra = 0;
        if (this.mode === 'pages' && !(last && S.w < availW * 0.6)) extra = Math.max(0, availW - S.w);
        const innerSum = S.ms.reduce((a, i) => a + lays[i].minInner, 0);
        const y0 = si * sysH + TOP_PAD;                       // topo da pauta 1
        const staffTop = (st) => y0 + (st - 1) * (STAFF_H + STAFF_GAP);
        const yBottom = staffTop(nst) + STAFF_H;
        let x = 26;
        S.top = si * sysH; S.bottom = (si + 1) * sysH; S.y0 = y0; S.yb = yBottom; S.x0 = x;
        // pautas, chave de piano, clave, armadura, fórmula
        const firstM = lays[S.ms[0]].m;
        for (let st = 1; st <= nst; st++) {
          const clef = (firstM.clefs && firstM.clefs[st]) || (st === 2 && nst === 2 ? { sign: 'F', line: 4 } : { sign: 'G', line: 2 });
          S.clefs = S.clefs || {}; S.clefs[st] = clef;
        }
        let px = x + 4;
        for (let st = 1; st <= nst; st++) {
          const clef = S.clefs[st], yt = staffTop(st);
          out.push(this.clefSvg(clef, px, yt));
          const kp = keyPositions(firstM.key || 0, clef), base = clefBase(clef);
          kp.forEach((d, j) => out.push(this.accSvg((firstM.key || 0) > 0 ? 'sharp' : 'flat', px + 36 + j * 9, yt + STAFF_H - (d - base) * SP / 2)));
          if (firstM.time) out.push(this.timeSvg(firstM.time, px + 36 + Math.abs(firstM.key || 0) * 9 + 6, yt));
        }
        x += S.pre;
        // compassos
        const startX = x;
        S.ms.forEach((i, idx) => {
          const L = lays[i];
          const add = innerSum ? extra * (L.minInner / innerSum) : 0;
          const inner = L.minInner + add;
          const scl = L.inner ? inner / L.inner : 1;
          const mx = x, ix = x + 12 + leftRep(i) + L.mid;
          const w = L.nat - L.minInner + inner;
          this.geo[i] = { sys: si, x: mx, w, ix, scl, lay: L, y0, yb: yBottom, top: S.top };
          this.drawMeasure(out, i, L, part, nst, mx, w, ix, scl, staffTop, yBottom, si, idx === 0, ties, slurs, S);
          x += w;
        });
        // linhas das pautas e barra inicial
        const lines = [];
        for (let st = 1; st <= nst; st++) {
          const yt = staffTop(st);
          for (let l = 0; l < 5; l++) lines.push('<line x1="' + f1(S.x0) + '" x2="' + f1(x) + '" y1="' + f1(yt + l * SP) + '" y2="' + f1(yt + l * SP) + '"/>');
        }
        out.unshift('<g stroke="currentColor" stroke-width="1" opacity="0.85">' + lines.join('') + '</g>');
        out.push('<line x1="' + f1(S.x0) + '" x2="' + f1(S.x0) + '" y1="' + f1(y0) + '" y2="' + f1(yBottom) + '" stroke="currentColor" stroke-width="1.6"/>');
        if (nst > 1) out.push('<path d="M' + f1(S.x0 - 3) + ',' + f1(y0) + ' C' + f1(S.x0 - 12) + ',' + f1(y0 + 20) + ' ' + f1(S.x0 - 12) + ',' + f1((y0 + yBottom) / 2 - 8) + ' ' + f1(S.x0 - 14) + ',' + f1((y0 + yBottom) / 2) + ' C' + f1(S.x0 - 12) + ',' + f1((y0 + yBottom) / 2 + 8) + ' ' + f1(S.x0 - 12) + ',' + f1(yBottom - 20) + ' ' + f1(S.x0 - 3) + ',' + f1(yBottom) + '" fill="none" stroke="currentColor" stroke-width="2"/>');
        S.x1 = x; S.startX = startX;
        totalW = Math.max(totalW, x + 20);
      });

      const totalH = this.systems.length * sysH + 10;
      const cursor = '<g class="cur" style="display:none"><rect class="hl" x="0" y="0" width="10" height="10" fill="#ffb300" opacity="0.16"/>' +
        '<line class="ln" x1="0" x2="0" y1="0" y2="10" stroke="#e53935" stroke-width="2.4" stroke-linecap="round"/></g>';
      const W = this.mode === 'pages' ? Math.max(availW + 24, 240) : totalW;
      this.box.innerHTML = '';
      const svg = document.createElementNS(NS, 'svg');
      svg.setAttribute('xmlns', NS);
      svg.setAttribute('width', f1(W * z));
      svg.setAttribute('height', f1(totalH * z));
      svg.setAttribute('viewBox', '0 0 ' + f1(W) + ' ' + f1(totalH));
      svg.style.display = 'block';
      svg.style.color = 'inherit';
      svg.setAttribute('font-family', 'Georgia, "Times New Roman", serif');
      svg.innerHTML = out.join('') + cursor;
      this.box.appendChild(svg);
      this.svg = svg;
      this.curG = svg.querySelector('.cur');
      this.curHl = svg.querySelector('.hl');
      this.curLine = svg.querySelector('.ln');
      this.lastCursor = null;
      if (this.pendingCursor) this.setCursor(this.pendingCursor, false);
    }

    clefSvg(c, x, yt) {
      const sign = (c && c.sign) || 'G';
      const line = (c && Number.isFinite(c.line)) ? c.line : (sign === 'F' ? 4 : sign === 'C' ? 3 : 2);
      const y = yt + STAFF_H - (line - 1) * SP;     // y da linha da clave
      const g = '<g transform="translate(' + f1(x + 12) + ',' + f1(y) + ')" fill="none" stroke="currentColor" stroke-width="2.3" stroke-linecap="round">';
      if (sign === 'G') {
        return g + '<path d="M2,24 C-3,26 -5,20 -1,18 C3,17 5,22 2,25 M1,22 L4,-33 C5,-42 11,-44 11,-36 C11,-27 3,-20 -3,-12 C-10,-3 -9,9 -1,11 C8,13 10,2 4,-2 C-1,-5 -6,0 -3,5"/></g>';
      }
      if (sign === 'F') {
        return g + '<path d="M-8,0 C-8,-7 1,-10 6,-5 C11,0 7,12 -9,19"/><circle cx="-8" cy="0" r="3.2" fill="currentColor" stroke="none"/><circle cx="13" cy="-5" r="1.9" fill="currentColor" stroke="none"/><circle cx="13" cy="5" r="1.9" fill="currentColor" stroke="none"/></g>';
      }
      return g + '<path d="M-8,-20 L-8,20 M-3,-20 L-3,20" stroke-width="3.2"/><path d="M1,-10 C10,-14 14,-4 5,0 C14,4 10,14 1,10" stroke-width="2.2"/></g>';
    }

    timeSvg(t, x, yt) {
      if (t.symbol === 'common') return '<text x="' + f1(x + 8) + '" y="' + f1(yt + 28) + '" font-size="34" font-weight="bold" text-anchor="middle" fill="currentColor">C</text>';
      if (t.symbol === 'cut') return '<text x="' + f1(x + 8) + '" y="' + f1(yt + 28) + '" font-size="34" font-weight="bold" text-anchor="middle" fill="currentColor">C</text><line x1="' + f1(x + 8) + '" x2="' + f1(x + 8) + '" y1="' + f1(yt + 2) + '" y2="' + f1(yt + 38) + '" stroke="currentColor" stroke-width="1.6"/>';
      const a = '<text x="' + f1(x + 8) + '" y="' + f1(yt + 19) + '" font-size="26" font-weight="bold" text-anchor="middle" fill="currentColor">' + esc(t.b) + '</text>';
      return a + '<text x="' + f1(x + 8) + '" y="' + f1(yt + 39) + '" font-size="26" font-weight="bold" text-anchor="middle" fill="currentColor">' + esc(t.t) + '</text>';
    }

    accSvg(kind, x, y) {
      const g = '<g transform="translate(' + f1(x) + ',' + f1(y) + ')" stroke="currentColor" stroke-width="1.3" fill="none" stroke-linecap="round">';
      if (kind === 'sharp' || kind === 'double-sharp') return g + '<path d="M-2.2,-9 V9 M2.2,-10 V8"/><path d="M-5,-2.5 L5,-4.8 M-5,4.5 L5,2.2" stroke-width="2.4"/></g>';
      if (kind === 'flat') return g + '<path d="M-3,-14 V4 M-3,4 C6,0 6,-9 -3,-3" stroke-width="1.7"/></g>';
      return g + '<path d="M-3,-10 V4 M3,-4 V10 M-3,-2.5 L3,-4.5 M-3,4.5 L3,2.5" stroke-width="1.6"/></g>';
    }

    restSvg(type, x, yt, measure) {
      const mid = yt + 2 * SP;
      const col = 'fill="currentColor" stroke="currentColor"';
      if (measure || type === 'whole') return '<rect x="' + f1(x - 6) + '" y="' + f1(yt + SP) + '" width="12" height="' + f1(SP / 2) + '" ' + col + '/>';
      if (type === 'half') return '<rect x="' + f1(x - 6) + '" y="' + f1(mid - SP / 2) + '" width="12" height="' + f1(SP / 2) + '" ' + col + '/>';
      if (type === 'quarter') return '<path d="M' + f1(x) + ',' + f1(mid - 13) + ' L' + f1(x + 4) + ',' + f1(mid - 6) + ' L' + f1(x - 2) + ',' + f1(mid) + ' L' + f1(x + 3) + ',' + f1(mid + 7) + ' C' + f1(x - 3) + ',' + f1(mid + 5) + ' ' + f1(x - 5) + ',' + f1(mid + 10) + ' ' + f1(x - 1) + ',' + f1(mid + 13) + '" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"/>';
      const n = BEAMS[type] || 1;
      let s = '<path d="M' + f1(x + 3) + ',' + f1(mid - 6) + ' L' + f1(x - 3 - n * 1.5) + ',' + f1(mid + 6 + n * 4) + '" stroke="currentColor" stroke-width="1.6" fill="none"/>';
      for (let j = 0; j < n; j++) s += '<circle cx="' + f1(x - 1 + 0 - j * 1.4) + '" cy="' + f1(mid - 4 + j * 5) + '" r="2.2" fill="currentColor"/>';
      return s;
    }

    drawMeasure(out, i, L, part, nst, mx, w, ix, scl, staffTop, yBottom, si, isFirst, ties, slurs, S) {
      const cm = L.cm, m = L.m;
      const xr = mx + w;
      const yTop = staffTop(1);
      const X = (q) => ix + this.slotX(L, q, scl);
      this.geo[i].X = X;
      // alterações no meio da linha
      let cx = mx + 8 + (cm.repeatStart ? 12 : 0);
      if (!isFirst && i > 0) {
        for (let st = 1; st <= nst; st++) {
          if (m.newClefs && m.clefs[st]) out.push(this.clefSvg(m.clefs[st], cx - 4, staffTop(st)));
          let kx = cx + (m.newClefs ? 24 : 0);
          if (m.newKey !== null && m.newKey !== undefined) {
            const clef = m.clefs[st] || { sign: 'G' }, base = clefBase(clef);
            keyPositions(m.newKey, clef).forEach((d, j) => out.push(this.accSvg(m.newKey > 0 ? 'sharp' : 'flat', kx + j * 9, staffTop(st) + STAFF_H - (d - base) * SP / 2)));
            kx += Math.abs(m.newKey) * 9 + 6;
          }
          if (m.newTime) out.push(this.timeSvg(m.newTime, kx, staffTop(st)));
        }
      }
      // barras de compasso
      const yb = yBottom, y0 = yTop;
      const vline = (x, sw) => '<line x1="' + f1(x) + '" x2="' + f1(x) + '" y1="' + f1(y0) + '" y2="' + f1(yb) + '" stroke="currentColor" stroke-width="' + sw + '"/>';
      const dots = (x) => { let s = ''; for (let st = 1; st <= nst; st++) { const yt = staffTop(st); s += '<circle cx="' + f1(x) + '" cy="' + f1(yt + 1.5 * SP) + '" r="2" fill="currentColor"/><circle cx="' + f1(x) + '" cy="' + f1(yt + 2.5 * SP) + '" r="2" fill="currentColor"/>'; } return s; };
      if (cm.repeatStart) out.push(vline(mx + 2, 4) + vline(mx + 7, 1) + dots(mx + 12));
      const rb = m.bar && m.bar.right;
      if (cm.repeatEnd) out.push(dots(xr - 13) + vline(xr - 7, 1) + vline(xr - 2, 4));
      else if (i === this.score.measures.length - 1 || rb === 'light-heavy') out.push(vline(xr - 6, 1) + vline(xr - 2, 4));
      else if (rb === 'light-light') out.push(vline(xr - 5, 1) + vline(xr, 1));
      else out.push(vline(xr, 1));
      // número do compasso
      if (isFirst && !cm.ending) out.push('<text x="' + f1(mx + 6) + '" y="' + f1(yTop - 8) + '" font-size="10" fill="currentColor" opacity="0.6">' + esc(cm.number || (i + 1)) + '</text>');
      // casas de repetição
      if (cm.ending) {
        const on = this.endingOn = true; void on;
      }
      // (o traço da casa é desenhado abaixo, junto do estado ativo)
      const endAct = this.endingActive;
      if (cm.ending) this.endingActive = true;
      if (this.endingActive || endAct) {
        out.push('<path d="M' + f1(cm.ending ? mx + 2 : mx) + ',' + f1(yTop - 14) + ' H' + f1(xr) + (cm.ending ? '' : '') + '" stroke="currentColor" fill="none" stroke-width="1.2"/>');
        if (cm.ending) out.push('<path d="M' + f1(mx + 2) + ',' + f1(yTop - 14) + ' V' + f1(yTop - 4) + '" stroke="currentColor" fill="none" stroke-width="1.2"/><text x="' + f1(mx + 7) + '" y="' + f1(yTop - 17) + '" font-size="11" fill="currentColor">' + esc(cm.ending.label) + '</text>');
        if (cm.endingStop) {
          this.endingActive = false;
          if (cm.endingStop.type === 'stop') out.push('<path d="M' + f1(xr - 0.6) + ',' + f1(yTop - 14) + ' V' + f1(yTop - 4) + '" stroke="currentColor" fill="none" stroke-width="1.2"/>');
        }
      }
      // sinais: segno/coda/da capo...
      const marks = [];
      if (cm.sound.dacapo) marks.push('D.C.'); if (cm.sound.dalsegno) marks.push('D.S.'); if (cm.sound.fine) marks.push('Fine'); if (cm.sound.tocoda) marks.push('To Coda');
      marks.forEach((t) => out.push('<text x="' + f1(xr - 4) + '" y="' + f1(yTop - 22) + '" font-size="11" font-style="italic" text-anchor="end" fill="currentColor">' + t + '</text>'));
      // direções (andamento, palavras, ensaio, dinâmica)
      let aboveR = mx + 14, belowR = mx + 4;
      const dirs = (m.dirs || []).slice().sort((a, b) => a.q - b.q);
      for (const d of dirs) {
        const dx = X(Math.min(d.q, L.lenQ));
        if (d.kind === 'dyn') {
          const y = (nst > 1 ? yTop + STAFF_H + 30 : yBottom + 24);
          const xx = Math.max(dx - 6, belowR);
          out.push('<text x="' + f1(xx) + '" y="' + f1(y) + '" font-size="14" font-style="italic" font-weight="bold" fill="currentColor">' + esc(d.text) + '</text>');
          belowR = xx + d.text.length * 8 + 6;
        } else if (d.kind === 'words') {
          const xx = Math.max(dx, aboveR);
          const above = d.place !== 'below';
          const y = above ? yTop - 28 : yBottom + 24;
          out.push('<text x="' + f1(xx) + '" y="' + f1(y) + '" font-size="12" ' + (d.italic || /^[a-z.]+$/.test(d.text) ? 'font-style="italic" ' : 'font-weight="bold" ') + 'fill="currentColor">' + esc(d.text) + '</text>');
          if (above) aboveR = xx + d.text.length * 6.5 + 8; else belowR = xx + d.text.length * 6.5 + 8;
        } else if (d.kind === 'metro') {
          const xx = Math.max(dx, aboveR), y = yTop - 28;
          const note = '<ellipse cx="' + f1(xx + 3) + '" cy="' + f1(y - 2) + '" rx="3.4" ry="2.5" transform="rotate(-20 ' + f1(xx + 3) + ' ' + f1(y - 2) + ')" fill="' + (d.unit === 'half' || d.unit === 'whole' ? 'none" stroke="currentColor' : 'currentColor') + '"/>' +
            (d.unit !== 'whole' ? '<line x1="' + f1(xx + 6) + '" x2="' + f1(xx + 6) + '" y1="' + f1(y - 3) + '" y2="' + f1(y - 14) + '" stroke="currentColor" stroke-width="1.2"/>' : '');
          out.push(note + '<text x="' + f1(xx + 11) + '" y="' + f1(y) + '" font-size="12" fill="currentColor">= ' + esc(d.perMinute) + '</text>');
          aboveR = xx + 52;
        } else if (d.kind === 'rehearsal') {
          const xx = Math.max(dx, aboveR) - 2;
          out.push('<rect x="' + f1(xx) + '" y="' + f1(yTop - 46) + '" width="' + f1(d.text.length * 9 + 10) + '" height="17" fill="none" stroke="currentColor" stroke-width="1.4"/><text x="' + f1(xx + 5) + '" y="' + f1(yTop - 33) + '" font-size="12" font-weight="bold" fill="currentColor">' + esc(d.text) + '</text>');
          aboveR = xx + d.text.length * 9 + 16;
        } else if (d.kind === 'wedge' && (d.type === 'crescendo' || d.type === 'diminuendo')) {
          out.push('<text x="' + f1(dx) + '" y="' + f1(nst > 1 ? yTop + STAFF_H + 30 : yBottom + 24) + '" font-size="12" font-style="italic" fill="currentColor">' + (d.type === 'crescendo' ? 'cresc.' : 'dim.') + '</text>');
        }
      }
      // notas, por pauta
      for (let st = 1; st <= nst; st++) this.drawStaffNotes(out, i, L, st, nst, X, staffTop(st), mx, w, ties, slurs, si, yBottom);
    }

    drawStaffNotes(out, i, L, st, nst, X, yt, mx, w, ties, slurs, si, yBottom) {
      const clef = (L.m.clefs && L.m.clefs[st]) || (st === 2 && nst === 2 ? { sign: 'F', line: 4 } : { sign: 'G', line: 2 });
      const base = clefBase(clef), mid = base + 4;
      const yOf = (d) => yt + STAFF_H - (d - base) * SP / 2;
      const ns = L.notes.filter((n) => n.staff === st);
      // eventos por voz e início (acordes = mesma entrada)
      const evs = []; const lastByVoice = {};
      for (const n of ns) {
        if (n.chord && lastByVoice[n.voice]) { lastByVoice[n.voice].notes.push(n); continue; }
        const e = { voice: n.voice, start: n.start, dur: n.dur, notes: [n], type: n.type, dots: n.dots, rest: n.rest, measureRest: n.measureRest, beams: n.beams, tuplet: n.tuplet, tupletMark: n.tupletMark, lyric: n.lyric };
        evs.push(e); lastByVoice[n.voice] = e;
      }
      const voices = Array.from(new Set(evs.map((e) => e.voice))).sort();
      for (const e of evs) {
        const t = e.type || (e.measureRest ? 'whole' : (e.dur >= 4 ? 'whole' : e.dur >= 2 ? 'half' : e.dur >= 1 ? 'quarter' : e.dur >= 0.5 ? 'eighth' : '16th'));
        e.t = t;
        e.x = X(e.start);
        if (e.rest) {
          const n0 = e.notes[0];
          e.x = e.measureRest ? mx + w / 2 : e.x;
          const dy = n0.pitch ? (yOf(degree(n0.pitch)) - (yt + 2 * SP)) : 0;
          out.push('<g transform="translate(0,' + f1(dy) + ')">' + this.restSvg(t, e.x, yt, e.measureRest) + '</g>');
          if (e.dots) out.push('<circle cx="' + f1(e.x + 9) + '" cy="' + f1(yt + 2 * SP - 3 + dy) + '" r="1.8" fill="currentColor"/>');
          continue;
        }
        e.ds = e.notes.map((n) => degree(n.pitch || { step: 'B', octave: 4 })).sort((a, b) => a - b);
        e.lo = e.ds[0]; e.hi = e.ds[e.ds.length - 1];
        const exp = e.notes.find((n) => n.stem === 'up' || n.stem === 'down');
        if (exp) e.dir = exp.stem; else if (voices.length > 1) e.dir = voices.indexOf(e.voice) === 0 ? 'up' : 'down';
        else e.dir = ((e.lo + e.hi) / 2 < mid) ? 'up' : 'down';
      }
      // grupos de barra
      const groups = []; let cur = null;
      for (const e of evs) {
        if (e.rest || !BEAMS[e.t]) { cur = null; continue; }
        const b1 = e.beams && e.beams['1'];
        if (b1 === 'begin') { cur = [e]; groups.push(cur); } else if (cur && (b1 === 'continue' || b1 === 'end') && cur[0].voice === e.voice) { cur.push(e); if (b1 === 'end') cur = null; } else cur = null;
      }
      const grouped = new Set();
      for (const g of groups) {
        if (g.length < 2) continue;
        let sum = 0; g.forEach((e) => { sum += ((e.lo + e.hi) / 2) - mid; });
        const dir = g.find((e) => e.notes.some((n) => n.stem)) ? g[0].dir : (voices.length > 1 ? g[0].dir : (sum < 0 ? 'up' : 'down'));
        g.forEach((e) => { e.dir = dir; e.grp = g; grouped.add(e); });
        const ends = g.map((e) => dir === 'up' ? yOf(e.hi) - 3.5 * SP : yOf(e.lo) + 3.5 * SP);
        g.beamY = dir === 'up' ? Math.min.apply(null, ends) : Math.max.apply(null, ends);
        // barra não deve cruzar o meio da pauta para o lado errado
        if (dir === 'up') g.beamY = Math.min(g.beamY, yt + 2 * SP); else g.beamY = Math.max(g.beamY, yt + 2 * SP);
      }
      const sx = (e) => e.x + (e.dir === 'up' ? 5.3 : -5.3);
      // cabeças, acidentes, pontos, hastes
      for (const e of evs) {
        if (e.rest) continue;
        const open = e.t === 'whole' || e.t === 'half' || e.t === 'breve';
        const head = [];
        const sorted = e.notes.map((n) => ({ n, d: degree(n.pitch || { step: 'B', octave: 4 }) })).sort((a, b) => a.d - b.d);
        let prevD = null, shift = false;
        const ordered = e.dir === 'up' ? sorted : sorted.slice().reverse();
        for (const o of ordered) {
          if (prevD !== null && Math.abs(o.d - prevD) === 1 && !shift) { o.off = e.dir === 'up' ? 10.4 : -10.4; shift = true; } else { o.off = 0; shift = false; }
          prevD = o.d;
        }
        let accLeft = 0;
        for (const o of sorted) {
          const y = yOf(o.d), hx = e.x + (o.off || 0);
          if (e.t !== 'whole') head.push('<ellipse cx="' + f1(hx) + '" cy="' + f1(y) + '" rx="5.6" ry="4" transform="rotate(-20 ' + f1(hx) + ' ' + f1(y) + ')" ' + (open ? 'fill="none" stroke="currentColor" stroke-width="1.6"' : 'fill="currentColor"') + '/>');
          else head.push('<ellipse cx="' + f1(hx) + '" cy="' + f1(y) + '" rx="6.6" ry="4.2" fill="none" stroke="currentColor" stroke-width="1.8"/>');
          // linhas suplementares
          if (o.d <= base - 2) for (let dd = base - 2; dd >= o.d; dd -= 2) head.push('<line x1="' + f1(hx - 9) + '" x2="' + f1(hx + 9) + '" y1="' + f1(yOf(dd)) + '" y2="' + f1(yOf(dd)) + '" stroke="currentColor" stroke-width="1.2"/>');
          if (o.d >= base + 10) for (let dd = base + 10; dd <= o.d; dd += 2) head.push('<line x1="' + f1(hx - 9) + '" x2="' + f1(hx + 9) + '" y1="' + f1(yOf(dd)) + '" y2="' + f1(yOf(dd)) + '" stroke="currentColor" stroke-width="1.2"/>');
          const al = o.n.accidental;
          if (al) { const kind = al.indexOf('sharp') >= 0 ? 'sharp' : al.indexOf('flat') >= 0 ? 'flat' : 'natural'; head.push(this.accSvg(kind, hx - 12 - accLeft, y)); accLeft = accLeft ? 0 : 0; }
          if (e.dots) { const onLine = ((o.d - base) % 2 + 2) % 2 === 0; for (let k = 0; k < e.dots; k++) head.push('<circle cx="' + f1(hx + 10 + k * 4.5) + '" cy="' + f1(y - (onLine ? SP / 2 : 0)) + '" r="1.7" fill="currentColor"/>'); }
          if (o.n.staccato) head.push('<circle cx="' + f1(e.x) + '" cy="' + f1(e.dir === 'up' ? y + 9 : y - 9) + '" r="1.6" fill="currentColor"/>');
          if (o.n.fermata) head.push('<path d="M' + f1(e.x - 7) + ',' + f1(yt - 6) + ' a7,7 0 0 1 14,0" fill="none" stroke="currentColor" stroke-width="1.5"/><circle cx="' + f1(e.x) + '" cy="' + f1(yt - 7) + '" r="1.5" fill="currentColor"/>');
        }
        out.push(head.join(''));
        // haste
        if (e.t !== 'whole' && e.t !== 'breve') {
          const x = sx(e);
          const yA = e.dir === 'up' ? yOf(e.lo) : yOf(e.hi);       // lado da cabeça de onde parte a haste
          let yE;
          if (e.grp) yE = e.grp.beamY; else yE = e.dir === 'up' ? Math.min(yOf(e.hi) - 3.5 * SP, yt + 2 * SP) : Math.max(yOf(e.lo) + 3.5 * SP, yt + 2 * SP);
          const yFar = e.dir === 'up' ? yOf(e.hi) : yOf(e.lo);
          e.stemX = x; e.stemEnd = yE;
          out.push('<line x1="' + f1(x) + '" x2="' + f1(x) + '" y1="' + f1(e.dir === 'up' ? Math.max(yA, yFar) : Math.min(yA, yFar)) + '" y2="' + f1(yE) + '" stroke="currentColor" stroke-width="1.3"/>');
          if (!e.grp && BEAMS[e.t]) {
            const n = BEAMS[e.t];
            for (let k = 0; k < n; k++) {
              const yy = e.dir === 'up' ? yE + k * 7 : yE - k * 7;
              out.push(e.dir === 'up'
                ? '<path d="M' + f1(x) + ',' + f1(yy) + ' c0,8 10,11 7,24" fill="none" stroke="currentColor" stroke-width="2.2"/>'
                : '<path d="M' + f1(x) + ',' + f1(yy) + ' c0,-8 10,-11 7,-24" fill="none" stroke="currentColor" stroke-width="2.2"/>');
            }
          }
        }
        // letra (apenas pauta única)
        if (e.lyric && nst === 1) out.push('<text x="' + f1(e.x) + '" y="' + f1(yBottom + 42) + '" font-size="12" text-anchor="middle" fill="currentColor">' + esc(e.lyric) + '</text>');
      }
      // barras
      for (const g of groups) {
        if (g.length < 2) continue;
        const dir = g[0].dir, by = g.beamY;
        const level = (L2, y0) => {
          let k = 0;
          while (k < g.length) {
            const has = (e) => e.beams && e.beams[String(L2)] && BEAMS[e.t] >= L2;
            if (!has(g[k])) { k++; continue; }
            let j = k;
            while (j + 1 < g.length && has(g[j + 1]) && g[j].beams[String(L2)] !== 'end' && g[j + 1].beams[String(L2)] !== 'begin') j++;
            let xa = g[k].stemX, xb = g[j].stemX;
            if (j === k) { const hook = g[k].beams[String(L2)]; const left = (hook === 'backward hook') || (hook === 'end') || (k === g.length - 1 && hook !== 'forward hook'); xa = left ? xb - 9 : xb; xb = left ? xb : xb + 9; }
            out.push('<polygon points="' + f1(xa) + ',' + f1(y0) + ' ' + f1(xb) + ',' + f1(y0) + ' ' + f1(xb) + ',' + f1(y0 + (dir === 'up' ? 5 : -5)) + ' ' + f1(xa) + ',' + f1(y0 + (dir === 'up' ? 5 : -5)) + '" fill="currentColor"/>');
            k = j + 1;
          }
        };
        const maxL = Math.max.apply(null, g.map((e) => BEAMS[e.t]));
        for (let l = 1; l <= maxL; l++) level(l, dir === 'up' ? by + (l - 1) * 7.5 : by - (l - 1) * 7.5);
      }
      // tercinas: número sobre o grupo
      let tg = null;
      for (const e of evs) {
        if (e.tupletMark === 'start') tg = [e]; else if (tg) tg.push(e);
        if (e.tupletMark === 'stop' && tg) {
          const dir = tg[0].dir || 'up';
          const xs = tg.map((q) => q.x), cx = (Math.min.apply(null, xs) + Math.max.apply(null, xs)) / 2;
          const ys = tg.filter((q) => q.stemEnd !== undefined).map((q) => q.stemEnd);
          const y = dir === 'up' ? (ys.length ? Math.min.apply(null, ys) : yt - 10) - 6 : (ys.length ? Math.max.apply(null, ys) : yt + STAFF_H + 10) + 14;
          out.push('<text x="' + f1(cx) + '" y="' + f1(y) + '" font-size="12" font-style="italic" text-anchor="middle" fill="currentColor">' + (e.tuplet ? e.tuplet.actual : 3) + '</text>');
          tg = null;
        }
      }
      // ligaduras de valor e de expressão
      for (const e of evs) {
        if (e.rest) continue;
        for (const n of e.notes) {
          const d = degree(n.pitch || { step: 'B', octave: 4 });
          const key = st + '|' + d;
          const y = yOf(d);
          const side = e.dir === 'up' ? 1 : -1;                   // arco do lado oposto da haste
          if (n.tie.indexOf('stop') >= 0 && ties[key]) {
            const a = ties[key]; delete ties[key];
            if (a.si === si) out.push(this.arc(a.x + 5, a.y, e.x - 5, y, a.side));
            else { out.push(this.arc(Math.max(10, e.x - 40), y, e.x - 5, y, a.side)); }
          }
          if (n.tie.indexOf('start') >= 0) ties[key] = { x: e.x, y, side, si };
          for (const s of n.slur) {
            const sk = st + '|' + s.num;
            if (s.type === 'start') slurs[sk] = { x: e.x, y: side > 0 ? yOf(e.hi) : yOf(e.lo), side: e.dir === 'up' ? -1 : 1, si };
            else if (s.type === 'stop' && slurs[sk]) {
              const a = slurs[sk]; delete slurs[sk];
              const yy = a.side < 0 ? yOf(e.hi) : yOf(e.lo);
              if (a.si === si) out.push(this.arc(a.x, a.y + a.side * 8, e.x, yy + a.side * 8, a.side, 1));
              else out.push(this.arc(Math.max(10, e.x - 50), yy + a.side * 8, e.x, yy + a.side * 8, a.side, 1));
            }
          }
        }
      }
    }

    arc(x1, y1, x2, y2, side, big) {
      const mx = (x1 + x2) / 2, my = (y1 + y2) / 2 + side * (big ? 9 + Math.min(14, (x2 - x1) / 8) : 7);
      const th = 2.4;
      return '<path d="M' + f1(x1) + ',' + f1(y1 + side * 5) + ' Q' + f1(mx) + ',' + f1(my + side * 5) + ' ' + f1(x2) + ',' + f1(y2 + side * 5) + ' Q' + f1(mx) + ',' + f1(my + side * (5 - th)) + ' ' + f1(x1) + ',' + f1(y1 + side * 5) + '" fill="currentColor"/>';
    }

    // ------------------------------------------------------------ cursor
    pointAt(m, q) {
      const g = this.geo[m];
      if (!g) return { x: 0, sys: 0, top: 0, bottom: 0 };
      const S = this.systems[g.sys];
      return { x: g.ix + this.slotX(g.lay, Math.min(Math.max(q, 0), g.lay.lenQ), g.scl), sys: g.sys, top: S.top + TOP_PAD - 30, bottom: S.yb + 16, mx: g.x, mw: g.w };
    }

    setCursor(pos, follow) {
      this.pendingCursor = pos;
      if (!this.curG) return;
      if (!pos) { this.curG.style.display = 'none'; this.lastCursor = null; return; }
      let m = pos.m;
      if (m === undefined && pos.k !== undefined && this.score.order[pos.k]) m = this.score.order[pos.k].m;
      if (m === undefined || !this.geo[m]) { this.curG.style.display = 'none'; return; }
      const p = this.pointAt(m, pos.q || 0);
      this.curG.style.display = '';
      this.curLine.setAttribute('x1', f1(p.x)); this.curLine.setAttribute('x2', f1(p.x));
      this.curLine.setAttribute('y1', f1(p.top)); this.curLine.setAttribute('y2', f1(p.bottom));
      this.curHl.setAttribute('x', f1(p.mx)); this.curHl.setAttribute('width', f1(p.mw));
      this.curHl.setAttribute('y', f1(p.top)); this.curHl.setAttribute('height', f1(p.bottom - p.top));
      this.lastCursor = p;
      if (follow !== false) this.follow(p);
    }

    follow(p) {
      if (Date.now() < this.pausedUntil) return;
      const z = this.zoom, b = this.box;
      if (this.mode === 'continuous') {
        const x = p.x * z, vw = b.clientWidth;
        if (x > b.scrollLeft + vw * 0.72 || x < b.scrollLeft + 10) b.scrollLeft = Math.max(0, x - vw * 0.28);
        b.scrollTop = 0;
      } else {
        const y = p.top * z, vh = b.clientHeight;
        if (y < b.scrollTop || (p.bottom * z) > b.scrollTop + vh) b.scrollTop = Math.max(0, y - 8);
      }
    }
  }

  // o contador das casas precisa recomeçar a cada desenho
  const _render = ScoreView.prototype.render;
  ScoreView.prototype.render = function () { this.endingActive = false; return _render.apply(this, arguments); };

  root.SincronoScore = Object.assign(root.SincronoScore || {}, { ScoreView });
})(typeof window !== 'undefined' ? window : globalThis);
