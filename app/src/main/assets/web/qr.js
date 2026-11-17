// Gerador de QR code mínimo (modo byte, correção de erro M, versões 1 a 5 = até 84 bytes).
// Serve para o endereço da sessão. Devolve uma matriz de booleanos (true = módulo escuro).
(function (root) {
  'use strict';
  // versão -> [codewords de correção por bloco, [[nº de blocos, codewords de dados por bloco], ...]]
  const EC_M = { 1: [10, [[1, 16]]], 2: [16, [[1, 28]]], 3: [26, [[1, 44]]], 4: [18, [[2, 32]]], 5: [24, [[2, 43]]], 6: [16, [[4, 27]]] };
  const ALIGN = { 1: [], 2: [6, 18], 3: [6, 22], 4: [6, 26], 5: [6, 30], 6: [6, 34] };

  const EXP = new Array(512), LOG = new Array(256);
  (function () { let x = 1; for (let i = 0; i < 255; i++) { EXP[i] = x; LOG[x] = i; x <<= 1; if (x & 256) x ^= 0x11d; } for (let i = 255; i < 512; i++) EXP[i] = EXP[i - 255]; })();
  const mul = (a, b) => (a === 0 || b === 0 ? 0 : EXP[LOG[a] + LOG[b]]);

  function rsGenerator(degree) {
    let poly = [1];
    for (let i = 0; i < degree; i++) {
      const next = new Array(poly.length + 1).fill(0);
      for (let j = 0; j < poly.length; j++) { next[j] ^= poly[j]; next[j + 1] ^= mul(poly[j], EXP[i]); }
      poly = next;
    }
    return poly; // coeficientes do termo de maior grau para o menor (o primeiro é 1)
  }
  function rsRemainder(data, degree) {
    const gen = rsGenerator(degree);
    const res = new Array(degree).fill(0);
    for (const b of data) {
      const factor = b ^ res.shift();
      res.push(0);
      for (let i = 0; i < degree; i++) res[i] ^= mul(gen[i + 1], factor);
    }
    return res;
  }

  function utf8(text) { return Array.from(new TextEncoder().encode(text)); }

  function encodeData(bytes, version) {
    const [ecLen, groups] = EC_M[version];
    const totalData = groups.reduce((s, g) => s + g[0] * g[1], 0);
    const bits = [];
    const put = (value, len) => { for (let i = len - 1; i >= 0; i--) bits.push((value >>> i) & 1); };
    put(4, 4); put(bytes.length, 8);
    for (const b of bytes) put(b, 8);
    const capacity = totalData * 8;
    for (let i = 0; i < 4 && bits.length < capacity; i++) bits.push(0);
    while (bits.length % 8) bits.push(0);
    const data = [];
    for (let i = 0; i < bits.length; i += 8) { let v = 0; for (let j = 0; j < 8; j++) v = (v << 1) | bits[i + j]; data.push(v); }
    for (let pad = 0xec; data.length < totalData; pad ^= 0xec ^ 0x11) data.push(pad);
    // blocos
    const blocks = []; let pos = 0;
    for (const [count, size] of groups) for (let i = 0; i < count; i++) { const d = data.slice(pos, pos + size); pos += size; blocks.push({ d, e: rsRemainder(d, ecLen) }); }
    const out = [];
    const maxData = Math.max(...blocks.map((b) => b.d.length));
    for (let i = 0; i < maxData; i++) for (const b of blocks) if (i < b.d.length) out.push(b.d[i]);
    for (let i = 0; i < ecLen; i++) for (const b of blocks) out.push(b.e[i]);
    return out;
  }

  function newMatrix(size) { return { size, m: Array.from({ length: size }, () => new Array(size).fill(false)), f: Array.from({ length: size }, () => new Array(size).fill(false)) }; }
  function setF(q, x, y, dark) { q.m[y][x] = dark; q.f[y][x] = true; }

  function drawFunctionPatterns(q, version) {
    const n = q.size;
    const finder = (cx, cy) => {
      for (let dy = -4; dy <= 4; dy++) for (let dx = -4; dx <= 4; dx++) {
        const x = cx + dx, y = cy + dy;
        if (x < 0 || y < 0 || x >= n || y >= n) continue;
        const d = Math.max(Math.abs(dx), Math.abs(dy));
        setF(q, x, y, d !== 4 && d !== 2);
      }
    };
    finder(3, 3); finder(n - 4, 3); finder(3, n - 4);
    for (let i = 8; i < n - 8; i++) { setF(q, i, 6, i % 2 === 0); setF(q, 6, i, i % 2 === 0); }
    const al = ALIGN[version];
    for (const cy of al) for (const cx of al) {
      if ((cx === 6 && cy === 6) || (cx === 6 && cy === n - 7) || (cx === n - 7 && cy === 6)) continue;
      for (let dy = -2; dy <= 2; dy++) for (let dx = -2; dx <= 2; dx++) setF(q, cx + dx, cy + dy, Math.max(Math.abs(dx), Math.abs(dy)) !== 1);
    }
    // áreas de formato (reservadas) e módulo escuro
    for (let i = 0; i < 9; i++) { if (i === 6) continue; setF(q, i, 8, false); setF(q, 8, i, false); } // (6,8) e (8,6) são do padrão de sincronismo
    for (let i = 0; i < 8; i++) { setF(q, n - 1 - i, 8, false); setF(q, 8, n - 1 - i, false); }
    setF(q, 8, n - 8, true);
  }

  function placeData(q, codewords) {
    const n = q.size; let bit = 0; const total = codewords.length * 8;
    const getBit = (i) => ((codewords[i >> 3] >>> (7 - (i & 7))) & 1) === 1;
    let upward = true;
    for (let x = n - 1; x >= 1; x -= 2) {
      if (x === 6) x = 5;
      for (let k = 0; k < n; k++) {
        const y = upward ? n - 1 - k : k;
        for (let c = 0; c < 2; c++) {
          const xx = x - c;
          if (q.f[y][xx]) continue;
          q.m[y][xx] = bit < total ? getBit(bit) : false;
          bit++;
        }
      }
      upward = !upward;
    }
  }

  const MASKS = [
    (x, y) => (x + y) % 2 === 0, (x, y) => y % 2 === 0, (x, y) => x % 3 === 0, (x, y) => (x + y) % 3 === 0,
    (x, y) => (Math.floor(y / 2) + Math.floor(x / 3)) % 2 === 0, (x, y) => ((x * y) % 2) + ((x * y) % 3) === 0,
    (x, y) => (((x * y) % 2) + ((x * y) % 3)) % 2 === 0, (x, y) => (((x + y) % 2) + ((x * y) % 3)) % 2 === 0,
  ];

  function formatBits(mask) {
    const data = (0 << 3) | mask; // nível M = 00
    let rem = data;
    for (let i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
    return ((data << 10) | rem) ^ 0x5412;
  }
  function drawFormat(m, n, mask) {
    const bits = formatBits(mask);
    const b = (i) => ((bits >>> i) & 1) === 1;
    for (let i = 0; i <= 5; i++) m[i][8] = b(i);
    m[7][8] = b(6); m[8][8] = b(7); m[8][7] = b(8);
    for (let i = 9; i < 15; i++) m[8][14 - i] = b(i);
    for (let i = 0; i < 8; i++) m[8][n - 1 - i] = b(i);
    for (let i = 8; i < 15; i++) m[n - 15 + i][8] = b(i);
    m[n - 8][8] = true;
  }

  function penalty(m, n) {
    let p = 0;
    for (let pass = 0; pass < 2; pass++) {
      for (let a = 0; a < n; a++) {
        let run = 1;
        for (let b = 1; b < n; b++) {
          const cur = pass ? m[b][a] : m[a][b], prev = pass ? m[b - 1][a] : m[a][b - 1];
          if (cur === prev) { run++; if (run === 5) p += 3; else if (run > 5) p++; } else run = 1;
        }
      }
    }
    for (let y = 0; y < n - 1; y++) for (let x = 0; x < n - 1; x++) if (m[y][x] === m[y][x + 1] && m[y][x] === m[y + 1][x] && m[y][x] === m[y + 1][x + 1]) p += 3;
    const pat = [true, false, true, true, true, false, true, false, false, false, false];
    const rev = pat.slice().reverse();
    const hit = (get) => { for (let i = 0; i + 11 <= n; i++) { let a = true, b = true; for (let k = 0; k < 11; k++) { const v = get(i + k); if (v !== pat[k]) a = false; if (v !== rev[k]) b = false; } if (a || b) return 1; } return 0; };
    for (let a = 0; a < n; a++) { for (let i = 0; i + 11 <= n; i++) { let h1 = true, h2 = true, v1 = true, v2 = true; for (let k = 0; k < 11; k++) { if (m[a][i + k] !== pat[k]) h1 = false; if (m[a][i + k] !== rev[k]) h2 = false; if (m[i + k][a] !== pat[k]) v1 = false; if (m[i + k][a] !== rev[k]) v2 = false; } p += 40 * ((h1 ? 1 : 0) + (h2 ? 1 : 0) + (v1 ? 1 : 0) + (v2 ? 1 : 0)); } }
    let dark = 0; for (let y = 0; y < n; y++) for (let x = 0; x < n; x++) if (m[y][x]) dark++;
    p += Math.floor(Math.abs(dark * 20 - n * n * 10) / (n * n)) * 10;
    return p;
  }

  function qrMatrix(text, forceMask) {
    const bytes = utf8(text);
    let version = 0;
    for (let v = 1; v <= 5; v++) { const total = EC_M[v][1].reduce((s, g) => s + g[0] * g[1], 0); if (bytes.length <= total - 2) { version = v; break; } }
    if (!version) throw new Error('Texto grande demais para o QR code.');
    const q = newMatrix(version * 4 + 17);
    drawFunctionPatterns(q, version);
    placeData(q, encodeData(bytes, version));
    const n = q.size;
    let best = null, bestP = Infinity;
    for (let mask = 0; mask < 8; mask++) {
      if (forceMask !== undefined && mask !== forceMask) continue;
      const m = q.m.map((row, y) => row.map((v, x) => (q.f[y][x] ? v : v !== MASKS[mask](x, y))));
      drawFormat(m, n, mask);
      const p = penalty(m, n);
      if (p < bestP) { bestP = p; best = m; }
    }
    return best;
  }

  function qrToCanvas(canvas, text, px) {
    const m = qrMatrix(text), n = m.length, quiet = 4, scale = px || 6;
    canvas.width = canvas.height = (n + quiet * 2) * scale;
    const c = canvas.getContext('2d');
    c.fillStyle = '#fff'; c.fillRect(0, 0, canvas.width, canvas.height);
    c.fillStyle = '#000';
    for (let y = 0; y < n; y++) for (let x = 0; x < n; x++) if (m[y][x]) c.fillRect((x + quiet) * scale, (y + quiet) * scale, scale, scale);
  }

  root.qrMatrix = qrMatrix;
  root.qrToCanvas = qrToCanvas;
  if (typeof module !== 'undefined') module.exports = { qrMatrix };
})(typeof window !== 'undefined' ? window : globalThis);
