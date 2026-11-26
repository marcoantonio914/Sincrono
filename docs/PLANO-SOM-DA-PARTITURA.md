# Plano: som gerado a partir da partitura (Síncrono)

Objetivo: tocar a partitura MusicXML como áudio (sem gravação), sincronizada em todos os aparelhos, por grupo/voz,
com o cursor, o metrônomo e o andamento ajustável. Útil para coro: ouvir só o naipe do contralto, por exemplo.

## 1. Visão geral
1. Extrair eventos de nota (início, duração, altura, voz/parte, dinâmica) do mesmo `playOrder` que já resolve repetições,
   casas 1/2 e `times="4"` (`score.js`). O tempo de cada compasso já existe (`order[k].ms0/ms1`).
2. Renderizar para um buffer de áudio (WAV) com `OfflineAudioContext` + sintetizador de soundfont.
3. Entregar esse WAV como se fosse um áudio carregado: o resto do sistema (grupos, sincronia, mixer, marcadores) já funciona.
4. Marcadores de compasso gerados automaticamente de `order[k].ms0` (offset 0) → cursor exato, sem "Marcar compassos".

## 2. Etapas
| # | Etapa | Resultado | Esforço |
|---|-------|-----------|---------|
| 1 | Extração de eventos em `score.js` (`sc.notes(partFilter)`): ties, acordes, ornamentos básicos, grace notes ignoradas, transposição | lista `{ms, dur, midi, vel, part, voice}` | 1–2 dias |
| 2 | Sintetizador mínimo (sem soundfont): oscilador + envelope, para validar tempo e altura | WAV de teste, onset/pitch automáticos | 0,5 dia |
| 3 | Soundfont pequeno (piano + coro "ooh/aah" + cordas, ≤ 5–10 MB) via SpessaSynth ou WebAudioFont | timbre decente offline | 2 dias |
| 4 | Render por grupo/parte: escolhe quais partes entram (ex.: só alto, ou tudo menos soprano) e volume por parte | 1 WAV por grupo | 1 dia |
| 5 | Re-render ao mudar BPM (ou usar o esticador WSOLA já existente — render em andamento escrito e esticar) | andamento sem pulo | 1 dia |
| 6 | Marcadores automáticos + botão "Gerar som da partitura" (anfitrião) | cursor perfeito | 0,5 dia |
| 7 | App Android: renderizar na WebView oculta (mesma página) e enviar o WAV ao `AudioHub` | funciona no celular anfitrião | 1–2 dias |
| 8 | Testes e ajuste fino (latência, loops, partituras grandes) | suíte verde | 1–2 dias |

Total estimado: ~8–11 dias de trabalho.

## 3. Decisões de projeto
- **Render offline, não ao vivo**: o resultado é um arquivo; entra no mesmo caminho de sincronia dos áudios (sem novo risco de relógio).
- **Andamento**: render no BPM escrito e usar o esticador (mantém o tom). Alternativa: re-render no novo BPM (melhor qualidade, mais lento).
- **Timbre**: soundfont GM leve. Verificar a licença de cada soundfont antes de embutir (GeneralUser GS e similares têm licenças próprias; confirmar redistribuição dentro de APK). Voz de coro sintetizada soa artificial: oferecer também piano.
- **Tamanho**: soundfont embutido aumenta o APK; opção de baixar na primeira vez (o app funciona em rede local, então o anfitrião pode servir o arquivo).
- **Onde renderizar**: sempre no anfitrião (PC no navegador, ou celular na WebView). Clientes só recebem o WAV.

## 4. Testes
- Duração do WAV = `totalMs` da partitura (± 20 ms).
- Onsets: cada nota tem energia no instante esperado (detecção de ataque).
- Altura: FFT do trecho de cada nota confere com a frequência MIDI (± 20 cents).
- Repetições / casas / `times="4"`: número de compassos tocados = `order.length`.
- Filtro de partes: render só do alto não tem energia fora da faixa do alto.
- Integração: gerar → tocar em 2 clientes → cursor acompanha sem marcação manual.

## 5. Riscos
- Qualidade do coro sintetizado (expectativa). Mitigação: oferecer piano e "dobrar a nota guia".
- Partituras com muitas partes e render lento no celular. Mitigação: render por parte sob demanda, barra de progresso.
- Memória: WAV estéreo 44,1 kHz de 5 min ≈ 50 MB. Usar mono / 22–32 kHz ou 16 bits.
- MusicXML com recursos raros (tremolo, arpejo, cadência): tocar como escrito, ignorar o resto e avisar.

## 6. O que preciso que você decida
1. Timbre principal: piano, cordas ou "ooh" de coro?
2. Quer ouvir uma parte isolada (ex.: só o alto) além da mistura?
3. Pode embutir soundfont no APK (≈ 5–10 MB) ou prefere baixar depois?
4. Prioridade: antes ou depois da próxima rodada de testes de campo?

## 7. Fontes a conferir
- SpessaSynth (sintetizador SoundFont em JS) — verificar licença e tamanho.
- WebAudioFont (instrumentos prontos para Web Audio).
- Licenças de soundfonts (ex.: GeneralUser GS) antes de redistribuir.
