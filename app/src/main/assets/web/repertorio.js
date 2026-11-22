// Repertório (só anfitrião): lista de músicas preparadas. Carregar uma música troca partitura, áudio por grupo,
// parte de cada grupo, andamento e marcadores de uma vez. Não mexe na sincronia: só usa os comandos que já existem.
(function () {
  'use strict';
  const $ = (id) => document.getElementById(id);
  const box = $('rep-list');
  if (!box) return;
  let data = { songs: [], current: null };
  const msg = (t) => { const e = $('rep-msg'); if (e) e.textContent = t || ''; };
  const esc = (t) => String(t).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

  async function call(body, okText) {
    try {
      const r = await fetch('/api/setlist', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
      const j = await r.json().catch(() => ({}));
      if (!r.ok) { msg(j.error || 'Não foi possível.'); return false; }
      data = j; render(); msg(okText || '');
      return true;
    } catch { msg('Sem conexão com o servidor.'); return false; }
  }
  async function load() {
    try { const r = await fetch('/api/setlist', { cache: 'no-store' }); if (r.ok) { data = await r.json(); render(); } } catch { /* sem conexão */ }
  }

  let openMenu = null;
  function chips(m) {
    const c = [m.score ? 'partitura' : 'sem partitura', m.audios.length ? m.audios.length + (m.audios.length === 1 ? ' áudio' : ' áudios') : 'sem áudio'];
    if (m.bpm) c.push(Math.round(m.bpm) + ' bpm');
    if (m.marks) c.push('marcadores');
    return c;
  }
  function render() {
    // cartão "carregada agora"
    const cur = data.songs.find((m) => m.id === data.current);
    $('pl-label').textContent = cur ? 'CARREGADA AGORA' : 'NENHUMA MÚSICA CARREGADA';
    $('pl-title').textContent = cur ? cur.name : (data.songs.length ? 'Escolha uma música da lista' : 'Playlist vazia');
    $('pl-sub').textContent = cur ? chips(cur).join(' · ') : (data.songs.length ? 'Toque numa música para carregar.' : 'Monte uma música na aba MÚSICA e adicione aqui.');
    box.textContent = '';
    data.songs.forEach((m, i) => {
      const li = document.createElement('li');
      const isCur = m.id === data.current;
      li.className = 'pl-row' + (isCur ? ' current' : '');
      li.innerHTML = '<div class="pl-num">' + (isCur ? '♪' : i + 1) + '</div>' +
        '<div><div class="pl-name">' + esc(m.name) + '</div><div class="pl-chips">' + chips(m).map((t) => '<span class="pl-chip">' + esc(t) + '</span>').join('') + '</div></div>' +
        '<button type="button" data-a="apply" class="pl-go primary" aria-label="Carregar ' + esc(m.name) + '" title="Carregar"><svg class="ico" aria-hidden="true"><use href="#i-play"/></svg></button>' +
        '<button type="button" data-a="more" class="pl-more" aria-label="Mais opções" title="Mais opções">⋯</button>';
      const menu = document.createElement('div');
      menu.className = 'pl-menu'; menu.hidden = openMenu !== m.id;
      menu.innerHTML = '<button type="button" data-a="up"' + (i === 0 ? ' disabled' : '') + '>↑ Subir</button>' +
        '<button type="button" data-a="down"' + (i === data.songs.length - 1 ? ' disabled' : '') + '>↓ Descer</button>' +
        '<button type="button" data-a="update" title="Guarda nesta música o que está carregado agora">Atualizar com o atual</button>' +
        '<button type="button" data-a="rename">Renomear</button>' +
        '<button type="button" data-a="remove" class="danger">Remover</button>';
      li.appendChild(menu);
      li.addEventListener('click', (ev) => {
        const a = ev.target && ev.target.closest && ev.target.closest('[data-a]');
        const act = a ? a.getAttribute('data-a') : 'apply';       // clicar na linha = carregar
        if (ev.target.closest && ev.target.closest('.pl-menu') && !a) return;
        if (act === 'more') { openMenu = openMenu === m.id ? null : m.id; render(); return; }
        if (act === 'apply') call({ op: 'apply', id: m.id }, 'Carregada: ' + m.name + '. Os aparelhos recebem o áudio em instantes.');
        else if (act === 'up') call({ op: 'move', id: m.id, dir: -1 });
        else if (act === 'down') call({ op: 'move', id: m.id, dir: 1 });
        else if (act === 'update') { if (confirm('Substituir o que está guardado em "' + m.name + '" pelo que está carregado agora?')) call({ op: 'update', id: m.id }, 'Música atualizada.'); }
        else if (act === 'rename') { const n = prompt('Novo nome:', m.name); if (n && n.trim()) call({ op: 'rename', id: m.id, name: n.trim() }); }
        else if (act === 'remove') { if (confirm('Remover "' + m.name + '" da playlist?')) call({ op: 'remove', id: m.id }); }
      });
      box.appendChild(li);
    });
    if (!data.songs.length) { const li = document.createElement('li'); li.className = 'pl-empty'; li.textContent = 'Nenhuma música ainda. Configure uma na aba MÚSICA (partitura, áudio, partes) e toque em "Adicionar a música atual".'; box.appendChild(li); }
  }

  $('rep-add').addEventListener('click', async () => {
    const n = $('rep-name');
    if (await call({ op: 'add', name: n.value }, 'Música salva no repertório.')) n.value = '';
  });
  const clickId = (id) => () => { const b = $(id); if (b) b.click(); };
  $('pl-play').addEventListener('click', clickId('play-all'));   // usa os mesmos botões e caixas da aba MÚSICA
  $('pl-pause').addEventListener('click', clickId('pause-all'));
  $('pl-stop').addEventListener('click', clickId('stop-all'));
  $('rep-next').addEventListener('click', () => call({ op: 'next' }, 'Próxima carregada.'));
  $('rep-prev').addEventListener('click', () => call({ op: 'prev' }, 'Anterior carregada.'));
  load();
  setInterval(load, 4000);
})();
