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
  const tabs = ['sync', 'audio', 'playlist', 'mixer', 'net'];
  // A partitura mora na aba SYNC, mas na PLAYLIST ela aparece junto dos controles (quem dá o play vê o cursor sem trocar de aba).
  let scoreHome = null;
  function moveScore(name) {
    const sc = document.getElementById('score'), slot = document.getElementById('pl-score-slot');
    if (!sc || !slot) return;
    if (!scoreHome) scoreHome = { parent: sc.parentNode, next: sc.nextSibling };
    if (name === 'playlist') { if (sc.parentNode !== slot) slot.appendChild(sc); }
    else if (sc.parentNode === slot) scoreHome.parent.insertBefore(sc, scoreHome.next && scoreHome.next.parentNode === scoreHome.parent ? scoreHome.next : null);
    setTimeout(() => { if (typeof window.sincronoScoreRedraw === 'function') window.sincronoScoreRedraw(); }, 30);
  }
  function showTab(name) {
    if (!tabs.includes(name)) name = 'sync';
    document.querySelectorAll('.tab').forEach((el) => { el.hidden = el.dataset.tab !== name; });
    document.querySelectorAll('#tabs button').forEach((b) => b.classList.toggle('on', b.dataset.go === name));
    moveScore(name);
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
  function row(cls) { const d = document.createElement('div'); d.className = 'row' + (cls ? ' ' + cls : ''); return d; }
  // Botão só com ícone (nome em aria-label/title para leitores de tela e dicas).
  function iconButton(name, label, onClick, cls, disabled) {
    const b = document.createElement('button');
    b.type = 'button'; b.setAttribute('aria-label', label); b.title = label; if (cls) b.className = cls; if (disabled) b.disabled = true;
    b.innerHTML = '<svg class="ico" aria-hidden="true"><use href="#i-' + name + '"/></svg>';
    b.addEventListener('click', onClick);
    return b;
  }

  // ---------- grupos ----------
  function renderGroups() {
    const box = $('groups-list');
    if (!box || busy.size) return;
    box.textContent = '';
    window.sincronoSetGroupColors(groupsInfo.groups);
    for (const g of groupsInfo.groups) {
      const d = document.createElement('div'); d.className = 'group'; d.style.borderLeft = '6px solid ' + window.sincronoGroupColor(g.id); d.style.paddingLeft = '10px';
      const head = row();
      const dot = document.createElement('span'); dot.className = 'dot' + (g.playing ? ' on' : '');
      const title = document.createElement('strong'); title.className = 'grow'; title.textContent = g.name; title.style.color = window.sincronoGroupColor(g.id);
      const file = document.createElement('span'); file.className = 'muted'; file.textContent = g.file || 'sem arquivo';
      head.append(dot, title, file); d.appendChild(head);
      // cor do grupo: o anfitrião escolhe (a mesma cor aparece em todos os aparelhos)
      const rc = row('colors'); const lc = document.createElement('span'); lc.className = 'muted'; lc.textContent = 'Cor';
      rc.appendChild(lc);
      const cur = window.sincronoGroupColor(g.id);
      window.sincronoGroupPalette.forEach((c) => {
        const b = document.createElement('button'); b.type = 'button'; b.className = 'swatch' + (c.toLowerCase() === cur.toLowerCase() ? ' on' : '');
        b.style.background = c; b.setAttribute('aria-label', 'Cor ' + c + ' para ' + g.name); b.title = c;
        b.addEventListener('click', () => api('/api/groups', { op: 'color', id: g.id, color: c }).then(refresh));
        rc.appendChild(b);
      });
      const pick = document.createElement('input'); pick.type = 'color'; pick.value = cur; pick.className = 'swatch-pick'; pick.setAttribute('aria-label', 'Outra cor para ' + g.name); pick.title = 'Outra cor';
      pick.addEventListener('change', () => api('/api/groups', { op: 'color', id: g.id, color: pick.value }).then(refresh));
      rc.appendChild(pick); d.appendChild(rc);
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
      d.appendChild(r1);
      const r1b = row('icons');
      r1b.append(
        iconButton('tostart', 'Voltar ao início', () => api(`/api/audio/cmd?group=${g.id}`, { type: 'seek', positionMs: 0 }).then(refresh), '', !g.file),
        iconButton('play', 'Tocar', () => api(`/api/audio/cmd?group=${g.id}`, { type: 'play' }).then(refresh), 'primary', !g.file || g.playing),
        iconButton('pause', 'Pausar', () => api(`/api/audio/cmd?group=${g.id}`, { type: 'pause' }).then(refresh), '', !g.playing),
        iconButton('stop', 'Parar e voltar ao início', () => api(`/api/audio/cmd?group=${g.id}`, { type: 'stop' }).then(refresh), '', !g.file),
      );
      d.appendChild(r1b);
      // Parte da partitura que este grupo vê (só aparece depois de importar a partitura).
      const sc = window.SincronoScoreInfo;
      if (sc && sc.partNames && sc.partNames.length) {
        const rp = row();
        const lp = document.createElement('span'); lp.className = 'muted'; lp.textContent = 'Parte da partitura';
        const sp = document.createElement('select'); sp.className = 'grow'; sp.setAttribute('aria-label', 'Parte da partitura do grupo ' + g.name);
        sc.partNames.forEach((n, i) => sp.add(new Option(n, String(i), false, (sc.parts[g.id] || 0) === i)));
        sp.addEventListener('change', () => window.sincronoScoreSetPart(g.id, Number(sp.value)));
        rp.append(lp, sp); d.appendChild(rp);
      }
      const vol = row();
      const lab = document.createElement('span'); lab.className = 'muted'; lab.textContent = 'Volume do grupo';
      const sl = slider('g:' + g.id, mix['g:' + g.id] ?? 1, (v) => api('/api/mix', { device: 'g:' + g.id, level: v })); sl.className = 'grow';
      vol.append(lab, sl); d.appendChild(vol);
      const r2 = row();
      r2.appendChild(button('Renomear', () => { const n = prompt('Novo nome do grupo', g.name); if (n && n.trim()) api('/api/groups', { op: 'rename', id: g.id, name: n.trim() }).then(refresh); }));
      if (g.id !== 'g1') {
        r2.appendChild(button('Remover grupo', () => { if (confirm(`Remover o grupo "${g.name}"? Os aparelhos voltam ao grupo Geral.`)) api('/api/groups', { op: 'remove', id: g.id }).then(refresh); }, 'danger'));
      }
      d.appendChild(r2);
      const members = devices.filter((x) => x.group === g.id);
      const m = document.createElement('p'); m.className = 'muted';
      m.textContent = members.length ? 'Aparelhos: ' + members.map((x) => x.name).join(', ') : 'Nenhum aparelho neste grupo.';
      d.appendChild(m);
      box.appendChild(d);
    }
  }

  // ---------- aparelhos / mixer ----------
  // Mixer: um cartão por grupo (cor, volume do grupo, mudo/solo) com os aparelhos dele dentro.
  const mxPrev = {};   // volume de cada grupo antes de ficar mudo
  const gLevel = (id) => (typeof mix['g:' + id] === 'number' ? mix['g:' + id] : 1);
  function setGroupLevel(id, v) { mix['g:' + id] = v; return api('/api/mix', { device: 'g:' + id, level: v }); }
  const isSolo = (g) => groupsInfo.groups.length > 1 && gLevel(g.id) > 0 && groupsInfo.groups.every((o) => o.id === g.id || gLevel(o.id) === 0);
  function toggleMute(g) {
    if (gLevel(g.id) === 0) return setGroupLevel(g.id, mxPrev[g.id] || 1);
    mxPrev[g.id] = gLevel(g.id);
    return setGroupLevel(g.id, 0);
  }
  function toggleSolo(g) {
    const others = groupsInfo.groups.filter((o) => o.id !== g.id);
    if (isSolo(g)) return Promise.all(others.map((o) => setGroupLevel(o.id, mxPrev[o.id] || 1)));
    const jobs = others.map((o) => { if (gLevel(o.id) > 0) mxPrev[o.id] = gLevel(o.id); return setGroupLevel(o.id, 0); });
    if (gLevel(g.id) === 0) jobs.push(setGroupLevel(g.id, mxPrev[g.id] || 1));
    return Promise.all(jobs);
  }
  function fader(key, value, accent, onDone, label) {
    const el = slider(key, value, onDone);
    el.className = 'fader'; el.style.setProperty('--accent', accent); el.setAttribute('aria-label', label);
    const paint = () => el.style.setProperty('--p', el.value + '%');
    el.addEventListener('input', paint); paint();
    return el;
  }
  function renderDevices() {
    const box = $('devices-list');
    if (!box || busy.size) return;
    box.textContent = '';
    for (const g of groupsInfo.groups) {
      const color = window.sincronoGroupColor(g.id);
      const devs = devices.filter((d) => d.group === g.id);
      const card = document.createElement('div'); card.className = 'mx-card'; card.style.borderLeftColor = color;
      const head = row();
      const dot = document.createElement('span'); dot.className = 'extra-dot'; dot.style.background = color;
      const name = document.createElement('strong'); name.className = 'grow'; name.textContent = g.name;
      const cnt = document.createElement('span'); cnt.className = 'muted'; cnt.textContent = devs.length + (devs.length === 1 ? ' aparelho' : ' aparelhos');
      const mute = button('Mudo', () => toggleMute(g).then(refresh), 'mx-btn'); mute.setAttribute('aria-pressed', String(gLevel(g.id) === 0));
      const solo = button('Solo', () => toggleSolo(g).then(refresh), 'mx-btn'); solo.setAttribute('aria-pressed', String(isSolo(g)));
      head.append(dot, name, cnt, mute, solo); card.appendChild(head);
      card.appendChild(fader('g:' + g.id, gLevel(g.id), color, (v) => setGroupLevel(g.id, v).then(refresh), 'Volume do grupo ' + g.name));
      for (const dev of devs) {
        const d = document.createElement('div'); d.className = 'mx-dev';
        const line = row();
        const dn = document.createElement('span'); dn.className = 'grow'; dn.textContent = dev.name + (dev.id === (typeof deviceId !== 'undefined' ? deviceId : '') ? ' (esta página)' : '');
        const sel = document.createElement('select'); sel.setAttribute('aria-label', 'Grupo de ' + dev.name);
        for (const o of groupsInfo.groups) sel.add(new Option(o.name, o.id, false, o.id === dev.group));
        sel.addEventListener('change', () => api('/api/groups', { op: 'assign', device: dev.id, group: sel.value }).then(refresh));
        line.append(dn, sel); d.appendChild(line);
        d.appendChild(fader('d:' + dev.id, dev.level, color, (v) => api('/api/mix', { device: dev.id, level: v }), 'Volume de ' + dev.name));
        card.appendChild(d);
      }
      if (!devs.length) { const p = document.createElement('p'); p.className = 'hint'; p.textContent = 'Nenhum aparelho neste grupo.'; card.appendChild(p); }
      box.appendChild(card);
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

  document.addEventListener('sincrono-score', () => { if (!busy.size && !document.activeElement?.closest?.('#groups-list')) renderGroups(); });

  // O que o Tocar dispara: metrônomo (clique + cursor da partitura) e/ou áudio, começando no mesmo instante.
  // Sem nenhum áudio carregado (só partitura), o "Áudio" não tem o que tocar: o Tocar usa o metrônomo, que move a partitura.
  const anyAudio = () => (groupsInfo.groups || []).some((g) => g.file);
  const withAudio = () => $('with-audio').checked && anyAudio();
  const withMetro = () => $('with-metro').checked || ($('with-audio').checked && !anyAudio());
  function metroSettings() { return typeof validSettings === 'function' ? validSettings() : { bpm: state.bpm, beatsPerBar: state.beatsPerBar }; }
  $('play-all').addEventListener('click', async () => {
    const startAt = serverNow() + 3000;
    // Nenhuma caixa marcada = SÓ PARTITURA: um relógio mudo (metrônomo sem clique) faz o cursor andar.
    const mudo = !withMetro() && !withAudio();
    try {
      if (withAudio()) await api('/api/audio/cmd?group=all', { type: 'play', startAt });
      if (withMetro() || mudo) {
        const s = metroSettings();
        const interval = 60000 / s.bpm;
        const pos = withAudio() && typeof songPositionMs === 'function' ? (songPositionMs() || 0) : 0;
        const shift = pos > 1 ? (interval - (pos % interval)) % interval : 0;   // áudio retomado no meio: clique na próxima batida
        await api('/api/command', { type: 'play', bpm: s.bpm, beatsPerBar: s.beatsPerBar, startAt: Math.round(startAt + shift), silent: mudo });
      }
    } catch { /* api já mostrou o erro */ }
    refresh();
  });
  // Caixas Metrônomo/Áudio em tempo real: com algo tocando, marcar liga e desmarcar desliga aquela parte na hora,
  // sem parar a outra, e a posição na partitura é mantida. Com as DUAS desmarcadas a partitura segue andando
  // (relógio mudo). Ao ligar, entra alinhada pela posição na partitura (ou pela batida, sem partitura).
  async function liveToggle(which) {
    const audioPlaying = typeof songState !== 'undefined' && !!(songState && songState.playing);
    const metroPlaying = !!state.playing;
    if (!audioPlaying && !metroPlaying) return;            // nada tocando: vale só no próximo Tocar
    const metroOn = $('with-metro').checked, audioOn = $('with-audio').checked;
    const s = metroSettings();
    const interval = 60000 / s.bpm;
    const SC = window.sincronoScore;
    const msNow = SC ? SC.ms() : null;
    const hasPos = msNow !== null && msNow !== undefined;
    const cmd = (extra) => api('/api/command', Object.assign({ bpm: s.bpm, beatsPerBar: s.beatsPerBar }, extra));
    try {
      if (!metroOn && !audioOn) {
        if (metroPlaying) {
          await cmd({ type: 'settings', silent: true });                       // o clique cala; o relógio segue
        } else if (audioPlaying) {
          const T = serverNow() + 1200;                                        // o áudio para daqui a ~1,2 s
          if (hasPos) await cmd({ type: 'play', silent: true, startAt: Math.round(T - (msNow + 1200) / SC.scale(s.bpm)) });
          await api('/api/audio/cmd?group=all', { type: 'pause' }, true).catch(() => {});
        }
      } else if (which === 'metro' && !metroOn) {
        if (metroPlaying) await cmd({ type: 'pause' });
      } else if (which === 'metro' && metroOn) {
        if (metroPlaying && state.silent) {
          await cmd({ type: 'settings', silent: false });                      // o clique volta, já na posição certa
        } else if (!metroPlaying && audioPlaying) {
          const T = serverNow() + 2000;
          let start;
          if (hasPos) start = T - (msNow + 2000) / SC.scale(s.bpm);            // clique no compasso da partitura
          else { const posT = (songPositionMs() || 0) + 2000; start = T + ((interval - (posT % interval)) % interval); }
          await cmd({ type: 'play', silent: false, startAt: Math.round(start) });
        }
      } else if (which === 'audio' && !audioOn) {
        if (audioPlaying) await api('/api/audio/cmd?group=all', { type: 'pause' }, true).catch(() => {});
      } else if (which === 'audio' && audioOn && !audioPlaying && metroPlaying && anyAudio()) {
        const T = serverNow() + 2000;
        if (hasPos) {
          // o áudio entra na posição da partitura em que o relógio estará em T
          const msT = msNow + 2000 * SC.scale(s.bpm);
          await api('/api/audio/cmd?group=all', { type: 'seek', positionMs: Math.round(SC.audioPos(msT)), startAt: T });
          await api('/api/audio/cmd?group=all', { type: 'play', startAt: T });
        } else {
          const pos = songPositionMs() || 0;                                   // sem partitura: na batida do metrônomo
          const d = (((state.startAt + pos - T) % interval) + interval) % interval;
          await api('/api/audio/cmd?group=all', { type: 'play', startAt: Math.round(T + d) });
        }
      }
    } catch { /* api já mostrou o erro */ }
    refresh();
  }
  $('with-metro').addEventListener('change', () => liveToggle('metro'));
  $('with-audio').addEventListener('change', () => liveToggle('audio'));
  // Pausar/Parar valem para TUDO o que toca (música e metrônomo), independente das caixas. O áudio para num instante
  // combinado (1,2 s adiante); o metrônomo é parado junto, para os dois terminarem na mesma hora.
  async function pauseAll(kind) {
    try {
      const tocando = typeof songState !== 'undefined' && songState && songState.playing;
      await api('/api/audio/cmd?group=all', { type: kind }, true).catch(() => {});
      if (tocando) await new Promise((r) => setTimeout(r, 1200));
      const s = metroSettings();
      await api('/api/command', { type: 'pause', bpm: s.bpm, beatsPerBar: s.beatsPerBar });
    } catch { /* api já mostrou o erro */ }
    refresh();
  }
  $('pause-all').addEventListener('click', () => pauseAll('pause'));
  // Fim da música: o anfitrião para tudo e volta ao 0:00 (como "Parar todos"), sem precisar clicar em parar.
  let endHandled = false;
  setInterval(() => {
    if (document.body.classList.contains('guest')) return;
    const a = typeof songState !== 'undefined' ? songState : null;
    const dur = typeof songBuffer !== 'undefined' && songBuffer ? songBuffer.duration * 1000 : 0;
    if (!a || !a.file || !a.playing || dur <= 0) { endHandled = false; return; }
    const pos = songPositionMs() || 0;
    if (pos < dur - 500) { endHandled = false; return; }
    if (!endHandled && pos >= dur - 30) { endHandled = true; pauseAll('stop'); }
  }, 250);
  $('stop-all').addEventListener('click', () => pauseAll('stop'));
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

  // Textos da seção de resultado da calibração (esta página só toca os sons quando um celular calibra).
  function setupCalibTexts() {
    const section = $('calibration');
    if (!section) return;
    if (caps.local) { section.hidden = true; return; } // no computador anfitrião não há calibração nesta página
    const hint = $('calib-hint');
    if (hint && caps.host === 'pc') {
      hint.textContent = 'A calibração de cada celular é feita no próprio app (botão "Autocalibrar"). Esta página não calibra.';
    }
    const a = $('calib-audio');
    if (a) setInterval(() => { a.hidden = soundRunning(); }, 500);
  }

  let start = 'sync';
  try { start = localStorage.getItem('sincrono.tab') || 'sync'; } catch { /* sem armazenamento */ }
  // Só o anfitrião (a página aberta no próprio computador do servidor) tem todos os controles.
  // Quem entra de outro aparelho vê o básico: acompanhar o metrônomo e a música, e dar um apelido ao aparelho.
  function setupNickname() {
    const input = $('nickname-input'), save = $('nickname-save'), msg = $('nickname-msg');
    if (!input || !save) return;
    input.value = deviceName === defaultDeviceName ? '' : deviceName;
    save.onclick = () => {
      const v = input.value.trim().replace(/\s+/g, ' ').slice(0, 24);
      try { if (v) localStorage.setItem('sincrono.nickname', v); else localStorage.removeItem('sincrono.nickname'); } catch { /* sem armazenamento */ }
      deviceName = v || defaultDeviceName;
      msg.textContent = 'Salvo. O anfitrião verá o novo nome em instantes.';
    };
  }
  loadCaps().then(() => {
    const guest = !caps.local || new URLSearchParams(location.search).get('guest') === '1';
    document.body.classList.toggle('guest', guest);
    if (guest) {
      document.querySelectorAll('.tab').forEach((el) => { el.hidden = false; });
      setupLeave(); setupCalibTexts(); setupNickname();
      return;
    }
    setupStart(); setupLeave(); setupCalibTexts();
    showTab(start); setInterval(refresh, 1500);
  });
})();

// Tenta liberar o som já ao abrir a página. Em Chrome/Edge abertos pelo servidor do Sincrono
// (política de autoplay liberada) isso funciona sem clique; nos demais, o primeiro toque libera.
try { getAudio().resume().catch(() => {}); } catch (e) { /* sem áudio disponível */ }
