'use strict';
// Anfitrião: gera uma cópia dos áudios dos grupos numa velocidade nova, mantendo o tom (stretch.js), e ajusta o metrônomo.
// Os outros aparelhos recebem a cópia como um arquivo comum, então a sincronia não muda. Os marcadores de compasso
// (se houver) são reescalados para a cópia.
(() => {
  const $ = (id) => document.getElementById(id);
  if (!$('speed-go')) return;
  const orig = {};   // grupo -> { blob, name, stretchedName } (só nesta página aberta)
  let working = false;
  // Os originais ficam também no IndexedDB do navegador: sobrevivem a recarregar a página (o botão "Voltar ao original" não some).
  const idb = () => new Promise((res, rej) => { const r = indexedDB.open('sincrono-velocidade', 1); r.onupgradeneeded = () => r.result.createObjectStore('o'); r.onsuccess = () => res(r.result); r.onerror = () => rej(r.error); });
  async function idbGet(k) { try { const d = await idb(); return await new Promise((res) => { const q = d.transaction('o').objectStore('o').get(k); q.onsuccess = () => res(q.result); q.onerror = () => res(undefined); }); } catch { return undefined; } }
  async function idbSet(k, v) { try { const d = await idb(); await new Promise((res) => { const t = d.transaction('o', 'readwrite'); t.objectStore('o').put(v, k); t.oncomplete = res; t.onerror = res; }); } catch { /* sem armazenamento: vale só nesta página */ } }
  const stretchedBpm = (name) => { const m = /\((\d+) bpm\)\.wav$/.exec(name || ''); return m ? Number(m[1]) : null; };
  const msg = (t) => { $('speed-msg').textContent = t; };
  const baseName = (n) => n.replace(/\.[^.]+$/, '').replace(/ \(\d+ bpm\)$/, '');

  async function json(url, body) {
    const r = await fetch(url, body === undefined ? { cache: 'no-store' } : { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
    const d = await r.json().catch(() => ({}));
    if (!r.ok) throw new Error(d.error || ('Erro ' + r.status));
    return d;
  }
  async function groupsWithAudio() {
    const d = await json('/api/groups');
    const out = [];
    for (const g of d.groups || []) {
      const a = await json('/api/audio?group=' + encodeURIComponent(g.id));
      if (a.file) out.push({ id: g.id, name: g.name, file: a.file, playing: a.playing });
    }
    return out;
  }
  async function upload(g, blob, name) {
    const r = await fetch(`/api/audio/upload?group=${encodeURIComponent(g)}&name=${encodeURIComponent(name)}`, { method: 'POST', headers: { 'X-File-Type': blob.type || 'audio/wav' }, body: blob });
    if (!r.ok) throw new Error(((await r.json().catch(() => ({}))).error) || 'Falha ao enviar o áudio.');
  }
  async function setMarks(sig, marks, offset) {
    try { await json('/api/score/marks', { sig, marks, offset }); } catch { /* sem partitura/marcadores */ }
  }

  async function run(restore) {
    if (working) return;
    const from = Number($('speed-from').value), to = Number($('speed-to').value);
    if (!restore && !(from >= 30 && from <= 300 && to >= 30 && to <= 300)) { msg('Informe os dois andamentos (30 a 300 bpm).'); return; }
    const f = restore ? 1 : to / from;
    if (!restore && !(f >= 0.4 && f <= 2.5)) { msg('A mudança passa do limite (de 0,4x a 2,5x a velocidade original).'); return; }
    working = true; $('speed-go').disabled = $('speed-reset').disabled = true;
    try {
      const list = await groupsWithAudio();
      if (!list.length) { msg('Nenhum grupo tem áudio carregado.'); return; }
      if (list.some((g) => g.playing)) { msg('Pare a música antes de mudar a velocidade.'); return; }
      const scoreNow = await json('/api/score').catch(() => ({ marks: {} }));
      let done = 0;
      for (const g of list) {
        let o = orig[g.id] || (orig[g.id] = (await idbGet(g.id)) || null);
        if (restore && (!o || (o.stretchedName !== g.file.name && o.name !== g.file.name))) {
          msg(`O original de ${g.name} não está guardado (a cópia foi feita em outro navegador ou os dados do site foram apagados). Carregue o arquivo original de novo.`);
          return;
        }
        if (!o || (o.stretchedName !== g.file.name && o.name !== g.file.name)) {   // o arquivo mudou: o atual passa a ser o original
          const r = await fetch(`/api/audio/file?id=${g.file.id}&group=${encodeURIComponent(g.id)}`);
          if (!r.ok) throw new Error('Não consegui baixar o áudio de ' + g.name + '.');
          o = orig[g.id] = { blob: await r.blob(), name: g.file.name, stretchedName: '' };
        }
        const prefix = `${g.name}: `;
        if (restore || Math.abs(f - 1) < 0.005) {
          await upload(g.id, o.blob, o.name); o.stretchedName = ''; await idbSet(g.id, o);
        } else {
          msg(prefix + 'lendo o áudio…');
          const ctx = (typeof getAudio === 'function') ? getAudio() : new (window.AudioContext || window.webkitAudioContext)();
          const buf = await ctx.decodeAudioData(await o.blob.arrayBuffer());
          const chs = []; for (let c = 0; c < Math.min(2, buf.numberOfChannels); c++) chs.push(new Float32Array(buf.getChannelData(c)));
          const out = await window.sincronoStretch(chs, buf.sampleRate, f, (p) => msg(`${prefix}ajustando a velocidade… ${Math.round(p * 100)}% (${done + 1} de ${list.length})`));
          const name = `${baseName(o.name)} (${Math.round(to)} bpm).wav`;
          msg(prefix + 'enviando…');
          await upload(g.id, window.sincronoWav(out, buf.sampleRate), name);
          o.stretchedName = name; await idbSet(g.id, o);
          const m = scoreNow.marks && scoreNow.marks[o.name];
          if (m && m.marks && m.marks.length) await setMarks(name, m.marks.map((x) => Math.round(x / f * 10) / 10), m.offset || 0);
        }
        done++;
      }
      // o metrônomo acompanha (andamento novo), mantendo o compasso
      const bpmNew = restore ? Number($('speed-from').value) : to;
      if (bpmNew >= 30 && bpmNew <= 300) {
        const st = await json('/api/state').catch(() => null);
        if (st) await json('/api/command', { type: 'settings', bpm: bpmNew, beatsPerBar: st.beatsPerBar || 4 }).catch(() => {});
      }
      await refreshReset();
      msg(restore || Math.abs(f - 1) < 0.005 ? 'Áudios originais de volta.' : `Pronto: ${done} áudio(s) a ${Math.round(to)} bpm (${(f * 100).toFixed(0)}% da velocidade original), tom mantido. O metrônomo foi ajustado para ${Math.round(to)} bpm.`);
    } catch (e) {
      msg('Não deu certo: ' + e.message);
    } finally {
      working = false; $('speed-go').disabled = $('speed-reset').disabled = false;
    }
  }
  // O botão "Voltar ao original" aparece sempre que algum áudio é uma cópia (nome "(N bpm).wav" ou cópia feita aqui)
  async function refreshReset() {
    try {
      const list = await groupsWithAudio();
      $('speed-reset').hidden = !list.some((g) => stretchedBpm(g.file.name) || (orig[g.id] && orig[g.id].stretchedName === g.file.name));
    } catch { /* servidor fora */ }
  }
  // Acompanhar o metrônomo: o áudio é refeito quando o BPM muda e a música está parada
  let pendingBpm = null, pendingSince = 0;
  setInterval(async () => {
    if (working || !$('speed-follow').checked || typeof state === 'undefined') return;
    try {
      const bpm = Math.round(state.bpm);
      const list = await groupsWithAudio();
      if (!list.length) return;
      const cur = stretchedBpm(list[0].file.name) || Number($('speed-from').value);
      if (!cur || Math.abs(bpm - cur) < 1) { pendingBpm = null; return; }
      if (list.some((g) => g.playing)) { msg(`O áudio será ajustado a ${bpm} bpm quando a música parar.`); return; }
      if (pendingBpm !== bpm) { pendingBpm = bpm; pendingSince = Date.now(); return; }
      if (Date.now() - pendingSince < 1500) return;
      pendingBpm = null; $('speed-to').value = bpm; run(false);
    } catch { /* tenta de novo */ }
  }, 700);
  refreshReset(); setInterval(() => { if (!working) refreshReset(); }, 3000);
  $('speed-go').addEventListener('click', () => run(false));
  $('speed-reset').addEventListener('click', () => run(true));
  // sugestões iniciais: "gravado a" = andamento da partitura (se houver), "tocar a" = o do metrônomo
  let primed = false;
  setInterval(() => {
    if (primed) return;
    const bpm = $('bpm') && Number($('bpm').value);
    const sc = window.SincronoScoreInfo && window.SincronoScoreInfo.initialBpm;
    if (bpm) { $('speed-to').value = bpm; $('speed-from').value = sc || bpm; primed = true; }
  }, 500);
})();
