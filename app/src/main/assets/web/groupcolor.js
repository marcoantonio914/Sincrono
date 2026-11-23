// Cor de cada grupo: sempre a mesma em todos os aparelhos (vem do número do grupo: g1, g2, g3…). Geral = cinza.
(function (root) {
  const PALETTE = ['#475569', '#1d4ed8', '#b45309', '#0f766e', '#7c3aed', '#be123c', '#15803d', '#be185d', '#0369a1', '#a16207'];
  root.sincronoGroupColor = function (id) {
    const n = parseInt(String(id || 'g1').replace(/\D/g, ''), 10) || 1;
    return PALETTE[(n - 1) % PALETTE.length];
  };
})(window);
