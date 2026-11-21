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

  function render() {
    box.textContent = '';
    data.songs.forEach((m, i) => {
      const li = document.createElement('li');
      li.className = 'rep-item' + (m.id === data.current ? ' current' : '');
      const info = [m.score ? 'partitura' : 'sem partitura', m.audios.length ? m.audios.length + ' áudio(s)' : 'sem áudio', m.bpm ? Math.round(m.bpm) + ' bpm' : '', m.marks ? 'marcadores' : ''].filter(Boolean).join(' · ');
      li.innerHTML = '<div class="grow"><strong>' + esc(m.name) + '</strong><div class="muted">' + esc(info) + '</div></div>' +
        '<button type="button" data-a="apply" class="primary">Carregar</button>' +
        '<button type="button" data-a="up" aria-label="Subir" title="Subir"' + (i === 0 ? ' disabled' : '') + '>↑</button>' +
        '<button type="button" data-a="down" aria-label="Descer" title="Descer"' + (i === data.songs.length - 1 ? ' disabled' : '') + '>↓</button>' +
        '<button type="button" data-a="update" title="Guardar nesta música o que está carregado agora">Atualizar</button>' +
        '<button type="button" data-a="rename">Renomear</button>' +
        '<button type="button" data-a="remove" class="danger">Remover</button>';
      li.addEventListener('click', (ev) => {
        const a = ev.target && ev.target.getAttribute && ev.target.getAttribute('data-a');
        if (!a) return;
        if (a === 'apply') call({ op: 'apply', id: m.id }, 'Música carregada: ' + m.name + '. Os aparelhos recebem o áudio em instantes.');
        else if (a === 'up') call({ op: 'move', id: m.id, dir: -1 });
        else if (a === 'down') call({ op: 'move', id: m.id, dir: 1 });
        else if (a === 'update') { if (confirm('Substituir o que está guardado em "' + m.name + '" pelo que está carregado agora?')) call({ op: 'update', id: m.id }, 'Música atualizada.'); }
        else if (a === 'rename') { const n = prompt('Novo nome:', m.name); if (n && n.trim()) call({ op: 'rename', id: m.id, name: n.trim() }); }
        else if (a === 'remove') { if (confirm('Remover "' + m.name + '" do repertório?')) call({ op: 'remove', id: m.id }); }
      });
      box.appendChild(li);
    });
    if (!data.songs.length) { const li = document.createElement('li'); li.className = 'muted'; li.textContent = 'Nenhuma música salva ainda.'; box.appendChild(li); }
  }

  $('rep-add').addEventListener('click', async () => {
    const n = $('rep-name');
    if (await call({ op: 'add', name: n.value }, 'Música salva no repertório.')) n.value = '';
  });
  $('rep-next').addEventListener('click', () => call({ op: 'next' }, 'Próxima música carregada.'));
  $('rep-prev').addEventListener('click', () => call({ op: 'prev' }, 'Música anterior carregada.'));
  load();
  setInterval(load, 4000);
})();
