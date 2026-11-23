// Cor de cada grupo: a que o anfitrião escolheu (vem do servidor em /api/groups) ou, sem escolha, a padrão
// derivada do número do grupo (g1, g2, g3…; Geral = cinza). Igual em todos os aparelhos.
(function (root) {
  const PALETTE = ['#475569', '#1d4ed8', '#b45309', '#0f766e', '#7c3aed', '#be123c', '#15803d', '#be185d', '#0369a1', '#a16207'];
  root.sincronoGroupPalette = PALETTE;
  root.sincronoGroupColors = root.sincronoGroupColors || {};
  root.sincronoSetGroupColors = function (groups) {
    const m = {}; (groups || []).forEach((g) => { if (g && g.id && /^#[0-9a-fA-F]{6}$/.test(g.color || '')) m[g.id] = g.color; });
    root.sincronoGroupColors = m;
    const nm = {}; (groups || []).forEach((g) => { if (g && g.id && g.name) nm[g.id] = g.name; });
    if (Object.keys(nm).length) root.sincronoGroupNames = nm;
    if (root.sincronoRepaintGroup) root.sincronoRepaintGroup();
  };
  root.sincronoGroupColor = function (id) {
    if (root.sincronoGroupColors && root.sincronoGroupColors[id]) return root.sincronoGroupColors[id];
    const n = parseInt(String(id || 'g1').replace(/\D/g, ''), 10) || 1;
    return PALETTE[(n - 1) % PALETTE.length];
  };
})(window);
