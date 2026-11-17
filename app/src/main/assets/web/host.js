// Camada "anfitrião" da página: abas, aviso de som, grupos, mixer por aparelho e QR code.
// Funciona com o servidor do celular e com o do computador (algumas coisas dependem de recursos do servidor).
(function () {
  'use strict';
  const $ = (id) => document.getElementById(id);
  const busy = new Set();            // controles sendo arrastados: não redesenhar
  let caps = { upload: false, urls: [], host: '', local: false, calibration: false };
  let groupsInfo = { groups: [], assign: {} };
  let mix = {};
  let devices = [];

  // ---------- abas ----------
  const tabs = ['sync', 'audio', 'mixer', 'net'];
  function showTab(name) {
    if (!tabs.includes(name)) name = 'sync';
    document.querySelectorAll('.tab').forEach((el) => { el.hidden = el.dataset.tab !== name; });
    document.querySelectorAll('#tabs button').forEach((b) => b.classList.toggle('on', b.dataset.go === name));
    try { localStorage.setItem('sincrono.tab', name); } catch { /* sem armazenamento */ }
    if (name === 'net') drawInvite();
    refresh();
  }
  document.querySelectorAll('#tabs button').forEach((b) => b.addEventListener('click', () => showTab(b.dataset.go)));
  // A música (seção #song) e o mixer ficam nas abas certas.
  const audioTab = document.querySelector('.tab[data-tab="audio"]');
  const song = $('song');
  if (song && audioTab) audioTab.prepend(song);

  // ---------- som ----------
  function soundRunning() { return typeof audioContext !== 'undefined' && audioContext && audioContext.state === 'running'; }
  async function enableSound() {
    try { await getAudio().resume(); } catch { /* navegador recusou; o aviso continua */ }
    updateBanner();
  }
  function updateBanner() { const b = $('sound-banner'); if (b) b.hidden = soundRunning(); }
  $('sound-enable').addEventListener('click', enableSound);
  // Qualquer toque, clique ou tecla já conta como o "gesto" que o navegador exige.
  ['pointerdown', 'keydown', 'touchstart'].forEach((ev) => document.addEventListener(ev, () => { if (!soundRunning()) enableSound(); }, { passive: true }));
  setInterval(updateBanner, 500);
  updateBanner();

  // ---------- API ----------
  async function api(path, body, quiet) {
    const r = await fetch(path, body === undefined ? { cache: 'no-store' } : { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
    const data = await r.json().catch(() => ({}));
    if (!r.ok) { if (!quiet) hint(data.error || `Erro ${r.status}`); throw new Error(data.error || r.status); }
    return data;
  }
  function hint(text) { const h = $('groups-hint'); if (h) h.textContent = text; }

  async function loadCaps() {
    try { caps = Object.assign({ upload: false, urls: [], host: '', local: false, calibration: false }, await api('/api/info', undefined, true)); } catch { /* servidor do celular: sem /api/info */ }
  }

  function slider(key, value, onDone) {
    const el = document.createElement('input');
    el.type = 'range'; el.min = 0; el.max = 100; el.value = Math.round(value * 100);
    el.addEventListener('input', () => busy.add(key));
    el.addEventListener('change', () => { Promise.resolve(onDone(el.value / 100)).finally(() => busy.delete(key)); });
    return el;
  }
  function button(text, onClick, cls, disabled) {
    const b = document.createElement('button');
    b.type = 'button'; b.textContent = text; if (cls) b.className = cls; if (disabled) b.disabled = true;
    b.addEventListener('click', onClick);
    return b;
  }
  function row() { const d = document.createElement('div'); d.className = 'row'; return d; }

  // ---------- grupos ----------
  function renderGroups() {
    const box = $('groups-list');
    if (!box || busy.size) return;
    box.textContent = '';
    for (const g of groupsInfo.groups) {
      const d = document.createElement('div'); d.className = 'group';
      const head = row();
      const dot = document.createElement('span'); dot.className = 'dot' + (g.playing ? ' on' : '');
      const title = document.createElement('strong'); title.className = 'grow'; title.textContent = g.name;
      const file = document.createElement('span'); file.className = 'muted'; file.textContent = g.file || 'sem arquivo';
      head.append(dot, title, file); d.appendChild(head);
      const r1 = row();
      if (caps.upload) {
        const up = document.createElement('input'); up.type = 'file'; up.accept = 'audio/*'; up.className = 'grow';
        up.addEventListener('change', async () => {
          const f = up.files[0]; if (!f) return;
          hint(`Enviando ${f.name}…`);
          const res = await fetch(`/api/audio/upload?group=${encodeURIComponent(g.id)}&name=${encodeURIComponent(f.name)}`, { method: 'POST', headers: { 'X-File-Type': f.type || 'application/octet-stream' }, body: f });
          hint(res.ok ? 'Arquivo enviado.' : ((await res.json().catch(() => ({}))).error || 'Falha no envio.'));
          refresh();
        });
        r1.appendChild(up);
      } else {
        const note = document.createElement('span'); note.className = 'muted grow'; note.textContent = 'O arquivo deste grupo é escolhido no app do celular anfitrião.';
        r1.appendChild(note);
      }
      r1.append(
        button('Tocar', () => api(`/api/audio/cmd?group=${g.id}`, { type: 'play' }).then(refresh), '', !g.file || g.playing),
        button('Pausar', () => api(`/api/audio/cmd?group=${g.id}`, { type: 'pause' }).then(refresh), '', !g.playing),
      );
      d.appendChild(r1);
      const vol = row();
      const lab = document.createElement('span'); lab.className = 'muted'; lab.textContent = 'Volume do grupo';
      const sl = slider('g:' + g.id, mix['g:' + g.id] ?? 1, (v) => api('/api/mix', { device: 'g:' + g.id, level: v })); sl.className = 'grow';
      vol.append(lab, sl); d.appendChild(vol);
      if (g.id !== 'g1') {
        const r2 = row();
        r2.append(
          button('Renomear', () => { const n = prompt('Novo nome do grupo', g.name); if (n) api('/api/groups', { op: 'rename', id: g.id, name: n }).then(refresh); }),
          button('Remover grupo', () => { if (confirm(`Remover o grupo "${g.name}"? Os aparelhos voltam ao grupo Geral.`)) api('/api/groups', { op: 'remove', id: g.id }).then(refresh); }, 'danger'),
        );
        d.appendChild(r2);
      }
      const members = devices.filter((x) => x.group === g.id);
      const m = document.createElement('p'); m.className = 'muted';
      m.textContent = members.length ? 'Aparelhos: ' + members.map((x) => x.name).join(', ') : 'Nenhum aparelho neste grupo.';
      d.appendChild(m);
      box.appendChild(d);
    }
  }

  // ---------- aparelhos / mixer ----------
  function renderDevices() {
    const box = $('devices-list');
    if (!box || busy.size) return;
    box.textContent = '';
    if (!devices.length) {
      const p = document.createElement('p'); p.className = 'muted'; p.textContent = 'Nenhum aparelho apareceu ainda.'; box.appendChild(p); return;
    }
    for (const dev of devices) {
      const d = document.createElement('div'); d.className = 'group';
      const head = row();
      const name = document.createElement('strong'); name.className = 'grow'; name.textContent = dev.name + (dev.id === (typeof deviceId !== 'undefined' ? deviceId : '') ? ' (esta página)' : '');
      const sel = document.createElement('select'); sel.setAttribute('aria-label', 'Grupo de ' + dev.name);
      for (const g of groupsInfo.groups) sel.add(new Option(g.name, g.id, false, g.id === dev.group));
      sel.addEventListener('change', () => api('/api/groups', { op: 'assign', device: dev.id, group: sel.value }).then(refresh));
      head.append(name, sel); d.appendChild(head);
      d.appendChild(slider('d:' + dev.id, dev.level, (v) => api('/api/mix', { device: dev.id, level: v })));
      box.appendChild(d);
    }
  }

  async function loadDevices() {
    let list = [];
    try {
      list = await api('/api/devices', undefined, true);
    } catch {
      try { list = (await api('/api/calib', undefined, true)).peers || []; } catch { list = []; }
    }
    devices = list.map((x) => ({
      id: x.id, name: x.name || String(x.id).slice(0, 8),
      group: (groupsInfo.assign || {})[x.id] || 'g1',
      level: typeof mix[x.id] === 'number' ? mix[x.id] : 1,
    }));
  }

  // ---------- convite (QR code) ----------
  function lanUrl(urls) {
    const priv = (u) => /^http:\/\/(10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.)/.test(u);
    return urls.find(priv) || urls[0] || location.origin;
  }
  function drawInvite() {
    const urls = caps.urls && caps.urls.length ? caps.urls : [location.origin];
    $('invite-urls').textContent = urls.join('\n');
    $('invite-urls').style.whiteSpace = 'pre-line';
    try { qrToCanvas($('qr'), lanUrl(urls), 6); } catch (e) { $('invite-urls').textContent += '\n(QR indisponível: ' + e.message + ')'; }
  }

  // ---------- atualização ----------
  let refreshing = false;
  async function refresh() {
    if (refreshing) return;
    refreshing = true;
    try {
      mix = (await api('/api/mix', undefined, true)).levels || {};
      groupsInfo = await api('/api/groups', undefined, true);
      await loadDevices();
      const panel = $('groups-panel');
      if (panel) panel.hidden = false;
      if (!groupsInfo.groups.some((g) => g.file) && !busy.size) hint('Cada grupo toca o seu arquivo; todos seguem o mesmo relógio.');
      renderGroups(); renderDevices();
    } catch {
      const panel = $('groups-panel');
      if (panel) panel.hidden = true; // servidor sem grupos
    } finally { refreshing = false; }
  }

  $('play-all').addEventListener('click', () => api('/api/audio/cmd?group=all', { type: 'play' }).then(refresh));
  $('pause-all').addEventListener('click', () => api('/api/audio/cmd?group=all', { type: 'pause' }).then(refresh));
  $('add-group').addEventListener('click', () => {
    const name = $('new-group').value.trim();
    if (name) api('/api/groups', { op: 'add', name }).then(() => { $('new-group').value = ''; refresh(); });
  });


  // ---------- tela inicial: ser anfitrião ou entrar (só no computador que roda o servidor) ----------
  function getMode() { try { return sessionStorage.getItem('sincrono.mode'); } catch { return null; } }
  function setMode(v) { try { if (v) sessionStorage.setItem('sincrono.mode', v); else sessionStorage.removeItem('sincrono.mode'); } catch { /* sem armazenamento */ } }
  function joinAt(url) {
    setMode(null); // ao voltar, pergunta de novo
    location.href = url.replace(/\/+$/, '') + '/?voltar=' + encodeURIComponent(location.origin);
  }
  function setupStart() {
    const screen = $('start-screen');
    if (!screen) return;
    if (!(caps.local && caps.host === 'pc') || getMode() === 'host') { screen.hidden = true; return; }
    screen.hidden = false;
    $('start-host').onclick = () => { setMode('host'); screen.hidden = true; };
    $('start-join').onclick = async () => {
      $('join-box').hidden = false;
      $('join-found').textContent = 'Procurando sessões na rede…';
      try {
        const r = await api('/api/discover', undefined, true);
        const list = $('join-list'); list.textContent = '';
        for (const found of r.sessions) list.appendChild(button(found.name, () => joinAt(found.url), 'big'));
        $('join-found').textContent = r.sessions.length ? 'Sessões encontradas:'
          : (r.available ? 'Nenhuma sessão encontrada. Digite o endereço abaixo.' : 'Digite o endereço da sessão (ele aparece no app do anfitrião, na aba REDE).');
      } catch { $('join-found').textContent = 'Digite o endereço da sessão.'; }
    };
    $('join-go').onclick = () => {
      let a = $('join-addr').value.trim();
      if (!a) { $('join-error').textContent = 'Digite o endereço.'; return; }
      if (!/^https?:\/\//i.test(a)) a = 'http://' + a;
      joinAt(a);
    };
  }
  // Página aberta a partir de outro computador que escolheu "Entrar em uma sessão": botão para voltar.
  try {
    const back = new URLSearchParams(location.search).get('voltar');
    if (back && /^https?:\/\//i.test(back)) sessionStorage.setItem('sincrono.voltar', back);
  } catch { /* sem armazenamento */ }
  function setupLeave() {
    let back = null;
    try { back = sessionStorage.getItem('sincrono.voltar'); } catch { /* sem armazenamento */ }
    const b = $('leave-session');
    if (b && back) { b.hidden = false; b.onclick = () => { try { sessionStorage.removeItem('sincrono.voltar'); } catch { /* */ } location.href = back; }; }
  }

  // ---------- calibrar todos (um botão) ----------
  async function updateHostCalib() {
    const panel = $('host-calib');
    if (!panel) return;
    const supported = typeof calibSupported !== 'undefined' && calibSupported;
    panel.hidden = !supported;
    if (!supported) return;
    const pc = caps.host === 'pc';
    $('host-calib-hint').textContent = pc
      ? 'Ligue a saída de fone de cada aparelho (e a deste computador) à mesa de som, e a mesa ao computador por USB. Com o app aberto e o som ativo em todos, é só clicar.'
      : 'Coloque os aparelhos perto uns dos outros, em silêncio e com volume médio. Os que têm microfone gravam os outros.';
    const busy = (typeof calibTicker !== 'undefined' && calibTicker !== null) || (typeof state !== 'undefined' && state.playing);
    const btn = $('calib-start');
    btn.disabled = busy;
    btn.textContent = (typeof state !== 'undefined' && state.playing) ? 'Pause o metrônomo para calibrar' : 'Calibrar todos os aparelhos';
  }
  $('calib-start').addEventListener('click', async () => {
    const msg = $('calib-start-msg');
    msg.textContent = '';
    try {
      const r = await fetch('/api/calib/start', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ initiator: deviceId }) });
      const d = await r.json().catch(() => ({}));
      msg.textContent = r.ok ? 'Calibrando… não mexa nos cabos nem no volume.' : '⚠ ' + (d.error || 'Não foi possível iniciar a calibração.');
    } catch { msg.textContent = '⚠ Sem conexão com o anfitrião.'; }
  });
  async function setupCalibAdvanced() {
    if (caps.host !== 'pc' || !caps.local || !caps.calibration) return;
    try {
      const r = await api('/api/calib/interfaces', undefined, true);
      const sel = $('calib-iface');
      for (const i of r.interfaces) sel.add(new Option(`${i.name} (${i.channels} canais)`, i.index, false, i.index === r.selected));
      sel.onchange = () => api('/api/calib/interface', { index: sel.value === '' ? null : Number(sel.value) }, true).catch(() => {});
      $('calib-advanced').hidden = r.interfaces.length === 0;
    } catch { /* sem interface listada */ }
  }
  // Textos da seção de resultado conforme quem anfitria.
  function setupCalibTexts() {
    const hint = $('calib-hint');
    if (!hint) return;
    if (caps.host === 'pc') {
      hint.textContent = 'A calibração é feita pelo computador anfitrião, por cabo. Deixe esta página aberta e com o som ativo.';
    }
    if (caps.local) {
      const c = $('calib-clear'); if (c) c.hidden = true;
      const own = $('calib-auto'); if (own && own.parentElement) own.parentElement.hidden = true; // o anfitrião é a referência
    }
    const a = $('calib-audio');
    if (a) setInterval(() => { a.hidden = soundRunning(); }, 500); // o som libera sozinho no Chrome/Edge do servidor
  }

  let start = 'sync';
  try { start = localStorage.getItem('sincrono.tab') || 'sync'; } catch { /* sem armazenamento */ }
  loadCaps().then(() => {
    setupStart(); setupLeave(); setupCalibTexts(); setupCalibAdvanced();
    showTab(start); setInterval(refresh, 1500);
    updateHostCalib(); setInterval(updateHostCalib, 1000);
  });
})();

// Tenta liberar o som já ao abrir a página. Em Chrome/Edge abertos pelo servidor do Sincrono
// (política de autoplay liberada) isso funciona sem clique; nos demais, o primeiro toque libera.
try { getAudio().resume().catch(() => {}); } catch (e) { /* sem áudio disponível */ }
