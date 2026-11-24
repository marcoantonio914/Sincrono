// "Ouvir também": além do áudio do próprio grupo, esta página pode tocar junto o áudio de outros grupos
// (por exemplo o piano de acompanhamento + a sua voz), cada um com o seu volume. Cada grupo segue o seu próprio
// estado no anfitrião (mesmo instante de início do Tocar), então tudo continua sincronizado.
// Usa o relógio, o contexto de áudio e o volume geral de app.js; a escolha fica guardada neste navegador.
(function () {
  const KEY = 'sincrono.extra.' + location.host;
  let sel = {};                       // grupo -> volume (0..1) dos marcados
  try { sel = JSON.parse(localStorage.getItem(KEY) || '{}') || {}; } catch { sel = {}; }
  const save = () => { try { localStorage.setItem(KEY, JSON.stringify(sel)); } catch { /* sem armazenamento */ } };
  const players = {};                 // grupo -> { state, buffer, bufferId, loadingId, node, rev, vol }
  let groups = [];
  let lastSig = '';

  const $ = (id) => document.getElementById(id);
  const own = () => (typeof songGroup === 'string' ? songGroup : 'g1');
  const wanted = (g) => g.id !== own() && g.file && Object.prototype.hasOwnProperty.call(sel, g.id);

  // ---- um tocador por grupo extra (mesma lógica do tocador principal em app.js)
  function newPlayer(gid) {
    const p = { gid, state: null, buffer: null, bufferId: 0, loadingId: 0, node: null, rev: -1, vol: null, polling: false };
    p.vol = getAudio().createGain();
    p.vol.gain.value = typeof sel[gid] === 'number' ? sel[gid] : 1;
    p.vol.connect(getMaster());
    return p;
  }
  function stopNode(p) {
    if (!p.node) return;
    const n = p.node; p.node = null;
    try { n.src.stop(); } catch { /* já parou */ }
    try { n.src.disconnect(); n.gain.disconnect(); } catch { /* ok */ }
  }
  function destroy(gid) {
    const p = players[gid]; if (!p) return;
    stopNode(p);
    try { p.vol.disconnect(); } catch { /* ok */ }
    delete players[gid];
  }
  function fadeOut(p, stopAtServerMs) {
    if (!p.node || p.node.fadeScheduled) return;
    const stopCtx = contextTimeForHeardAt(stopAtServerMs);
    const g = p.node.gain.gain;
    g.setValueAtTime(1, Math.max(audioContext.currentTime, stopCtx - SONG_FADE_OUT_S));
    g.linearRampToValueAtTime(0, Math.max(audioContext.currentTime + 0.001, stopCtx));
    try { p.node.src.stop(stopCtx + 0.02); } catch { /* ok */ }
    p.node.fadeScheduled = true;
  }
  function begin(p, ctxStart, mediaSeconds, s) {
    stopNode(p);
    const src = audioContext.createBufferSource();
    src.buffer = p.buffer;
    const gain = audioContext.createGain();
    gain.gain.setValueAtTime(0, ctxStart);
    gain.gain.linearRampToValueAtTime(1, ctxStart + SONG_FADE_IN_S);
    src.connect(gain).connect(p.vol);
    src.start(ctxStart, Math.max(0, mediaSeconds));
    p.node = { src, gain, startAt: s.startAt, positionMs: s.positionMs, segP: mediaSeconds, segT: ctxStart, segRate: 1, fadeScheduled: false };
    if (s.stopAt) fadeOut(p, s.stopAt);
  }
  function control(p) {
    const s = p.state;
    if (!p.buffer || !s || !s.file || s.file.id !== p.bufferId || !audioContext || audioContext.state !== 'running') { stopNode(p); return; }
    if (s.revision !== p.rev) {
      p.rev = s.revision;
      const same = p.node && s.playing && p.node.startAt === s.startAt && p.node.positionMs === s.positionMs;
      if (same) { if (s.stopAt) fadeOut(p, s.stopAt); } else stopNode(p);
    }
    if (!s.playing) { stopNode(p); return; }
    const t = audioContext.currentTime + 0.05;
    const rel = (serverHeardAtContextTime(t) - s.startAt) / 1000;
    const posS = s.positionMs / 1000;
    const stopMedia = s.stopAt ? (s.positionMs + (s.stopAt - s.startAt)) / 1000 : Infinity;
    if (posS + rel >= stopMedia + 0.3 || posS + rel >= p.buffer.duration) { stopNode(p); return; }
    if (!p.node) {
      if (rel < 0) { if (-rel > 1.0) return; begin(p, t - rel, posS, s); } else begin(p, t, posS + rel, s);
      return;
    }
    if (t < p.node.segT || rel < 0) return;
    const actual = p.node.segP + (t - p.node.segT) * p.node.segRate;
    const error = (posS + rel) - actual;
    if (Math.abs(error) > SONG_JUMP_S) { begin(p, t, posS + rel, s); return; }
    const rate = Math.abs(error) < 0.0001 ? 1 : 1 + Math.max(-SONG_MAX_RATE, Math.min(SONG_MAX_RATE, error / 0.5));
    p.node.src.playbackRate.setValueAtTime(rate, t);
    p.node.segP = actual; p.node.segT = t; p.node.segRate = rate;
  }
  async function load(p, id) {
    p.loadingId = id;
    try {
      const r = await fetch(`/api/audio/file?id=${id}&group=${encodeURIComponent(p.gid)}`);
      if (!r.ok) throw new Error('download');
      const data = await r.arrayBuffer();
      if (p.loadingId !== id || players[p.gid] !== p) return;
      const decoded = await getAudio().decodeAudioData(data);
      if (p.loadingId !== id || players[p.gid] !== p) return;
      p.buffer = decoded; p.bufferId = id;
    } catch { /* tenta de novo quando o estado voltar a mudar */ if (p.loadingId === id) p.loadingId = 0; }
  }
  function apply(p, next) {
    if (p.state && next.revision < p.state.revision) return;
    p.state = next;
    const id = next.file ? next.file.id : 0;
    if (id !== p.loadingId) {
      p.loadingId = id; p.buffer = null; p.bufferId = 0; stopNode(p);
      if (id) load(p, id);
    }
  }
  async function poll(p) {
    if (p.polling) return;
    p.polling = true;
    try {
      const r = await fetch(`/api/audio?group=${encodeURIComponent(p.gid)}`, { cache: 'no-store' });   // sem "device": não conta como aparelho do grupo
      if (r.ok && players[p.gid] === p) apply(p, await r.json());
    } catch { /* próximo ciclo */ } finally { p.polling = false; }
  }

  // ---- lista na página
  function render() {
    const box = $('extra-list'), sec = $('extra-audio');
    if (!box || !sec) return;
    const others = groups.filter((g) => g.id !== own() && g.file);
    sec.hidden = others.length === 0;
    box.textContent = '';
    for (const g of others) {
      const on = Object.prototype.hasOwnProperty.call(sel, g.id);
      const row = document.createElement('div'); row.className = 'extra-row';
      const lab = document.createElement('label');
      const cb = document.createElement('input'); cb.type = 'checkbox'; cb.checked = on;
      const dot = document.createElement('span'); dot.className = 'extra-dot';
      dot.style.background = typeof sincronoGroupColor === 'function' ? sincronoGroupColor(g.id) : '#475569';
      lab.append(cb, dot, document.createTextNode(' ' + g.name));
      row.append(lab);
      cb.addEventListener('change', () => {
        if (cb.checked) { sel[g.id] = typeof sel[g.id] === 'number' ? sel[g.id] : 1; getAudio().resume(); } else delete sel[g.id];
        save(); lastSig = ''; reconcile(); render();
      });
      if (on) {
        const sl = document.createElement('input'); sl.type = 'range'; sl.min = '0'; sl.max = '100'; sl.value = String(Math.round((sel[g.id] ?? 1) * 100));
        sl.setAttribute('aria-label', 'Volume de ' + g.name);
        sl.addEventListener('input', () => {
          sel[g.id] = Number(sl.value) / 100; save();
          const p = players[g.id]; if (p) p.vol.gain.setTargetAtTime(sel[g.id], audioContext.currentTime, 0.03);
        });
        row.append(sl);
      }
      box.append(row);
    }
  }
  function reconcile() {
    const want = new Set(groups.filter(wanted).map((g) => g.id));
    for (const gid of Object.keys(players)) if (!want.has(gid)) destroy(gid);
    for (const gid of want) if (!players[gid]) players[gid] = newPlayer(gid);
  }
  async function refreshGroups() {
    try {
      const r = await fetch('/api/groups', { cache: 'no-store' });
      if (!r.ok) return;
      groups = (await r.json()).groups || [];
      const sig = own() + '|' + groups.map((g) => g.id + ':' + g.name + ':' + (g.file ? 1 : 0)).join(',');
      reconcile();
      if (sig !== lastSig) { lastSig = sig; render(); }
    } catch { /* servidor sem grupos */ }
  }

  setInterval(refreshGroups, 2000); refreshGroups();
  setInterval(() => { for (const gid of Object.keys(players)) poll(players[gid]); }, 700);
  setInterval(() => { for (const gid of Object.keys(players)) control(players[gid]); }, 100);
  // para testes
  window.sincronoExtraAudio = { players, select: (gid, v) => { sel[gid] = v === undefined ? 1 : v; save(); lastSig = ''; reconcile(); render(); } };
})();
