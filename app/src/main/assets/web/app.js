const bpmInput = document.querySelector('#bpm');
const beatsInput = document.querySelector('#beats');
const connection = document.querySelector('#connection');
const syncStatus = document.querySelector('#sync');
const status = document.querySelector('#status');
const beat = document.querySelector('#beat');
const offsetLabel = document.querySelector('#offset-value');

const LOOKAHEAD_MS = 150; // quanto à frente os cliques são agendados no motor de áudio
const TICK_MS = 25;       // frequência do agendador

let audioContext;
let state = { playing: false, bpm: 120, beatsPerBar: 4, startAt: null, revision: -1 };
let offsetMs = 0;           // diferença entre o relógio do servidor e o deste aparelho
let syncSamples = [];       // amostras recentes de relógio (guardamos a de menor latência)
let syncCount = 0;
let nextBeatIndex = null;   // próxima batida ainda não agendada
let tickTimer = null;

// Ajuste fino deste aparelho, em ms. Positivo = tocar mais cedo (use se este aparelho soa atrasado).
let userOffsetMs = 0;
// Ajuste da calibração automática (ms, positivo = tocar mais cedo). Substitui o ajuste manual.
let autoOffsetMs = 0;
try { userOffsetMs = Number(localStorage.getItem('metronomo.offsetMs')) || 0; } catch { /* sem armazenamento */ }
// A calibração guardada só vale se foi feita contra o anfitrião desta sessão.
let storedAuto = 0;
let storedAutoRef = '';
let sessionHostId = '';
let autoValid = false;
try {
  storedAuto = Number(localStorage.getItem('metronomo.autoOffsetMs')) || 0;
  storedAutoRef = localStorage.getItem('metronomo.autoRef') || '';
} catch { /* sem armazenamento */ }

function serverNow() { return Date.now() + offsetMs; }
function getAudio() {
  if (!audioContext) audioContext = new AudioContext({ latencyHint: 'interactive' });
  return audioContext;
}
// Volume: próprio (controle desta página) × definido pelo anfitrião. Vale para o metrônomo e a música;
// os sons da calibração vão direto ao destino, sem passar por aqui.
let masterGain = null;
let ownVolume = 1;
let hostVolume = 1;
try { const v = parseFloat(localStorage.getItem('metronomo.ownVolume')); if (v >= 0 && v <= 1) ownVolume = v; } catch { /* sem armazenamento */ }
function getMaster() {
  const context = getAudio();
  if (!masterGain) {
    masterGain = context.createGain();
    masterGain.gain.value = ownVolume * hostVolume;
    masterGain.connect(context.destination);
  }
  return masterGain;
}
function applyVolume() {
  if (masterGain) masterGain.gain.setTargetAtTime(ownVolume * hostVolume, audioContext.currentTime, 0.05);
  const label = document.getElementById('volume-label');
  if (label) {
    label.textContent = `${Math.round(ownVolume * 100)}%` +
      (hostVolume < 0.995 ? ` (o anfitrião ajustou para ${Math.round(hostVolume * 100)}% — final ${Math.round(ownVolume * hostVolume * 100)}%)` : '');
  }
}
function setSongGroup(group) {
  if (group === songGroup) return;
  songGroup = group;
  songState = null;
  songLoadingId = 0;
  songBuffer = null;
  songBufferId = 0;
  stopSongNode();
  if (songEvents) { songEvents.close(); songEvents = null; }
  if (songNameLabel) songNameLabel.textContent = '';
  if (songStatus) songStatus.textContent = '';
}
let groupLevel = 1;
async function pollMix() {
  try {
    const response = await fetch('/api/mix', { cache: 'no-store' });
    if (!response.ok) return;
    const data = await response.json();
    const levels = data.levels || {};
    let group = 'g1';
    try {
      const g = await fetch('/api/groups', { cache: 'no-store' });
      if (g.ok) {
        const info = await g.json();
        const wanted = info.assign && info.assign[deviceId];
        if (wanted && (info.groups || []).some((x) => x.id === wanted)) group = wanted;
        window.sincronoGroupNames = Object.fromEntries((info.groups || []).map((x) => [x.id, x.name]));
      }
    } catch { /* servidor sem grupos */ }
    setSongGroup(group);
    const own = typeof levels[deviceId] === 'number' ? levels[deviceId] : 1;
    const byGroup = typeof levels['g:' + group] === 'number' ? levels['g:' + group] : 1;
    const mine = own * byGroup;
    if (mine !== hostVolume) { hostVolume = mine; applyVolume(); }
  } catch { /* sem conexão: mantém o último volume */ }
}
function validSettings() {
  return { bpm: Math.max(30, Math.min(300, Number(bpmInput.value) || 120)), beatsPerBar: Math.max(1, Math.min(12, Math.round(Number(beatsInput.value) || 4))) };
}
async function command(type) {
  const settings = validSettings();
  if (type !== 'pause') await getAudio().resume();
  const response = await fetch('/api/command', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ type, ...settings }) });
  if (!response.ok) status.textContent = 'Não foi possível enviar o comando.';
}

function clickAt(audioTime, accented) {
  const context = getAudio();
  const oscillator = context.createOscillator();
  const gain = context.createGain();
  oscillator.frequency.value = accented ? 1320 : 880;
  gain.gain.setValueAtTime(0.0001, audioTime);
  gain.gain.exponentialRampToValueAtTime(accented ? 0.22 : 0.16, audioTime + 0.002);
  gain.gain.exponentialRampToValueAtTime(0.0001, audioTime + 0.04);
  oscillator.connect(gain).connect(getMaster());
  oscillator.start(audioTime);
  oscillator.stop(audioTime + 0.05);
}

// Converte "instante em que o som deve ser OUVIDO" (relógio do servidor, ms) para o
// tempo do AudioContext em que ele precisa ser agendado, descontando a latência real de
// saída deste aparelho. getOutputTimestamp() informa qual amostra está saindo agora.
function contextTimeForHeardAt(serverMs, withOffsets = true) {
  const context = audioContext;
  const nowPerf = performance.now();
  const wallMinusPerf = Date.now() - nowPerf;
  const targetPerf = serverMs - offsetMs - wallMinusPerf - (withOffsets ? userOffsetMs + autoOffsetMs : 0);
  const stamp = context.getOutputTimestamp ? context.getOutputTimestamp() : null;
  if (stamp && stamp.performanceTime > 0 && stamp.contextTime > 0) {
    return stamp.contextTime + (targetPerf - stamp.performanceTime) / 1000;
  }
  const latency = (context.outputLatency || 0) + (context.baseLatency || 0);
  return context.currentTime + (targetPerf - nowPerf) / 1000 - latency;
}

function tick() {
  if (!state.playing || !state.startAt || !audioContext || audioContext.state !== 'running') return;
  const interval = 60000 / state.bpm;
  const nowServer = serverNow();
  if (nextBeatIndex === null) {
    // Primeira batida ainda por vir (com folga para a latência de saída).
    nextBeatIndex = Math.max(0, Math.ceil((nowServer - state.startAt) / interval));
  }
  for (let guard = 0; guard < 8; guard++) {
    const beatServerMs = state.startAt + nextBeatIndex * interval;
    const when = contextTimeForHeardAt(beatServerMs);
    const ahead = when - audioContext.currentTime;
    if (ahead > LOOKAHEAD_MS / 1000) break;       // ainda está longe: fica para o próximo ciclo
    const index = nextBeatIndex;
    nextBeatIndex++;
    if (ahead < -0.005) continue;                 // já passou: não toca atrasado
    const accented = index % state.beatsPerBar === 0;
    clickAt(Math.max(when, audioContext.currentTime), accented);
    const label = `${(index % state.beatsPerBar) + 1} / ${state.beatsPerBar}`;
    setTimeout(() => { if (state.playing) beat.textContent = label; }, Math.max(0, ahead * 1000));
  }
}

function startTicking() {
  if (tickTimer === null) tickTimer = setInterval(tick, TICK_MS);
}
function stopTicking() {
  if (tickTimer !== null) { clearInterval(tickTimer); tickTimer = null; }
}

function applyState(next) {
  if (next.revision < state.revision) return;
  state = next;
  nextBeatIndex = null;
  bpmInput.value = state.bpm;
  beatsInput.value = state.beatsPerBar;
  status.textContent = state.playing ? `Início sincronizado em ${new Date(state.startAt).toLocaleTimeString()}` : 'Parado';
  if (!state.playing) beat.textContent = '—';
  // O tick() só toca com o áudio liberado; iniciar o relógio sempre garante que, se o som for ativado
  // DEPOIS de outro aparelho já ter dado play, esta página comece a tocar assim que o áudio rodar.
  if (state.playing) startTicking(); else stopTicking();
}

async function synchronizeClock() {
  const clientSentAt = Date.now();
  try {
    const response = await fetch('/api/sync', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ clientSentAt }) });
    const clientReceivedAt = Date.now();
    const sample = await response.json();
    const rtt = clientReceivedAt - sample.clientSentAt - (sample.serverSentAt - sample.serverReceivedAt);
    const sampleOffset = ((sample.serverReceivedAt - sample.clientSentAt) + (sample.serverSentAt - clientReceivedAt)) / 2;
    if (rtt >= 0) {
      syncSamples.push({ rtt, offset: sampleOffset });
      if (syncSamples.length > 12) syncSamples.shift();
      const best = syncSamples.reduce((a, b) => (b.rtt < a.rtt ? b : a));
      offsetMs = syncCount === 0 || Math.abs(best.offset - offsetMs) > 30 ? best.offset : offsetMs + (best.offset - offsetMs) * 0.3;
      syncStatus.textContent = `Relógio sincronizado (rede: ${Math.max(0, Math.round(best.rtt))} ms)`;
    }
    syncCount++;
  } catch {
    syncStatus.textContent = 'Não foi possível sincronizar o relógio.';
  }
}

function connectEvents() {
  const events = new EventSource('/events');
  events.onopen = () => { connection.textContent = 'Conectado à sessão local.'; };
  events.addEventListener('state', (event) => applyState(JSON.parse(event.data)));
  events.onerror = () => { connection.textContent = 'Reconectando à sessão…'; };
}

function showOffset() {
  offsetLabel.textContent = `${userOffsetMs > 0 ? '+' : ''}${userOffsetMs} ms`;
}
function changeOffset(delta) {
  userOffsetMs = Math.max(-200, Math.min(200, userOffsetMs + delta));
  try { localStorage.setItem('metronomo.offsetMs', String(userOffsetMs)); } catch { /* sem armazenamento */ }
  nextBeatIndex = null;
  showOffset();
}
document.querySelectorAll('[data-offset]').forEach((button) => {
  button.addEventListener('click', () => changeOffset(Number(button.dataset.offset)));
});
showOffset();

document.querySelector('#play').addEventListener('click', () => command('play'));
document.querySelector('#pause').addEventListener('click', () => command('pause'));

connectEvents();
// Rajada inicial de amostras para convergir rápido; depois, a cada 2 s.
for (let i = 0; i < 10; i++) setTimeout(synchronizeClock, i * 200);
setInterval(synchronizeClock, 2000);
fetch('/api/state').then((response) => response.json()).then(applyState).catch(() => { connection.textContent = 'Servidor indisponível.'; });

// ---------------------------------------------------------------------------
// Calibração automática. Este aparelho só TOCA os sons de teste, nos horários
// combinados, sem aplicar nenhum ajuste; quem grava é um celular com o app.
// ---------------------------------------------------------------------------
const calibSection = document.querySelector('#calibration');
const calibStatus = document.querySelector('#calib-status');
const calibLines = document.querySelector('#calib-lines');
const calibAutoLabel = document.querySelector('#calib-auto');

let deviceId = null;
try { deviceId = localStorage.getItem('metronomo.deviceId'); } catch { /* sem armazenamento */ }
if (!deviceId) {
  deviceId = (self.crypto && self.crypto.randomUUID) ? crypto.randomUUID()
    : `web-${Math.random().toString(16).slice(2)}${Date.now().toString(16)}`;
  try { localStorage.setItem('metronomo.deviceId', deviceId); } catch { /* sem armazenamento */ }
}
const defaultDeviceName = `Computador (${(navigator.platform || 'navegador').trim()}) ${deviceId.slice(0, 4)}`;
// Apelido escolhido pelo usuário desta página (o anfitrião o vê nas listas de aparelhos e grupos).
let deviceName = defaultDeviceName;
try { const saved = (localStorage.getItem('sincrono.nickname') || '').trim(); if (saved) deviceName = saved; } catch { /* sem armazenamento */ }

let calibSupported = true;
let calibFirst = true;
let calibLastStarted = -Infinity;
let calibLastResult = -Infinity;
let calibTimes = [];
let calibNext = 0;
let calibTicker = null;
let chirpBuffer = null;

function showAutoOffset() {
  calibAutoLabel.textContent = autoValid
    ? `${autoOffsetMs > 0 ? '+' : ''}${autoOffsetMs.toFixed(1)} ms`
    : 'não calibrada para este anfitrião';
}
function saveOffsets() {
  try {
    localStorage.setItem('metronomo.autoOffsetMs', String(storedAuto));
    localStorage.setItem('metronomo.autoRef', storedAutoRef);
    localStorage.setItem('metronomo.offsetMs', String(userOffsetMs));
  } catch { /* sem armazenamento */ }
}
// Recalcula o ajuste em uso: só vale a calibração feita contra o anfitrião atual.
function refreshAuto() {
  autoValid = storedAutoRef !== '' && sessionHostId !== '' && storedAutoRef === sessionHostId;
  const next = autoValid ? storedAuto : 0;
  if (next !== autoOffsetMs) nextBeatIndex = null;
  autoOffsetMs = next;
  showAutoOffset();
}
function applyAutoOffset(ms, hostId) {
  storedAuto = ms;
  storedAutoRef = hostId || '';
  if (hostId) sessionHostId = hostId;
  userOffsetMs = 0; // a calibração substitui o ajuste manual
  nextBeatIndex = null;
  saveOffsets();
  refreshAuto();
  showOffset();
}

// Mesmo som que o aplicativo usa na análise: varredura de 2 a 6 kHz, 40 ms, janela de Hann.
let chirpAmplitude = 0.9; // a sessão informa o volume (mais baixo no modo cabo)
function getChirpBuffer(context) {
  if (chirpBuffer && chirpBuffer.sampleRate === context.sampleRate && chirpBuffer.amp === chirpAmplitude) return chirpBuffer;
  const f0 = 2000;
  const f1 = 6000;
  const duration = 0.04;
  const length = Math.round(duration * context.sampleRate);
  chirpBuffer = context.createBuffer(1, length, context.sampleRate);
  const data = chirpBuffer.getChannelData(0);
  for (let i = 0; i < length; i++) {
    const t = i / context.sampleRate;
    const window = 0.5 * (1 - Math.cos(2 * Math.PI * t / duration));
    const phase = 2 * Math.PI * (f0 * t + (f1 - f0) / (2 * duration) * t * t);
    data[i] = chirpAmplitude * window * Math.sin(phase);
  }
  chirpBuffer.amp = chirpAmplitude;
  return chirpBuffer;
}

function playChirp(when) {
  const source = audioContext.createBufferSource();
  source.buffer = getChirpBuffer(audioContext);
  source.connect(audioContext.destination);
  source.start(when);
}

function calibTick() {
  if (!audioContext || audioContext.state !== 'running') return;
  for (let guard = 0; guard < 4 && calibNext < calibTimes.length; guard++) {
    const when = contextTimeForHeardAt(calibTimes[calibNext], false); // sem ajustes: queremos o erro bruto
    const ahead = when - audioContext.currentTime;
    if (ahead > LOOKAHEAD_MS / 1000) break;
    calibNext++;
    if (ahead < -0.005) continue; // já passou: a análise tolera buracos
    playChirp(Math.max(when, audioContext.currentTime));
  }
  if (calibNext >= calibTimes.length && calibTicker !== null) {
    clearInterval(calibTicker);
    calibTicker = null;
  }
}

function participateInCalibration(session) {
  const ids = session.devices.map((d) => d.id);
  const me = ids.indexOf(deviceId);
  if (me < 0) return;
  if (!audioContext || audioContext.state !== 'running') {
    calibStatus.textContent = 'A calibração começou, mas o áudio deste aparelho não está ativo. Toque em "Ativar áudio" e peça para calibrar de novo.';
    return;
  }
  if (serverNow() > session.startAt - 500) {
    calibStatus.textContent = 'A calibração começou antes de esta página se preparar. Tente de novo.';
    return;
  }
  const n = ids.length;
  calibTimes = [];
  for (let r = 0; r <= session.repeats; r++) calibTimes.push(session.startAt + (r * n + me) * session.slotMs);
  calibNext = 0;
  calibLines.textContent = '';
  chirpAmplitude = session.amp > 0 && session.amp <= 1 ? session.amp : 0.9;
  calibStatus.textContent = session.cable
    ? 'Calibrando por cabo… mantenha a saída de áudio deste computador ligada à mesa e não mexa nos cabos nem no volume.'
    : 'Calibrando… mantenha silêncio e as caixas voltadas para o microfone do celular que está gravando.';
  if (calibTicker !== null) clearInterval(calibTicker);
  calibTicker = setInterval(calibTick, TICK_MS);
}

function showCalibrationResult(session) {
  calibLines.textContent = '';
  if (session.state === 'failed') {
    calibStatus.textContent = session.message || 'A calibração falhou.';
    return;
  }
  const results = session.results || {};
  const single = session.mode === 'single';
  for (const device of session.devices) {
    // Na calibração em par só o aparelho novo recebe resultado.
    if (single && device.id !== session.target) continue;
    const item = document.createElement('li');
    const r = results[device.id];
    if (!r) {
      item.textContent = `${device.name}: não foi ouvido`;
    } else {
      const reference = !single && device.id === session.host ? ' (anfitrião, referência)' : '';
      item.textContent = `${device.name}: ${r.offsetMs > 0 ? '+' : ''}${r.offsetMs.toFixed(1)} ms (variação ${r.spreadMs.toFixed(1)} ms, confiança ${qualityLabel(r.quality)})${reference}`;
    }
    calibLines.appendChild(item);
  }
  if (single && session.target !== deviceId) {
    calibStatus.textContent = 'Calibração em par concluída. Esta página não foi alterada.';
    return;
  }
  const mine = results[deviceId];
  if (!mine) {
    calibStatus.textContent = 'Esta página não foi ouvida. Aproxime as caixas do microfone e aumente o volume.';
    return;
  }
  const quality = mine.quality || 'medium';
  // Um resultado de baixa confiança não substitui uma calibração válida (contra este anfitrião) já em uso.
  if (quality === 'low' && autoValid) {
    calibStatus.textContent = `Resultado de baixa confiança (variação ${mine.spreadMs.toFixed(1)} ms): mantive a calibração anterior. Tente de novo com as caixas mais próximas do microfone e em silêncio.`;
    return;
  }
  applyAutoOffset(mine.offsetMs, session.host);
  const signed = `${mine.offsetMs > 0 ? '+' : ''}${mine.offsetMs.toFixed(1)}`;
  calibStatus.textContent = quality === 'low'
    ? `Calibração aplicada com BAIXA confiança (variação ${mine.spreadMs.toFixed(1)} ms, ajuste ${signed} ms). Refaça em silêncio e com as caixas mais próximas.`
    : `Calibração concluída (confiança ${qualityLabel(quality)}, variação ${mine.spreadMs.toFixed(1)} ms). Esta página foi ajustada em ${signed} ms.`;
}

function qualityLabel(q) {
  return q === 'high' ? 'alta' : q === 'low' ? 'baixa' : 'média';
}

function handleCalibration(session) {
  if (session.host && session.host !== sessionHostId) {
    sessionHostId = session.host;
    refreshAuto();
  }
  if (calibFirst) {
    calibFirst = false;
    if (session.state !== 'running') { // não aplica resultados de sessões antigas ao abrir a página
      calibLastStarted = session.id;
      calibLastResult = session.id;
      return;
    }
  }
  if (session.state === 'running' && session.id > calibLastStarted) {
    calibLastStarted = session.id;
    participateInCalibration(session);
  } else if ((session.state === 'done' || session.state === 'failed') && session.id > calibLastResult) {
    calibLastResult = session.id;
    showCalibrationResult(session);
  }
}

async function pollCalibration() {
  if (!calibSupported) return;
  try {
    const response = await fetch(`/api/calib?device=${encodeURIComponent(deviceId)}&name=${encodeURIComponent(deviceName)}` +
      `&host=0&auto=${autoOffsetMs.toFixed(3)}&cal=${autoValid ? 1 : 0}&route=WEB`);
    if (response.status === 404) { // servidor sem suporte (ex.: servidor Node.js)
      calibSupported = false;
      calibSection.hidden = true;
      return;
    }
    if (response.ok) handleCalibration(await response.json());
  } catch { /* tenta de novo no próximo ciclo */ }
}

function initCalibration() {
  showAutoOffset();
  document.querySelector('#calib-audio').addEventListener('click', async () => {
    await getAudio().resume();
    calibStatus.textContent = 'Áudio ativado. Esta página já pode participar da calibração.';
  });
  document.querySelector('#calib-clear').addEventListener('click', () => {
    applyAutoOffset(0, '');
    calibStatus.textContent = 'Calibração removida.';
  });
  // Em repouso consulta de vez em quando; durante uma calibração, mais rápido.
  const loop = async () => {
    await pollCalibration();
    setTimeout(loop, calibTicker !== null ? 500 : 1500);
  };
  loop();
}

initCalibration();


// ---------------------------------------------------------------------------
// Áudio sincronizado. O anfitrião diz "no instante startAt (servidor) o arquivo está em
// positionMs"; a posição que deve ser ouvida a cada momento é uma função do relógio do
// servidor. A página baixa o arquivo, decodifica e corrige a deriva do relógio de áudio
// ajustando de leve a velocidade (no máximo 0,1%).
// ---------------------------------------------------------------------------
const songSection = document.querySelector('#song');
const songNameLabel = document.querySelector('#song-name strong');
const songStatus = document.querySelector('#song-status');
const songPosition = document.querySelector('#song-position');

let songSupported = true;
let songState = null;       // último estado recebido de /api/audio
let songBuffer = null;      // AudioBuffer decodificado
let songBufferId = 0;       // id do arquivo que está em songBuffer
let songLoadingId = 0;
let songNode = null;        // { src, gain, startAt, positionMs, segP, segT, segRate, fadeScheduled }
let songRevision = -1;
let songGroup = 'g1';     // grupo ("ilha") cujo arquivo esta página toca; definido pelo anfitrião

const SONG_JUMP_S = 0.15;       // acima deste erro, recomeça no ponto certo
const SONG_MAX_RATE = 0.001;
const SONG_FADE_IN_S = 0.01;
const SONG_FADE_OUT_S = 0.03;

// Inverso de contextTimeForHeardAt: que instante do servidor está sendo OUVIDO no tempo t do contexto.
function serverHeardAtContextTime(t) {
  const reference = serverNow();
  return reference + (t - contextTimeForHeardAt(reference)) * 1000;
}

function songPositionMs() {
  const s = songState;
  if (!s || !s.file) return null;
  if (!s.playing) return s.positionMs;
  const now = serverNow();
  const end = s.stopAt ? Math.min(now, s.stopAt) : now;
  const position = s.positionMs + Math.max(0, end - s.startAt);
  return songBuffer ? Math.min(position, songBuffer.duration * 1000) : position;
}

function formatSongTime(ms) {
  const total = Math.max(0, Math.floor(ms / 1000));
  return `${Math.floor(total / 60)}:${String(total % 60).padStart(2, '0')}`;
}

function stopSongNode() {
  if (!songNode) return;
  const node = songNode;
  songNode = null;
  try { node.src.stop(); } catch { /* já parou */ }
  try { node.src.disconnect(); node.gain.disconnect(); } catch { /* ok */ }
}

function scheduleSongFadeOut(stopAtServerMs) {
  if (!songNode || songNode.fadeScheduled) return;
  const stopCtx = contextTimeForHeardAt(stopAtServerMs);
  const gain = songNode.gain.gain;
  gain.setValueAtTime(1, Math.max(audioContext.currentTime, stopCtx - SONG_FADE_OUT_S));
  gain.linearRampToValueAtTime(0, Math.max(audioContext.currentTime + 0.001, stopCtx));
  try { songNode.src.stop(stopCtx + 0.02); } catch { /* ok */ }
  songNode.fadeScheduled = true;
}

function beginSongNode(ctxStart, mediaSeconds, state) {
  stopSongNode();
  const src = audioContext.createBufferSource();
  src.buffer = songBuffer;
  const gain = audioContext.createGain();
  gain.gain.setValueAtTime(0, ctxStart);
  gain.gain.linearRampToValueAtTime(1, ctxStart + SONG_FADE_IN_S);
  src.connect(gain).connect(getMaster());
  src.start(ctxStart, Math.max(0, mediaSeconds));
  songNode = { src, gain, startAt: state.startAt, positionMs: state.positionMs, segP: mediaSeconds, segT: ctxStart, segRate: 1, fadeScheduled: false };
  if (state.stopAt) scheduleSongFadeOut(state.stopAt);
}

function songControl() {
  const s = songState;
  if (!songBuffer || !s || !s.file || s.file.id !== songBufferId || !audioContext || audioContext.state !== 'running') {
    stopSongNode();
    return;
  }
  if (s.revision !== songRevision) {
    songRevision = s.revision;
    const sameRun = songNode && s.playing && songNode.startAt === s.startAt && songNode.positionMs === s.positionMs;
    if (sameRun) { if (s.stopAt) scheduleSongFadeOut(s.stopAt); } else stopSongNode();
  }
  if (!s.playing) { stopSongNode(); return; }

  const t = audioContext.currentTime + 0.05;
  const rel = (serverHeardAtContextTime(t) - s.startAt) / 1000; // segundos desde o início agendado
  const posS = s.positionMs / 1000;
  const stopMedia = s.stopAt ? (s.positionMs + (s.stopAt - s.startAt)) / 1000 : Infinity;
  if (posS + rel >= stopMedia + 0.3 || posS + rel >= songBuffer.duration) { stopSongNode(); return; }

  if (!songNode) {
    if (rel < 0) {
      if (-rel > 1.0) return;               // ainda longe: agenda no próximo ciclo
      beginSongNode(t - rel, posS, s);      // começa exatamente em startAt
    } else {
      beginSongNode(t, posS + rel, s);      // entrou no meio: cai no ponto certo
    }
    return;
  }
  if (t < songNode.segT || rel < 0) return; // ainda não começou a tocar

  const actual = songNode.segP + (t - songNode.segT) * songNode.segRate;
  const error = (posS + rel) - actual;
  if (Math.abs(error) > SONG_JUMP_S) { beginSongNode(t, posS + rel, s); return; }
  const rate = Math.abs(error) < 0.0001 ? 1 : 1 + Math.max(-SONG_MAX_RATE, Math.min(SONG_MAX_RATE, error / 0.5));
  songNode.src.playbackRate.setValueAtTime(rate, t);
  songNode.segP = actual;
  songNode.segT = t;
  songNode.segRate = rate;
}

async function loadSongFile(id) {
  songLoadingId = id;
  songStatus.textContent = 'Baixando o arquivo…';
  try {
    const response = await fetch(`/api/audio/file?id=${id}&group=${encodeURIComponent(songGroup)}`);
    if (!response.ok) throw new Error('download');
    const data = await response.arrayBuffer();
    if (songLoadingId !== id) return;
    songStatus.textContent = 'Preparando o áudio…';
    const decoded = await getAudio().decodeAudioData(data);
    if (songLoadingId !== id) return;
    songBuffer = decoded;
    songBufferId = id;
    songStatus.textContent = 'Pronto.';
  } catch {
    if (songLoadingId === id) songStatus.textContent = 'Não foi possível preparar este arquivo neste navegador. Tente WAV ou MP3.';
  }
}

// Aceita sempre o estado COMPLETO; ignora o que for mais antigo que o último já aplicado
// (o SSE e o polling se cruzam).
function applySongState(next) {
  if (songState && next.revision < songState.revision) return;
  songState = next;
  const fileId = next.file ? next.file.id : 0;
  if (fileId !== songLoadingId) {
    songLoadingId = fileId;
    songBuffer = null;
    songBufferId = 0;
    stopSongNode();
    songNameLabel.textContent = next.file ? next.file.name : '';
    songStatus.textContent = fileId ? '' : 'Aguardando o anfitrião escolher um arquivo.';
    if (fileId) loadSongFile(fileId);
  }
}

// Os comandos chegam por /events/audio assim que o anfitrião muda algo; o polling fica lento,
// só para registrar esta página, atualizar as contagens e recuperar o estado se o SSE cair.
let songEvents = null;
function connectSongEvents() {
  if (songEvents || !window.EventSource) return;
  songEvents = new EventSource(`/events/audio?group=${encodeURIComponent(songGroup)}`);
  songEvents.addEventListener('audio', (event) => {
    try { applySongState(JSON.parse(event.data)); } catch { /* mensagem inválida: ignora */ }
  });
}

async function pollSong() {
  if (!songSupported) return;
  try {
    const asked = songGroup;
    const response = await fetch(`/api/audio?device=${encodeURIComponent(deviceId)}&ready=${songBufferId}&group=${encodeURIComponent(asked)}`);
    if (asked !== songGroup) return; // resposta de um grupo que esta página já deixou
    if (response.status === 404) { // servidor sem suporte (ex.: servidor Node.js)
      songSupported = false;
      songSection.hidden = true;
      return;
    }
    if (!response.ok) return;
    songSection.hidden = false;
    applySongState(await response.json());
    connectSongEvents();
  } catch { /* tenta de novo no próximo ciclo */ }
}

async function songCommand(type, positionMs) {
  if (type === 'play') await getAudio().resume();
  const body = { type };
  if (positionMs !== undefined) body.positionMs = positionMs;
  try {
    await fetch(`/api/audio/cmd?group=${encodeURIComponent(songGroup)}`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
  } catch { songStatus.textContent = 'Não foi possível enviar o comando.'; }
}

function songSeekBy(deltaMs) {
  const current = songPositionMs() || 0;
  const limit = songBuffer ? songBuffer.duration * 1000 : Infinity;
  songCommand('seek', Math.max(0, Math.min(limit, current + deltaMs)));
}

function initSong() {
  document.querySelector('#song-enable').addEventListener('click', async () => {
    await getAudio().resume();
    songStatus.textContent = 'Áudio ativado.';
  });
  document.querySelector('#song-play').addEventListener('click', () => songCommand('play'));
  document.querySelector('#song-pause').addEventListener('click', () => songCommand('pause'));
  document.querySelector('#song-stop').addEventListener('click', () => songCommand('stop'));
  document.querySelector('#song-start').addEventListener('click', () => songCommand('seek', 0));
  document.querySelector('#song-back').addEventListener('click', () => songSeekBy(-10000));
  document.querySelector('#song-forward').addEventListener('click', () => songSeekBy(10000));
  const poll = async () => { await pollSong(); setTimeout(poll, 1500); };
  poll();
  setInterval(songControl, 100);
  setInterval(() => {
    const position = songPositionMs();
    songPosition.textContent = position === null ? '—'
      : formatSongTime(position) + (songBuffer ? ` / ${formatSongTime(songBuffer.duration * 1000)}` : '');
  }, 250);
}

initSong();


// Controle de volume desta página e leitura do volume definido pelo anfitrião.
const volumeSlider = document.getElementById('volume');
if (volumeSlider) {
  volumeSlider.value = String(Math.round(ownVolume * 100));
  volumeSlider.addEventListener('input', () => {
    ownVolume = Math.max(0, Math.min(1, Number(volumeSlider.value) / 100));
    try { localStorage.setItem('metronomo.ownVolume', String(ownVolume)); } catch { /* sem armazenamento */ }
    applyVolume();
  });
}
applyVolume();
setInterval(pollMix, 1000);
pollMix();
