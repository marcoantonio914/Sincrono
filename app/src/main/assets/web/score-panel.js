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
  let view = null, drawnGroup = '';
  let mode = 'continuous', zoom = 1;
  try { mode = localStorage.getItem('sincrono.scoreMode') === 'pages' ? 'pages' : 'continuous'; zoom = Number(localStorage.getItem('sincrono.scoreZoom')) || 1; } catch { /* sem armazenamento */ }
  const isGuest = () => document.body.classList.contains('guest');
  const myGroup = () => (typeof songGroup !== 'undefined' ? songGroup : 'g1');
  const msg = (t) => { $('score-msg').textContent = t || ''; };

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
    view.mode = mode; view.zoom = zoom;
    view.setScore(score, partIndex());
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
      sec.hidden = !next.file && isGuest();
      if (!next.file) { score = null; loadedId = 0; box.textContent = ''; view = null; $('score-info').textContent = ''; sel.textContent = ''; publish(); return; }
      if (next.file.id !== loadedId) await loadFile(next.file.id);
      else if (changedPart) { renderSelect(); draw(); }
      publish(); showInfo();
    } catch { /* servidor sem partitura: painel fica escondido */ }
    finally { polling = false; }
  }

  // ---------- cursor: áudio tocando > metrônomo > áudio pausado > início
  let last = '';
  function scoreMs() {
    const a = typeof songState !== 'undefined' ? songState : null;
    if (a && a.file && a.playing) return songPositionMs();
    if (typeof state !== 'undefined' && state.playing && state.startAt) {
      const scale = state.bpm / (score.initialBpm || state.bpm);
      return Math.max(0, (serverNow() - state.startAt) * scale);
    }
    if (a && a.file) { const p = songPositionMs(); if (p) return p; }
    return 0;
  }
  setInterval(() => {
    if (!score || !view || sec.hidden) return;
    if (myGroup() !== drawnGroup) { renderSelect(); draw(); }   // o anfitrião moveu este aparelho de grupo
    const loc = score.locate(scoreMs());
    if (loc.k === undefined) return;
    const key = loc.k + ':' + loc.q.toFixed(2);
    if (key === last) return;
    last = key;
    view.setCursor({ k: loc.k, q: loc.q }, true);
  }, 40);

  // ---------- botões
  $('score-mode').addEventListener('click', () => { mode = mode === 'pages' ? 'continuous' : 'pages'; try { localStorage.setItem('sincrono.scoreMode', mode); } catch { /* */ } draw(); });
  const setZoom = (z) => { zoom = Math.min(2.5, Math.max(0.6, z)); try { localStorage.setItem('sincrono.scoreZoom', String(zoom)); } catch { /* */ } draw(); };
  $('score-zoom-in').addEventListener('click', () => setZoom(zoom + 0.15));
  $('score-zoom-out').addEventListener('click', () => setZoom(zoom - 0.15));
  let rt = null;
  window.addEventListener('resize', () => { clearTimeout(rt); rt = setTimeout(() => { if (mode === 'pages') draw(); }, 200); });
  $('score-file').addEventListener('change', async (ev) => {
    const f = ev.target.files[0]; if (!f) return;
    msg('Enviando ' + f.name + '…');
    try {
      const r = await fetch('/api/score/upload?name=' + encodeURIComponent(f.name), { method: 'POST', body: f });
      msg(r.ok ? 'Partitura enviada.' : ((await r.json().catch(() => ({}))).error || 'Falha no envio.'));
    } catch { msg('Sem conexão com o anfitrião.'); }
    ev.target.value = '';
    poll(true);
  });
  $('score-remove').addEventListener('click', () => { if (info.file && confirm('Remover a partitura da sessão?')) post('/api/score/clear', {}); });
  $('score-tempo').addEventListener('click', async () => {
    if (!score) return;
    const t = score.initialTime || { b: 4 };
    await post('/api/command', { type: 'settings', bpm: Math.round(score.initialBpm), beatsPerBar: Math.max(1, Math.min(12, Math.round(t.b))) });
  });

  poll();
  setInterval(poll, 1000);   // a escolha do anfitrião aparece para os clientes em ~1 s
})();
