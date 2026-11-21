// Painel de partitura da página (anfitrião e cliente): baixa a partitura da sessão, desenha a parte certa
// e move o cursor pelo MESMO relógio do metrônomo e do áudio. Não altera nada da sincronia.
(function () {
  'use strict';
  const $ = (id) => document.getElementById(id);
  const sec = $('score');
  if (!sec || !window.SincronoScore || !SincronoScore.ScoreView) return;
  const box = $('score-view');
  const sel = $('score-part');
  let score = null, loadedId = 0, loadingId = 0, info = { file: null, parts: {}, rev: -1 };
  let view = null, drawnGroup = '', stage = /[?&]stage=1/.test(location.search), seeking = false;
  let mode = 'continuous', zoom = 1;
  try { mode = localStorage.getItem('sincrono.scoreMode') === 'pages' ? 'pages' : 'continuous'; zoom = Number(localStorage.getItem('sincrono.scoreZoom')) || 1; } catch { /* sem armazenamento */ }
  const isGuest = () => document.body.classList.contains('guest');
  const myGroup = () => (typeof songGroup !== 'undefined' ? songGroup : 'g1');
  const hostParam = /[?&]host=1/.test(location.search);
  const canSeek = () => hostParam || (!!$('score-file') && !isGuest());
  const msg = (t) => { const e = $('score-msg'); if (e) e.textContent = t || ''; };
  const on = (id, ev, fn) => { const e = $(id); if (e) e.addEventListener(ev, fn); };
  const setHidden = (id, v) => { const e = $(id); if (e) e.hidden = v; };

  function override() {
    try { const o = JSON.parse(localStorage.getItem('sincrono.part') || 'null'); return o && info.file && o.sig === sig() ? o.idx : null; } catch { return null; }
  }
  const sig = () => (info.file ? info.file.name + ':' + info.file.size : '');
  function partIndex() {
    const n = score ? score.parts.length : 1;
    let i = override();
    if (i === null || i === undefined) i = info.parts[myGroup()] || 0;
    return Math.min(Math.max(0, i), n - 1);
  }

  function renderSelect() {
    if (!score) return;
    const ov = override();
    const groupIdx = Math.min(info.parts[myGroup()] || 0, score.parts.length - 1);
    sel.textContent = '';
    sel.add(new Option('Parte do meu grupo (' + score.parts[groupIdx].name + ')', '-1', false, ov === null || ov === undefined));
    score.parts.forEach((p, i) => sel.add(new Option(p.name, String(i), false, ov === i)));
  }
  sel.addEventListener('change', () => {
    const v = Number(sel.value);
    try { if (v < 0) localStorage.removeItem('sincrono.part'); else localStorage.setItem('sincrono.part', JSON.stringify({ sig: sig(), idx: v })); } catch { /* sem armazenamento */ }
    draw();
  });

  function draw() {
    if (!score) return;
    box.classList.toggle('pages', mode === 'pages');
    $('score-mode').textContent = mode === 'pages' ? 'Uma linha' : 'Páginas';
    if (!view) view = new SincronoScore.ScoreView(box, { mode, zoom });
    view.mode = stage ? 'pages' : mode; view.zoom = stage ? Math.max(zoom, 1.4) : zoom; view.stage = stage;
    view.onPick = (h) => { if (canSeek()) onPickMeasure(h); };
    view.setScore(score, partIndex());
    setupTimeline();
    drawnGroup = myGroup();
    last = '';
  }

  // Informa à camada do anfitrião (host.js) as partes e a escolha de cada grupo, para o cartão do grupo mostrar o seletor.
  function publish() {
    const names = score ? score.parts.map((p) => p.name) : [];
    const before = JSON.stringify(window.SincronoScoreInfo || null);
    window.SincronoScoreInfo = { fileName: info.file ? info.file.name : '', partNames: names, parts: info.parts || {} };
    if (JSON.stringify(window.SincronoScoreInfo) !== before) document.dispatchEvent(new Event('sincrono-score'));
  }
  window.sincronoScoreSetPart = (group, part) => post('/api/score/part', { group, part });

  async function post(path, body) {
    try {
      const r = await fetch(path, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body || {}) });
      if (!r.ok) msg((await r.json().catch(() => ({}))).error || 'Não foi possível.');
    } catch { msg('Sem conexão com o anfitrião.'); }
    poll(true);
  }

  async function loadFile(id) {
    if (loadingId === id) return;
    loadingId = id;
    try {
      const r = await fetch('/api/score/file?id=' + id, { cache: 'no-store' });
      if (!r.ok) throw new Error('A partitura não está mais disponível.');
      const s = await SincronoScore.load(await r.arrayBuffer());
      if (loadingId !== id) return;
      score = s; loadedId = id; msg('');
      renderSelect(); draw(); showInfo(); publish();
    } catch (e) { msg('Não consegui abrir a partitura: ' + (e && e.message ? e.message : e)); score = null; loadedId = id; }
    finally { if (loadingId === id) loadingId = 0; }
  }

  function showInfo() {
    if (!score) { $('score-info').textContent = ''; return; }
    const sb = typeof state !== 'undefined' ? state.bpm : 0;
    const base = Math.round(score.initialBpm);
    $('score-info').textContent = (score.title ? score.title + ' · ' : '') + 'andamento da partitura: ' + base + ' bpm' +
      (sb && Math.round(sb) !== base ? ' · metrônomo em ' + Math.round(sb) + ' (o cursor acompanha o metrônomo)' : '');
  }

  let polling = false;
  async function poll(force) {
    if (polling && !force) return;
    polling = true;
    try {
      const r = await fetch('/api/score', { cache: 'no-store' });
      if (!r.ok) { sec.hidden = true; return; }
      const next = await r.json();
      const changedPart = JSON.stringify(next.parts) !== JSON.stringify(info.parts);
      info = next;
      document.body.classList.toggle('has-score', !!next.file);
      sec.hidden = !next.file;
      const nm = $('score-name'); if (nm) nm.textContent = next.file ? 'Partitura: ' + next.file.name : 'Partitura: nenhuma';
      setHidden('score-tempo', !next.file); setHidden('score-remove', !next.file);
      if (!next.file) { score = null; loadedId = 0; box.textContent = ''; view = null; $('score-info').textContent = ''; sel.textContent = ''; publish(); return; }
      if (next.file.id !== loadedId) await loadFile(next.file.id);
      else if (changedPart) { renderSelect(); draw(); }
      publish(); showInfo(); setupTimeline();
    } catch { /* servidor sem partitura: painel fica escondido */ }
    finally { polling = false; }
  }

  // ---------- cursor: áudio tocando > metrônomo > áudio pausado > início
  let last = '';
  function scoreMs() {
    const a = typeof songState !== 'undefined' ? songState : null;
    const am = audioMarks();
    const fromA = (p) => (am ? score.fromAudio(am.marks, am.offset, p) : p);
    if (a && a.file && a.playing) return fromA(songPositionMs());
    if (typeof state !== 'undefined' && state.playing && state.startAt) {
      const scale = state.bpm / (score.initialBpm || state.bpm);
      return Math.max(0, (serverNow() - state.startAt) * scale);
    }
    if (typeof info.pos === 'number') return info.pos;           // posição escolhida pelo anfitrião na linha do tempo
    if (a && a.file) { const p = songPositionMs(); if (p) return fromA(p); }
    return 0;
  }
  /** Marcadores de compasso do áudio atual (feitos pelo anfitrião), ou null. */
  function audioMarks() {
    const a = typeof songState !== 'undefined' ? songState : null;
    const m = a && a.file && info.marks ? info.marks[a.file.name] : null;
    return m && m.marks && m.marks.length ? m : null;
  }

  // ---------- linha do tempo do anfitrião: clique num compasso ou arraste o controle; todos veem (cursor e música)
  const realNow = () => (typeof sincronoServerNow === 'function' ? sincronoServerNow() : serverNow());
  async function postQuiet(path, body) {
    try { await fetch(path, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body || {}) }); } catch { /* sem conexão */ }
  }
  /** Move a música e o metrônomo para [ms] (da partitura). Áudio e metrônomo, se tocando, retomam juntos no mesmo instante. */
  async function seekTo(ms) {
    if (!score || !canSeek()) return;
    const a = typeof songState !== 'undefined' ? songState : null;
    const hasAudio = !!(a && a.file), audioPlaying = hasAudio && a.playing;
    const metroPlaying = typeof state !== 'undefined' && !!state.playing;
    const S = realNow() + 2500;
    const scale = state.bpm / (score.initialBpm || state.bpm);
    const jobs = [];
    const am = audioMarks();
    if (hasAudio) jobs.push(postQuiet('/api/audio/cmd?group=all', { type: 'seek', positionMs: Math.max(0, am ? score.toAudio(am.marks, am.offset, ms) : ms), startAt: S }));
    if (metroPlaying) jobs.push(postQuiet('/api/command', { type: 'play', bpm: state.bpm, beatsPerBar: state.beatsPerBar, startAt: Math.round(S - ms / scale) }));
    await Promise.all(jobs);
    if (!audioPlaying && !metroPlaying) await postQuiet('/api/score/pos', { ms });
    poll(true);
  }
  function onPickMeasure(h) {
    // repetições: entre as vezes em que o compasso é tocado, escolhe a mais próxima da posição atual
    const cur = score.locate(scoreMs());
    let best = -1;
    for (const o of score.order) if (o.m === h.m && (best < 0 || Math.abs(o.k - (cur.k || 0)) < Math.abs(best - (cur.k || 0)))) best = o.k;
    if (best < 0) return;
    seekTo(score.msOf(best, 0));
  }
  function setupTimeline() {
    const t = $('score-timeline'), r = $('score-seek');
    if (!t || !r || !score) return;
    t.hidden = !canSeek();
    r.max = String(Math.max(1, Math.round(score.totalMs)));
  }
  (function wireTimeline() {
    const r = $('score-seek'); if (!r) return;
    const label = (ms) => { const l = score.locate(ms); const n = l.m !== undefined ? (score.measures[l.m].number || (l.m + 1)) : 1; $('score-seek-label').textContent = 'compasso ' + n; return l; };
    r.addEventListener('pointerdown', () => { seeking = true; });
    r.addEventListener('input', () => { if (!score) return; const l = label(Number(r.value)); if (view && l.k !== undefined) view.setCursor({ k: l.k, q: 0 }, true); });
    r.addEventListener('change', () => {
      seeking = false;
      if (!score) return;
      const l = score.locate(Number(r.value));
      if (l.k !== undefined) seekTo(score.msOf(l.k, 0));
    });
    window.__scoreSeekLabel = label;
  })();

  // ---------- marcadores de compasso (anfitrião): toca o áudio e toca em COMPASSO no início de cada compasso
  let marking = null;   // { marks: [ms do áudio], sig }
  const audioName = () => { const a = typeof songState !== 'undefined' ? songState : null; return a && a.file ? a.file.name : ''; };
  function marksUi() {
    const box = $('score-marks'); if (!box) return;
    box.hidden = !score || !canSeek() || !audioName() || !!marking;
    const am = audioMarks(), n = score ? score.order.length : 0;
    setHidden('marks-clear', !am);
    $('marks-status').textContent = am ? 'Marcadores: ' + am.marks.length + ' de ' + n + ' compassos' : (audioName() ? 'Sem marcadores: o cursor segue o andamento escrito.' : '');
    const bar = $('marks-bar'); if (bar) bar.hidden = !marking;
    if (marking) $('marks-tap').textContent = 'COMPASSO ' + (marking.marks.length + 1) + ' de ' + n;
  }
  setInterval(marksUi, 500);
  on('marks-start', 'click', () => {
    const a = typeof songState !== 'undefined' ? songState : null;
    if (!a || !a.file) { msg('Carregue o áudio na aba Música.'); return; }
    marking = { marks: [], sig: a.file.name }; marksUi();
    msg('Toque o áudio desde o início e toque em COMPASSO no início de cada compasso.');
  });
  function tap() {
    if (!marking || !score) return;
    const a = typeof songState !== 'undefined' ? songState : null;
    if (!a || !a.playing) { msg('O áudio precisa estar tocando para marcar.'); return; }
    if (marking.marks.length >= score.order.length) return;
    marking.marks.push(songPositionMs());
    if (view) view.setCursor({ k: marking.marks.length - 1, q: 0 }, true);
    marksUi();
  }
  on('marks-tap', 'click', tap);
  document.addEventListener('keydown', (e) => { if (marking && (e.key === ' ' || e.key === 'Enter') && !/^(INPUT|BUTTON|TEXTAREA)$/.test(e.target.tagName)) { e.preventDefault(); tap(); } });
  on('marks-undo', 'click', () => { if (marking) { marking.marks.pop(); marksUi(); } });
  on('marks-cancel', 'click', () => { marking = null; msg(''); marksUi(); });
  on('marks-save', 'click', async () => {
    if (!marking) return;
    if (!marking.marks.length) { msg('Nenhum compasso marcado.'); return; }
    const d = Math.max(0, Math.min(500, Number($('marks-delay').value) || 0));
    const body = { sig: marking.sig, marks: marking.marks.map((x) => Math.round(x)), offset: -d };
    marking = null;
    await post('/api/score/marks', body);
    msg('Marcadores salvos: o cursor agora segue o áudio compasso a compasso.');
    marksUi();
  });
  on('marks-clear', 'click', async () => { if (confirm('Apagar os marcadores deste áudio?')) { await post('/api/score/marks', { sig: audioName(), marks: null }); marksUi(); } });

  // ---------- modo palco: só a partitura, tela cheia, tela acesa
  let wake = null;
  async function setStage(on) {
    stage = on;
    sec.classList.toggle('stage', on);
    document.body.classList.toggle('stage-on', on);
    const b = $('score-stage'); if (b) b.textContent = on ? 'Sair do palco' : 'Palco';
    try {
      if (on) { if (document.documentElement.requestFullscreen && !document.fullscreenElement) await document.documentElement.requestFullscreen(); }
      else if (document.fullscreenElement) await document.exitFullscreen();
    } catch { /* o navegador pode recusar; o modo palco funciona mesmo assim */ }
    try {
      if (on && navigator.wakeLock) wake = await navigator.wakeLock.request('screen');
      else if (wake) { await wake.release(); wake = null; }
    } catch { /* tela acesa é opcional */ }
    draw();
  }
  { const x = document.createElement('button'); x.id = 'score-stage-exit'; x.type = 'button'; x.textContent = 'Sair do palco'; sec.appendChild(x); x.addEventListener('click', () => setStage(false)); }
  on('score-stage', 'click', () => setStage(!stage));
  document.addEventListener('fullscreenchange', () => { if (!document.fullscreenElement && stage) setStage(false); });
  document.addEventListener('keydown', (e) => { if (e.key === 'Escape' && stage) setStage(false); });
  setInterval(() => {
    if (!score || !view || sec.hidden) return;
    if (myGroup() !== drawnGroup) { renderSelect(); draw(); }   // o anfitrião moveu este aparelho de grupo
    const ms = scoreMs();
    const loc = score.locate(ms);
    if (!seeking) { const r = $('score-seek'); if (r && !r.hidden !== false && !(r.parentElement && r.parentElement.hidden)) { r.value = String(Math.round(ms)); if (window.__scoreSeekLabel) window.__scoreSeekLabel(ms); } }
    if (loc.k === undefined) return;
    const key = loc.k + ':' + loc.q.toFixed(2);
    if (key === last) return;
    last = key;
    view.setCursor({ k: loc.k, q: loc.q }, true);
  }, 40);

  // ---------- botões
  on('score-mode', 'click', () => { mode = mode === 'pages' ? 'continuous' : 'pages'; try { localStorage.setItem('sincrono.scoreMode', mode); } catch { /* */ } draw(); });
  const setZoom = (z) => { zoom = Math.min(2.5, Math.max(0.6, z)); try { localStorage.setItem('sincrono.scoreZoom', String(zoom)); } catch { /* */ } draw(); };
  on('score-zoom-in', 'click', () => setZoom(zoom + 0.15));
  on('score-zoom-out', 'click', () => setZoom(zoom - 0.15));
  let rt = null;
  window.addEventListener('resize', () => { clearTimeout(rt); rt = setTimeout(() => { if (mode === 'pages') draw(); }, 200); });
  on('score-file', 'change', async (ev) => {
    const f = ev.target.files[0]; if (!f) return;
    msg('Enviando ' + f.name + '…');
    try {
      const r = await fetch('/api/score/upload?name=' + encodeURIComponent(f.name), { method: 'POST', body: f });
      msg(r.ok ? 'Partitura enviada.' : ((await r.json().catch(() => ({}))).error || 'Falha no envio.'));
    } catch { msg('Sem conexão com o anfitrião.'); }
    ev.target.value = '';
    poll(true);
  });
  on('score-remove', 'click', () => { if (info.file && confirm('Remover a partitura da sessão?')) post('/api/score/clear', {}); });
  on('score-tempo', 'click', async () => {
    if (!score) return;
    const t = score.initialTime || { b: 4 };
    await post('/api/command', { type: 'settings', bpm: Math.round(score.initialBpm), beatsPerBar: Math.max(1, Math.min(12, Math.round(t.b))) });
  });

  poll();
  setInterval(poll, 1000);   // a escolha do anfitrião aparece para os clientes em ~1 s
})();
