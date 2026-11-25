'use strict';
// Muda a velocidade de um áudio SEM mudar o tom (WSOLA: sobreposição de trechos escolhidos por semelhança).
// O anfitrião gera uma cópia esticada; os outros aparelhos a recebem como um arquivo comum.
//   sincronoStretch(canais: Float32Array[], sampleRate, fator, onProgress?) -> Promise<Float32Array[]>
//     fator = velocidade nova / velocidade original (84/120 = 0,7 → fica 1/0,7 vezes mais longo). Aceita 0,4 a 2,5.
//   sincronoWav(canais, sampleRate) -> Blob (WAV PCM 16 bits)
(() => {
  const DEC = 4;          // a busca do melhor ponto é feita num sinal com 1/4 da taxa
  async function stretch(chs, sr, f, onProgress) {
    if (!(f >= 0.4 && f <= 2.5)) throw new Error('Velocidade fora do limite (0,4x a 2,5x).');
    const len = chs[0].length, nch = chs.length;
    const N = sr >= 32000 ? 2048 : 1024, Hs = N / 2, T = N / 4;
    const win = new Float32Array(N);
    for (let i = 0; i < N; i++) win[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / N);   // Hann periódica: soma 1 com sobreposição de 50%
    // sinal mono reduzido para a busca
    const dl = Math.floor(len / DEC), md = new Float32Array(dl + N);
    for (let i = 0; i < dl; i++) {
      let a = 0;
      for (let c = 0; c < nch; c++) { const x = chs[c]; for (let j = 0; j < DEC; j++) a += x[i * DEC + j]; }
      md[i] = a / (DEC * nch);
    }
    const outLen = Math.floor(len / f);
    const out = chs.map(() => new Float32Array(outLen + N));
    const M = Hs / DEC, Td = T / DEC;
    let prev = 0, k = 0;
    for (let pos = 0; pos < outLen; pos += Hs, k++) {
      let c = 0;
      if (k > 0) {
        const nominal = Math.round(pos * f);
        const tgt = Math.floor((prev + Hs) / DEC);              // continuação natural do trecho anterior
        const base = Math.floor(nominal / DEC);
        let best = -Infinity, bestI = Math.max(0, Math.min(base, Math.floor((len - N) / DEC)));
        const lo = Math.max(0, base - Td), hi = Math.min(Math.floor((len - N) / DEC), base + Td);
        for (let ci = lo; ci <= hi; ci++) {
          let corr = 0, e = 1e-9;
          for (let i = 0; i < M; i++) { const v = md[ci + i]; corr += md[tgt + i] * v; e += v * v; }
          const sc = corr / Math.sqrt(e);
          if (sc > best) { best = sc; bestI = ci; }
        }
        c = bestI * DEC;
      }
      c = Math.max(0, Math.min(c, len - N));
      for (let ch = 0; ch < nch; ch++) {
        const src = chs[ch], dst = out[ch];
        for (let i = 0; i < N; i++) {
          const w = (k === 0 && i < Hs) ? 1 : win[i];              // o começo não entra com fade
          dst[pos + i] += src[c + i] * w;
        }
      }
      prev = c;
      if (k % 400 === 0) { if (onProgress) onProgress(Math.min(1, pos / outLen)); await new Promise((r) => setTimeout(r, 0)); }
    }
    return out.map((o) => o.subarray(0, outLen));
  }
  function wav(chs, sr) {
    const n = chs[0].length, nch = chs.length, bytes = n * nch * 2;
    const buf = new ArrayBuffer(44 + bytes), v = new DataView(buf);
    const str = (o, s) => { for (let i = 0; i < s.length; i++) v.setUint8(o + i, s.charCodeAt(i)); };
    str(0, 'RIFF'); v.setUint32(4, 36 + bytes, true); str(8, 'WAVE'); str(12, 'fmt '); v.setUint32(16, 16, true);
    v.setUint16(20, 1, true); v.setUint16(22, nch, true); v.setUint32(24, sr, true); v.setUint32(28, sr * nch * 2, true);
    v.setUint16(32, nch * 2, true); v.setUint16(34, 16, true); str(36, 'data'); v.setUint32(40, bytes, true);
    let o = 44;
    for (let i = 0; i < n; i++) for (let c = 0; c < nch; c++) {
      const x = Math.max(-1, Math.min(1, chs[c][i]));
      v.setInt16(o, x < 0 ? x * 32768 : x * 32767, true); o += 2;
    }
    return new Blob([buf], { type: 'audio/wav' });
  }
  window.sincronoStretch = stretch;
  window.sincronoWav = wav;
})();
