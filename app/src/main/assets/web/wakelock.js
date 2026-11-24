// Mantém a tela acesa enquanto algo toca (metrônomo ou música): navegador com a tela apagada suspende o áudio.
// Só segura a trava durante o tocar; solta ao parar. Se o navegador não tem a função, não faz nada.
(function () {
  if (!('wakeLock' in navigator)) return;
  let lock = null, asking = false;
  const playing = () => {
    try { if (typeof state !== 'undefined' && state && state.playing) return true; } catch { /* ainda não existe */ }
    try { if (typeof songState !== 'undefined' && songState && songState.playing) return true; } catch { /* idem */ }
    try { if (window.sincronoExtraAudio && Object.values(window.sincronoExtraAudio.players).some((p) => p.node)) return true; } catch { /* idem */ }
    return false;
  };
  async function tick() {
    const want = playing() && document.visibilityState === 'visible';
    if (want && !lock && !asking) {
      asking = true;
      try {
        lock = await navigator.wakeLock.request('screen');
        lock.addEventListener('release', () => { lock = null; });
      } catch { lock = null; } finally { asking = false; }
    } else if (!want && lock) {
      const l = lock; lock = null;
      try { await l.release(); } catch { /* já solta */ }
    }
  }
  setInterval(tick, 1500);
  document.addEventListener('visibilitychange', tick);
})();
