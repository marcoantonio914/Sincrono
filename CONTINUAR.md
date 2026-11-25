# CONTINUAR.md — leia isto primeiro (para qualquer agente de IA ou pessoa que continue o projeto)

> **Regra de manutenção:** a cada versão nova, ESTE arquivo deve ser atualizado (seções 3, 8, 9 e o histórico da seção 10) e incluído no zip e no commit. Se você é um agente: antes de terminar o trabalho, atualize-o. Escreva só o que é verdade; separe sempre **testado de verdade** de **não testado**.

## 1. Quem é o usuário e como ele trabalha

- Marco Antonio (GitHub: `marcoantonio914`, repositório `Sincrono`). Fala português do Brasil; escreva tudo em português, simples e direto. Usa Ubuntu no computador, celulares Android de marcas e modelos variados (ex.: um Galaxy A07), e uma interface de áudio USB Teyun Q-12 nos testes.
- **Regra permanente: não modificar o que já funciona.** Toda evolução vai para uma **pasta/zip novos** (`sincrono-v8` → `v9` → … → `v12`); as versões anteriores ficam intactas. Cada versão vira um commit no git.
- Seja honesto sobre o que foi testado. O Kotlin **nunca foi compilado no ambiente do agente** (não há kotlinc/Gradle aqui); quem compila é o usuário (`python build_apk.py`) e devolve os erros.
- O usuário já expôs um token do GitHub no chat uma vez (aconselhado a revogar). Nunca grave tokens em arquivos. `publicar_github.py` pede o token na hora.

## 2. O que é o projeto

App Android nativo (Kotlin/Compose) de **metrônomo e áudio sincronizados entre aparelhos**, mais um **servidor para o computador** (Python) e uma **página web** que faz o papel de cliente/anfitrião no navegador.

Ideia central: **nada é transmitido ao vivo.** O anfitrião só manda comandos ("comece em tal instante do relógio do servidor"). Cada aparelho baixa o arquivo inteiro uma vez e o toca localmente, calculando a posição pelo relógio compartilhado (sincronia estilo NTP, erro ~1–2 ms). Rede lenta não faz falha de som. O que sobra é a **latência de saída de áudio de cada aparelho**, resolvida pela calibração.

## 3a. v13 — PARTITURA NA TELA (feita; Android ainda não compilado; leia isto antes da seção 3)

Pedido do usuário: partitura escrita no MuseScore e exportada em **MusicXML**; cada músico vê **só a sua parte**; o **cursor segue o metrônomo** (e o áudio exportado do MuseScore); a partitura pode ter repetições, mudanças de andamento, "de tudo". Decisão de projeto: **motor próprio, sem bibliotecas externas** (funciona offline na WebView; OSMD/Verovio não puderam ser baixados). Uma **única partitura por sessão** (arquivo completo, todas as partes); **cada grupo escolhe qual parte vê** e cada músico pode trocar a sua no próprio aparelho.

| Etapa | Situação |
|---|---|
| Leitor MusicXML/MXL `web/score.js` (partes, ordem real de execução com repetições/casas/da capo/segno/coda/fine, mapa de andamento, `locate(ms)`) | **FEITO e testado** (`python3 tools/teste_partitura_leitor.py`, 10 verificações em Chromium) |
| Desenho SVG + cursor `web/score-view.js` (`SincronoScore.ScoreView`): claves G/F/C, armadura, fórmula, notas/acordes/pausas, hastes, barras, bandeirolas, ligaduras, tercinas (só o número), letra, dinâmicas, andamento, repetições e casas, piano em 2 pautas; modos "uma linha rolando" e "páginas"; rolagem acompanha o cursor e pausa 4 s quando o usuário mexe | **FEITO e testado** (`tools/teste_partitura_desenho.py`, 20 verificações; capturas olhadas à mão com `tools/captura_partitura.py`). **Não desenha:** notas de adorno, 8va, pedal, crescendo (vira o texto "cresc."), vários versos de letra, vozes cruzadas complexas. A clave de sol é um desenho aproximado. Só foi testado com partituras de exemplo geradas por `tools/gerar_exemplos_partitura.py` — **falta testar com partituras reais do MuseScore do usuário.** |
| Servidor do PC: `/api/score`, `/api/score/file?id=`, `POST /api/score/upload?name=`, `/api/score/clear`, `/api/score/part {group, part}` | **FEITO e testado** (`servidor-pc/teste_partitura.py`, 15 verificações) |
| Página (anfitrião e cliente) `web/score-panel.js` + seção `#score` em `index.html`: importar, escolher parte por grupo, "minha parte", modo, zoom, botão "Metrônomo = partitura", cursor | **FEITO e testado** (`tools/teste_partitura_pagina.py`: servidor real + Chromium, 13 verificações, inclui o cursor voltando ao compasso 1 depois da repetição) |
| Android (Kotlin): `SessionServer.kt` (`/api/score`, `/api/score/file`, `/api/score/part`, `/api/score/clear`; partitura na memória), `SessionController.importScore` e `scoreTick()`, `ui/ScoreView.kt` (WebView que abre `web/score.html`; aba **PARTITURA** do anfitrião com botão "Importar partitura" e cartão **Partitura** na tela do cliente) | **ESCRITO, NÃO COMPILADO NEM TESTADO EM APARELHO.** A página que a WebView abre (`score.html`) foi testada com Chromium simulando o que o app entrega a cada 50 ms (`python3 tools/teste_partitura_app.py`, 10 verificações). O app entrega à página: ms desde o início do metrônomo, posição do áudio, bpm e grupo (`window.sincronoTick`); a WebView não toca som nem registra aparelho no servidor. Riscos para o primeiro build: erros de compilação em `ScoreView.kt`/`SessionServer.kt`/`SessionController.kt`; WebView dentro de coluna rolável (gesto vertical); seletor de arquivos (`*/*`) para `.musicxml`/`.mxl`. |

**Escolha da parte (decisão do usuário, v13 final):** igual ao áudio por grupo da v10 — o anfitrião cria os grupos e move os aparelhos entre eles (como já era) e, **no cartão de cada grupo**, escolhe a "Parte da partitura" (PC e app: aba **MÚSICA**, antiga ÁUDIO; no app é o `GroupRow`). **Áudio e partitura do grupo são uma coisa só**: a importação da partitura (`#score-admin` / linha "Partitura:" do `GroupsPanel`), o arquivo de áudio e a parte ficam no mesmo painel de grupos; o visualizador da partitura fica na aba SYNC (anfitrião e cliente) e só aparece se há partitura (`SessionController.scoreFile`, consultado a cada 1 s). Não existe mais a aba PARTITURA. A página dos clientes consulta `/api/score` a cada 1 s e redesenha sozinha (também ao trocar de grupo). Não há mais lista separada de partes por grupo na seção da partitura. No Android, os nomes das partes vêm de `net/ScoreParts.kt` (lê o `part-list` do MusicXML/MXL; a ordem é a do `part-list`, o JS usa a ordem dos `<part>`: no MuseScore são iguais).

**Cursor:** prioridade áudio tocando > metrônomo > áudio pausado > início. Com o metrônomo, o tempo da partitura = `(agora − startAt) × (bpm da sessão ÷ bpm inicial da partitura)`; ou seja, o metrônomo marca um andamento fixo e o cursor segue o mapa de andamento da partitura nesse ritmo (mudança de andamento no meio não muda o metrônomo). Com áudio exportado do MuseScore o tempo coincide, sem marcações. **Áudio gravado por pessoas (andamento livre) precisa de marcadores compasso→segundo: NÃO feito (v14).**

## 3. Estado atual (v12) — o que foi pedido, feito e verificado

Pedidos do usuário na v12 e situação:

| Pedido | Situação |
|---|---|
| Botão **parar** além de tocar e pausar; todos param e voltam ao início juntos | Feito. "Parar" agenda a parada num instante combinado (como a pausa) e a posição volta a 0. Lógica testada de verdade no servidor Python (`servidor-pc/teste_transporte.py`). **Kotlin (`AudioHub.kt`) espelha a mesma lógica, não compilado/testado.** |
| Conferir se **pausar** e **voltar a tocar** são simultâneos em todos | Conferido por leitura do código e por teste: pausa = `stopAt = agora + 1,2 s`; tocar = `startAt = agora + 2,5 s`; "todos os grupos" usa o **mesmo `agora`** para todos. Cada aparelho para/começa no quadro exato pelo relógio compartilhado. Só falha se um aparelho receber o comando depois do instante marcado (rede com mais de ~1,2 s de atraso). |
| Botões com **ícones** (tocar, pausar, parar, início, ±10 s) em vez de texto | Feito no app (ícones desenhados em `ui/Transport.kt`) e na página web (SVG). |
| Metrônomo: só iniciar e parar | O metrônomo não tem "posição": pausar e parar eram a mesma coisa. Agora há ▶ (inicia; se já tocando, recomeça) e ⏹. A parada do metrônomo vale assim que cada aparelho recebe a atualização (não é agendada como a do áudio). |
| Só o **anfitrião** tem todas as opções; o **cliente** vê o básico (autocalibrar, metrônomo e áudio só para acompanhar) | Feito no app (`GuestScreen` em `MetronomoApp.kt`) e na página web (classe `guest`; no servidor do PC o anfitrião é a página aberta no próprio computador). O cliente não tem mais: ajuste fino manual, mixer, rede/qualidade, convite, botão "Calibrar todos". |
| **Apelido** do aparelho (cliente) visível para o anfitrião | Feito: app (`NicknameModule`, `SessionController.setNickname`) e web (`#nickname`). O nome vai na consulta de calibração (`/api/calib?...&name=`) e aparece nas listas de aparelhos/grupos/mixer. Web: testado em navegador real simulado (Playwright). App: não compilado. |
| Anfitrião **renomeia grupos** | Feito no app (botão "Renomear" em cada grupo, inclusive o padrão) e na web. O servidor já tinha a operação `rename`. |
| **Tirar a calibração por cabo** | Retirada da interface do app (chave "cabo", opções avançadas) e da página web (painel "Calibrar todos" do computador). O código legado (`CalibrationHub` modo cabo, `servidor-pc/calibracao_cabo.py`, `CalibrationDsp` com `cable`) **continua no repositório**, sem acesso pela interface. `calibracao_cabo.py` ainda é usado como registro de aparelhos (`/api/calib` lista os aparelhos que o PC vê). Remover de vez exige trocar esse registro; ver pendências. |
| Estudo de viabilidade de **partitura na tela** (estilo MuseScore) | Estudo em `docs/PARTITURA.md` (resultado resumido na seção 9). Nada implementado. |

Verificado de verdade nesta versão: `servidor-pc/teste_transporte.py` (12 verificações do transporte), `servidor-pc/teste_servidor.py`, `servidor-pc/teste_calibracao_cabo.py`, `tools/*Test.java` (inclui `SelfCalTest`), e um teste de navegador (Playwright) das páginas de anfitrião e de cliente. **Não verificado:** tudo em Kotlin (telas, `AudioHub.kt`, apelido, ícones) e qualquer coisa em aparelho real além do que o usuário já testou na v11.

O que o usuário **já validou em aparelhos reais (v11):** áudio sincronizado e a **autocalibração** (também com fone de ouvido) "funcionou muito bem".

## 4. Mapa do código

```
app/src/main/java/com/metronomo/
  SessionController.kt      estado do app, papéis (HOST/GUEST), prefs, apelido, comandos de áudio/metrônomo, calibração
  net/SessionServer.kt      servidor HTTP no celular anfitrião (metrônomo, áudio, grupos, mix, calib, arquivos web)
  net/SessionClient.kt, ClockSync.kt, MixClient.kt, Discovery.kt (NSD/mDNS _metronomo._tcp.), GroupsModel.kt
  audio/AudioHub.kt         estado do áudio de UM grupo no anfitrião (play/pause/seek/stop agendados)
  audio/AudioPlayback.kt    cliente do áudio: baixa, decodifica, entrega o plano ao player; posição para a tela
  audio/AudioFilePlayer.kt  toca o arquivo no relógio do servidor (AudioTrack + AudioTimestamp + correção gradual)
  audio/ClickEngine.kt      metrônomo; AudioDecoder.kt; Mix.kt; OutputRoute.kt (alto-falante/fone/USB/Bluetooth)
  calibration/SelfCalibrator.kt + SelfCalibrationDsp.java   AUTOCALIBRAÇÃO (v11)
  calibration/CalibrationHub.kt, CalibrationManager.kt, CalibrationDsp.java, CalibrationPlayer/Recorder.kt  calibração em conjunto (microfone)
  diag/*.java               Groups, MixLevels, SyncMath, AudioClockTracker, GainRamp (Java puro, testado em tools/)
  ui/MetronomoApp.kt        telas: StartScreen, SessionScreen (anfitrião, 4 abas), GuestScreen (cliente)
  ui/Transport.kt           ícones e botões de transporte; ui/Components.kt, Theme.kt, Qr.kt, AdBanner.kt
app/src/main/assets/web/    página web (index.html, app.js, host.js, style.css, qr.js); servida pelo celular e pelo servidor do PC
servidor-pc/                servidor Python para o computador (sincrono_server.py) + testes + simulador + diagnóstico de áudio
tools/                      testes em Java puro (javac + java), ver README
build_apk.py                gera APK/AAB (baixa JDK, Gradle e SDK sozinho); chave de upload em ~/.sincrono-keystore (FORA do projeto)
publicar_github.py          envia ao GitHub (pede o token; não o grava)
```

## 5. Protocolo (resumo)

Servidor HTTP simples (celular ou PC), mesmo formato nos dois:
- `GET /api/state`, `POST /api/command` (`play|pause|restart|settings`), `GET /events` (SSE, evento `state`) — metrônomo; `startAt = agora_do_servidor + LEAD`; batida n = `startAt + n·60000/bpm`.
- `POST /api/sync` — troca de relógio (NTP-like).
- `GET /api/audio?device=ID&ready=ID&group=G`, `POST /api/audio/cmd?group=G|all` com `{"type":"play|pause|seek|stop","positionMs":N}`, `GET /api/audio/file?id=N&group=G`, `GET /events/audio?group=G` (SSE, evento `audio`). O estado traz `startAt`, `positionMs`, `stopAt`, `resetOnStop` (v12: `stop` agenda `stopAt` e, depois dele, a posição volta a 0).
- `GET|POST /api/groups` (`add|rename|remove|assign`), `GET|POST /api/mix`, `GET /api/devices` (PC), `/api/calib*` (calibração em conjunto e registro de aparelhos; recebe `name` = apelido).
- Constantes: `LEAD_MS` do áudio 2500 ms, `STOP_MS` 1200 ms (Kotlin `AudioHub`, Python `AUDIO_LEAD_MS`/`AUDIO_STOP_MS`).

## 6. Conceitos que não podem ser esquecidos

- **Convenção do ajuste de calibração:** positivo = o aparelho soa depois do planejado = deve **tocar mais cedo**.
- **Autocalibração (absoluta):** o aparelho toca 8 sons e o próprio microfone os grava; erro = horário gravado − horário combinado, usando o **carimbo do microfone** (`AudioRecord.getTimestamp`) que já inclui o atraso de entrada. Sem carimbo → não aplica. Guardada com `ref="self"`, vale com qualquer anfitrião; o anfitrião também se autocalibra e os outros se alinham ao som dele (o hub soma o `autoMs` do anfitrião às calibrações em conjunto).
- **Limite conhecido:** os carimbos do Android podem informar menos que a realidade (medido no celular do usuário: ida e volta real ~64 ms contra ~44 ms informados, sem saber se é entrada ou saída). Idéia aberta (v12→): em vez de uma média global do início da gravação, usar o carimbo **mais próximo de cada som** para absorver deriva de relógio (as taxas de entrada/saída observadas diferem de 48000).
- Alto-falante e fone têm calibrações separadas (por tipo de saída).
- Chrome/Edge do servidor do PC é aberto com `--autoplay-policy=no-user-gesture-required` num perfil próprio (`~/.sincrono-browser`) para tocar sem clique.
- Ubuntu: Python em venv (`~/sincrono-venv`), `libportaudio2`; PipeWire pode travar a interface USB.

## 7. Decisões do usuário (não reverter sem perguntar)

- Calibração por cabo/mesa: **retirada** (a autocalibração com o próprio fone resolveu).
- **Multitrack/stems:** adiado ("Esquece essa parte... Por enquanto").
- **REPEATS 5→8** e confiança tolerante a outliers na calibração por microfone: adiado.
- Ideias citadas e adiadas: receptor ESP32, fones sem fio/Bluetooth (verificação de estabilidade), iPhone.
- O cliente só vê o básico; o anfitrião vê tudo. (Não colocar controles de transporte no cliente.)

## 8. Como compilar, testar e publicar

- Compilar: `python build_apk.py --no-aab` na pasta do projeto (gera `sincrono-debug.apk`). Erros de Kotlin voltam para o agente corrigir.
- Testes sem Android (Java puro): ver o README (`javac` + `java -cp ... SyncTest AudioSyncTest DspTest SelfCalTest GainTest GroupsTest`).
- Servidor do PC: `cd servidor-pc && python3 sincrono_server.py` (no Ubuntu, dentro do venv); testes: `teste_servidor.py`, `teste_transporte.py`, `teste_calibracao_cabo.py`.
- Publicar: `python3 publicar_github.py` (cria/atualiza `origin` e pede o token na hora). Cada versão = um commit com os trailers `Co-Authored-By` e `Claude-Session` conforme a sessão.

## 9a. v14 — "Tocar todos" com Metrônomo e/ou Áudio (feita; Kotlin NÃO compilado aqui) + achados do K284

- Pedido do usuário: partitura+metrônomo sem áudio, com a opção de o áudio sair junto ou não. Na aba MÚSICA, junto de "Todos os grupos", há duas caixas **Metrônomo** e **Áudio** (padrão: só Áudio). Tocar/Pausar/Parar agem no que está marcado. O cursor já seguia o metrônomo (prioridade: áudio tocando > metrônomo); o que faltava era um botão que o disparasse ali.
- Protocolo: `startAt` OPCIONAL (ms do servidor) em `POST /api/command` (play/settings) e em `POST /api/audio/cmd` (play). Só vale entre agora−0,5 s e agora+15 s; senão usa o padrão (3000 / 2500 ms). Quem inicia escolhe `startAt = agora+3000` e manda o mesmo aos dois: começam juntos. Se o áudio retoma no meio, o 1º clique cai na próxima batida alinhada com o arquivo (o acento do compasso não coincide com a barra da partitura nesse caso). Código: `_wanted_start` (Python), `AudioHub.wantedStart` e `SessionServer.handleCommand` (Kotlin), `SessionController.playTogether/pauseTogether`, `GroupsPanel` (caixas), `host.js` (`play-all`, `pauseAll`). Teste: `tools/teste_tocar_junto.py` (passa). O andamento do clique é o da aba SYNC (o app ainda não lê o andamento da partitura; na página existe o botão "Metrônomo = partitura").
- **Partitura real K284-2.xml (Mozart, piano, 92 compassos, MuseScore 3) — o desenho NÃO está correto; achados ainda NÃO corrigidos:**
  1. `<attributes><clef>` no MEIO do compasso: `parsePart` trata como estado do início do compasso (`m.clefs` é sobrescrito), então a pauta inteira usa a última clave (ex.: pauta de baixo em clave de sol). Precisa guardar trocas de clave com posição `q` por pauta e usar a clave vigente em cada nota; desenhar a clave pequena no ponto.
  2. Dinâmicas ignoram `<staff>` da `<direction>`: todas caem abaixo da pauta 1 (duplicadas "p p", "f f"); devem ficar abaixo da pauta indicada.
  3. Não desenhados: cifras (`<harmony>`, 231), ornamentos (trill-mark, inverted-mordent, turn), apojaturas (`<grace>`, 15), fermata só aproximada, staccato só em parte. Ligaduras/hastes/barras de vozes 2+ no piano merecem revisão (vozes 5 em diante na pauta 2).
  4. Detectar fórmula de compasso e andamento: o leitor já expõe `initialBpm`/`initialTime`; falta o app Android usá-los (hoje só a página, botão "Metrônomo = partitura").
  5. Tocar as notas (síntese) — viabilidade respondida ao usuário só em conversa: dá, com um sintetizador simples no próprio aparelho agendado pelo relógio compartilhado; NÃO implementado.

## 9b. v16 — desenho de orquestra, andamento automático no Android, linha do tempo, modo palco (Kotlin NÃO compilado aqui)
- **Desenho (score.js / score-view.js):** claves de sol, 8vb, fá, dó (alto/tenor), percussão, troca de clave no início E no meio do compasso; armaduras corrigidas por clave (antes a de sustenidos em sol estava errada); dinâmicas por pauta; ornamentos (trinado, mordente, grupeto, tremolo), articulações, arcadas, harmônicos, arpejo, apojaturas, fermata, pedal, 8va, cifras e análise romana, vários versos de letra, harpa (2 pautas) e órgão (3). Gerador de exemplo: `tools/gerar_exemplo_orquestra.py`; teste: `tools/teste_partitura_orquestra.py` (30 checagens) com `docs/exemplos-partitura/K284-2.xml`.
  - Achado nº 1 do K284 corrigido: as trocas de clave dele são no início do compasso (já estavam certas); o que faltava eram dinâmicas por pauta, ornamentos, apojaturas, cifras e fermata.
  - Limites conhecidos: inclinação das barras de colcheias, barras entre pautas do piano, compassos de pausa múltiplos, colisões automáticas, 8va entre sistemas aproximado.
- **Andamento/compasso automáticos no Android:** `ScoreParts.meta()` lê o bpm (sound tempo > metronome > palavra italiana, esta só estimada, marcada "~") e o numerador; ao importar, aplica sozinho se o metrônomo está parado e o andamento é numérico; botão "Metrônomo = partitura".
- **Linha do tempo do anfitrião:** clique num compasso ou arraste `#score-seek` (só anfitrião; `?host=1` no app). `seekTo(ms)` em `score-panel.js`: áudio `seek` com `startAt`, metrônomo `play` com `startAt` no passado (servidor aceita até 1 h) para manter a contagem, ou `POST /api/score/pos {ms}` se nada toca; `/api/score` devolve `pos`. Cursor: áudio tocando > metrônomo > `pos` > áudio pausado > 0. No app, `scoreTick()` agora envia `now` e `beats`.
- **Modo palco:** botão `#score-stage` (web, tela cheia + Wake Lock + páginas com fonte maior) e "Modo palco" no cartão do app (Dialog em tela cheia, `keepScreenOn`). Virada de página automática = modo páginas seguindo o cursor.
- **Testes:** `teste_linha_do_tempo.py` novo; `teste_tocar_junto.py` (startAt absurdo agora = 2 h atrás) e `teste_partitura_app.py` ajustados. Todos passam. Kotlin (ScoreView.kt, SessionController.kt, ScoreParts.kt etc.) NÃO foi compilado: compile no PC.
- **Ideia pendente (resposta dada ao usuário):** marcadores de compasso para áudio gravado (anfitrião bate na tela no início de cada compasso; tabela compasso→ms por arquivo). Som de piano (SoundFont + FluidSynth no PC) adiado.

## 9c. v17 — marcadores de compasso (timestamp) para áudio gravado (Kotlin NÃO compilado aqui)
- **Ideia:** o anfitrião toca o áudio e toca em COMPASSO (ou Espaço/Enter) no início de cada compasso; fica uma tabela `marks[k]` = ms do ÁUDIO em que começa o k-ésimo compasso tocado (`score.order[k]`, com repetições). O cursor passa a seguir o áudio compasso a compasso, mesmo com andamento humano.
- **Servidor (Python e Kotlin):** `/api/score` devolve `marks: {nomeDoAudio: {marks:[ms], offset}}`; `POST /api/score/marks {sig, marks|null, offset}` (valida ordem crescente, ≤20000, |offset|≤5 s); apagados ao trocar/limpar a partitura. A chave é o NOME do arquivo de áudio (o app só conhece o nome: `audioName` no `scoreTick`).
- **Cliente (score.js / score-panel.js):** `score.fromAudio(marks, offset, audioMs)` e `score.toAudio(marks, offset, scoreMs)` (interpolação linear dentro do compasso; depois do último marcador segue o andamento escrito). `scoreMs()` usa isso quando há marcadores; `seekTo` (linha do tempo) converte partitura→áudio. Controles só do anfitrião: "Marcar compassos", barra COMPASSO/Desfazer/Salvar/Cancelar, "Atraso do toque (ms)" (padrão 120; salvo como offset negativo), "Apagar marcadores".
- **Testes:** `tools/teste_marcadores.py` (matemática, servidor, fluxo, cliente sem controles). Todos os testes anteriores continuam passando.
- **Limites / não feito:** detecção automática de batidas (não implementada; seria menos confiável); marcar a partir do meio da música; ajuste fino depois de salvo (hoje se refaz); o fluxo real de toque humano só foi testado com cliques automáticos, não com precisão de palco.
- **Próximo (acordado com o usuário):** v18 = REPERTÓRIO/playlist: lista de músicas, cada uma com áudio (por grupo), partitura, parte de cada grupo, andamento/compasso e marcadores; botão "Próxima"; exportar/importar; trocar só com a música parada. Som de piano (SoundFont + FluidSynth) segue adiado.

## 9d. v18 — REPERTÓRIO (playlist) (Kotlin NÃO compilado aqui)
- **Modelo:** "foto" da sessão. O anfitrião prepara uma música (partitura, áudio de cada grupo, parte de cada grupo, andamento/compasso, marcadores) e salva com "Salvar a configuração atual como música"; carregar depois restaura tudo de uma vez para todos. Os arquivos são COPIADOS para `<cache>/repertorio/song-N/` (PC: `~/.sincrono-server/repertorio/`; Android: `filesDir/repertorio/`) com índice `repertorio.json`, então sobrevive a reinícios.
- **Grupos pelo NOME:** áudio e partes são guardados por nome de grupo; ao carregar, grupos que não existem mais são recriados; grupos que a música não usa ficam sem áudio.
- **API (igual no PC e no Android):** `GET /api/setlist` → `{songs:[{id,name,score,audios,bpm,beats,marks}], current}`; `POST /api/setlist {op: add|update|rename|remove|move|apply|next|prev, id, name, dir}`. Trocar com algo tocando → 409 "Pare a música e o metrônomo...". Carregar também aplica andamento/compasso (comando `settings`) e limpa `pos`.
- **PC/web:** `servidor-pc/sincrono_server.py` (`rep_op`, `_rep_snapshot`, `_rep_apply`), `web/repertorio.js` + seção `#setlist` na aba MÚSICA (só anfitrião). **Android:** `net/Setlist.kt`, rota em `SessionServer`, `ui/SetlistCard.kt` na aba Música do anfitrião; `SessionController.setlistJson/setlistOp`.
- **Testes:** `tools/teste_repertorio.py` (salvar, carregar, grupos recriados, 409, próxima/anterior, mover, renomear, remover, persistência após reiniciar o servidor, página). Todos os outros passam.
- **Limites / não feito:** exportar/importar repertório como arquivo; trocar música com tudo tocando é recusado (de propósito); "Próxima" não toca sozinha (só carrega); o Kotlin (Setlist.kt, SetlistCard.kt, mudanças em SessionServer/SessionController/AudioHub) NÃO foi compilado.
- **Adiado:** som de piano (SoundFont + FluidSynth no PC).

## 9e. v19 — aba PLAYLIST separada (visual de playlist de áudio) (Kotlin NÃO compilado aqui)
- Pedido do usuário: o repertório da v18 "não estava legal"; ele quer uma aba separada, igual a uma playlist de áudio, e a aba MÚSICA só para configuração.
- **Web:** nova aba `PLAYLIST` (ordem: SYNC, MÚSICA, PLAYLIST, MIXER, REDE; `host.js` `tabs`, `#tabs` com 5 colunas). Cartão escuro "CARREGADA AGORA" (nome + chips), controles redondos (anterior, tocar, parar, próxima: `#pl-play`/`#pl-stop` clicam em `#play-all`/`#stop-all`, então usam as caixas Metrônomo/Áudio da aba MÚSICA), lista numerada com chips (partitura, N áudios, bpm, marcadores), clicar na linha carrega, menu "⋯" (subir, descer, atualizar com o atual, renomear, remover), "+ Adicionar a música atual". `repertorio.js` reescrito (render); a seção `#setlist` saiu da aba MÚSICA (que ganhou só uma dica).
- **Android:** `TAB_NAMES` com PLAYLIST (índices: 0 SYNC, 1 MÚSICA, 2 PLAYLIST, 3 MIXER, 4 REDE); `ui/SetlistCard.kt` refeito no mesmo estilo (Compose; ▶ chama `playTogether(false, true)`, ■ `pauseTogether(true)`). Backend (API/Setlist.kt) não mudou.
- **Testes:** `teste_repertorio.py` atualizado (aba playlist, cartão, menu ⋯; tira captura em scratchpad). Todos passam. Kotlin NÃO compilado.

## 9f. v20 — fim da música volta ao 0:00, botão de pausa, controles redondos em todas as abas, renomear no app (Kotlin NÃO compilado aqui)
- **Pedido:** (1) quando a música acabar, tudo volta ao 0:00 sem clicar em parar; (2) botão de pausa na playlist; (3) controles centralizados (no print do usuário o TOCAR não ficava no meio: 1 botão à esquerda, 2 à direita); (4) abas SYNC e MÚSICA com os mesmos botões redondos da playlist; (5) renomear música (no app).
- **Fim da música:** o ANFITRIÃO detecta (posição do áudio dele ≥ duração) e faz o mesmo que "Parar todos" (áudio `stop` + metrônomo `pause` depois de 1,2 s). Web: `host.js` (intervalo de 250 ms, `endHandled`), usa `songBuffer.duration`. Android: laço de 250 ms em `SessionController.startSession` (só `Role.HOST`; usa `audioPlayback.positionMs()` e `_audio.value.durationMs`; reaciona quando sai do fim). Só vale para o áudio do grupo do próprio anfitrião; só metrônomo nunca "acaba". Se o anfitrião não tiver áudio no próprio grupo, não há parada automática.
- **Playlist:** controles = anterior, PAUSAR, TOCAR (centro), parar, próxima (`#pl-pause` clica em `#pause-all`). Android: mesmos 5 botões.
- **Botões redondos:** web `style.css` (final): `.controls`, `section.controls`, `.row.icons` ficam redondos (52 px, primário 64 px) e centralizados; Android: `TransportButton` (Transport.kt) agora é CircleShape de tamanho fixo (pílula só com `label`, ex.: "10"); as linhas de transporte usam `Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally)`.
- **Renomear no app:** menu "⋯" da playlist → "Renomear" → AlertDialog (`op: rename`). A página já tinha.
- **Testes:** `tools/teste_fim_da_musica.py` (música de 4 s com metrônomo: para sozinha e volta a 0; pausa mantém a posição; tocar centralizado; botões redondos). Todos os testes passam. Kotlin NÃO compilado.

## 9g. v21 — ícone, metrônomo no visual da playlist, Tocar único na playlist, configuração que não some, modo palco à vista (Kotlin NÃO compilado aqui)
- **Ícone do app:** as 5 barras de som da tela inicial. `res/drawable/ic_launcher_foreground.xml` (+ `_monochrome` para ícones temáticos), `res/mipmap-anydpi-v26/ic_launcher(.round).xml` (adaptativo), cor de fundo `#111827` em `values/ic_launcher_background.xml`, referenciado no `AndroidManifest.xml` (`icon`/`roundIcon`). Página: `web/icon.svg` como favicon. minSdk >= 26 (sem PNGs legados). Se preferir fundo claro com barras escuras, troque a cor e o `strokeColor`.
- **Metrônomo no visual da playlist:** cartão escuro, pontos dos tempos, BPM grande, botões redondos (−5 −1 +1 +5), tempos por compasso com − e +, play branco/stop cinza. Android: `SyncTab` + `MetronomeViewModule` (convidado, sem botões) + `StepRow`/`RoundStep` redondos também no ajuste fino de latência; `TransportButton(onDark = true)`. Web: `.met-card` em `style.css` (cuidado: `#play` tem regra de ID; a do cartão usa `#play` também).
- **Playlist assume o Tocar:** saiu o bloco "Todos os grupos" (caixas + 4 botões) da aba MÚSICA (Android e web); as caixas **Metrônomo/Áudio** agora ficam na PLAYLIST e o ▶ delas manda (`SessionController.playWithMetro/playWithAudio`; web: ids `with-metro`, `with-audio`, `play-all`, `pause-all`, `stop-all` agora são os botões da playlist). Anfitrião não vê mais "Áudio do meu grupo" (o áudio dele é o do cartão do grupo dele); convidados continuam vendo.
- **Configuração não some:** (1) a música carregada guarda sozinha o que for mudado: laço que grava quando a configuração para de mudar por 3 s, só com nada tocando e sessão não vazia; (2) ao abrir o servidor/sessão, volta a última música carregada (PC: `rep_restore`; Android: `Setlist.restoreLast` 2 s depois de abrir, depois de restaurar grupos/volumes); (3) ao sair da sessão (Android) grava antes; (4) `/api/setlist` devolve `dirty` e `unsaved`; a tela mostra faixa amarela ("Alterações ainda não guardadas… Salvar agora" / "Esta configuração ainda não está na playlist…"); `op: "save"`. Impressão digital da configuração: `_rep_fp_live/_rep_fp_song` (Python) e `fpLive/fpSong` (Kotlin). Músicas guardadas antes da v21 não têm o tamanho do áudio: aparecem como alteradas uma vez e são regravadas.
- **Fim da música / Parar:** inalterado (v20).
- **Modo palco à vista:** (o usuário não o achou) botão grande "Modo palco (tela cheia)" no topo do cartão Partitura (SYNC) e na PLAYLIST, no app e na página (`SessionController.stageOpen`, `StageDialog` no topo da tela; web: `#pl-stage`, `window.sincronoStage`). Esc ou "Sair do palco" fecha.
- **Testes:** `teste_repertorio.py` (dirty/unsaved, autosave, Salvar agora, restaurar ao reabrir, modo palco), `teste_fim_da_musica.py`, `teste_tocar_junto.py` (agora via PLAYLIST). Todos passam. Kotlin NÃO compilado.

## 9h. v22 — palco com várias linhas, "como na partitura", parte só do anfitrião, botões redondos, Nova música (Kotlin NÃO compilado aqui)

- Visualização da partitura (botão redondo cicla): **Uma linha rolando → Linhas que cabem na tela → Quebras da partitura** (respeita `print new-system|new-page` do MusicXML; sem essas marcas, quebra sozinho). O zoom efetivo diminui se o compasso mais largo não cabe na tela (responsivo).
- Modo palco tem visualização e zoom próprios (padrão: "partitura", 1.4), guardados em `sincrono.stageMode/stageZoom`. Defeito corrigido: a classe `pages` usava o modo salvo, não o efetivo, e o palco virava uma linha só.
- Só o anfitrião vê/escolhe a parte; convidados (inclusive o PC como cliente) sempre veem a parte do seu grupo (ignora `sincrono.part` salvo).
- Botões Modo/A−/A+/Palco redondos (44 px) com ícones; no palco flutuam no canto (web: embaixo à direita; app: em cima à esquerda).
- **Nova música** (op `new` em `/api/setlist`, Python e Kotlin; botão na aba PLAYLIST): recusa (409) se algo toca; grava o autosave da atual, limpa partitura/marcadores/áudios e solta a música atual (`current=None`), para o autosave não sobrescrever a anterior. Fluxo: PLAYLIST → Nova música → aba MÚSICA configura → "+ Adicionar a música atual".
- Também: campos BPM/Tempos não estouram em 320 px.
- Teste novo: `tools/teste_palco_quebras.py`. Kotlin (`Setlist.kt` op `new`, `SetlistCard.kt`) NÃO compilado.

## 9i. v23 — partitura só com cifra e letra (pauta oculta) (Kotlin sem mudança)

- Patch do usuário (`sincrono-pauta-oculta.patch`) incorporado sobre a v22. MusicXML com `staff-lines 0` ou `print-object="no"` vira vista só de cifras e letra: sem pauta, clave, notas, pausas nem barras de compasso; cifras e sílabas ganham largura mínima para não se sobreporem; o cursor cobre cifra e letra.
- Só um trecho do patch não coube (a linha `this.noStaff = ...` em `score-view.js`, por causa do zoom da v22) e foi colocado à mão. O resto aplicou limpo.
- Exemplo novo: `docs/exemplos-partitura/cifra-e-letra.musicxml`. Teste novo: `tools/teste_partitura_sem_pauta.py`.
- Teste antigo `teste_partitura_pagina.py` atualizado: o convidado não tem seletor de parte (regra da v22).
- Limite conhecido: o espaço vertical entre linhas na vista sem pauta ainda reserva a altura da pauta oculta (linhas bem afastadas no celular).

## 9j. v24 — correção: "Marcar compassos" cobria os botões no modo palco (Kotlin sem mudança)

- Causa: na v22 toda `.row` virava flutuante no palco, inclusive as linhas de marcar compassos. Agora só a barra de visualização (`.row.tb`) flutua; as linhas de marcação e a linha do tempo ficam ocultas no palco.
- Teste: `tools/teste_palco_quebras.py` confere que as linhas de marcação não aparecem no palco e que os botões não se sobrepõem.

## 9k. v25 — correção: a linha do tempo (navegar pela partitura) acompanha a música em tempo real (Kotlin sem mudança)

- Causa: o controle marcava "estou arrastando" ao tocar e só soltava no evento `change`. Se o toque era cancelado (rolagem da página no celular), ficava preso e parava de acompanhar a música. Agora solta em `pointerup/pointercancel/lostpointercapture/blur` e há um vigia de 3 s.
- Teste novo: `tools/teste_linha_tempo_real.py`. Reproduzido e corrigido na página web; no app Android (WebView) NÃO foi testado. Se o problema persistir lá, o próximo suspeito é o `sincronoTick` do app não atualizar o estado do metrônomo/áudio no host.

## 9l. v26 — modo "Página" (vira sozinha), palco em página por padrão, diagrama de conexão (Kotlin sem mudança)

- Novo modo de visualização **Página**: mostra um bloco de linhas inteiras e vira a página inteira quando o cursor passa para a seguinte (sem rolar aos poucos). O botão redondo agora cicla **Uma linha → Página → Linhas rolando → Quebras da partitura**. O palco usa "Página" por padrão (quem já escolheu outro modo mantém). Implementado em `ScoreView.follow` (modo `page`) e `score-panel.js`.
- Teste: `tools/teste_palco_quebras.py` (rolagem sempre em múltiplos de página e avançando). NÃO testado no app Android (a WebView usa o mesmo `score-panel.js`).
- Diagrama da conexão: `docs/conexao.svg` (e `.png`): roteador, computador anfitrião ou celular anfitrião, celulares por grupo; o que trafega (relógio, comandos, arquivos uma vez).
- Atenção: textos antigos acima (seções 3/9 e README) dizem "cada músico pode trocar a sua parte"; desde a v22 só o anfitrião escolhe a parte.

## 9m. v27 — cor por grupo

- Cada grupo tem uma cor fixa, a mesma em todos os aparelhos, derivada do número do id (`g1`, `g2`…): Geral = cinza, depois azul, laranja, verde-azulado, roxo, vinho, verde, rosa, azul-claro, mostarda (10 cores, depois repete). Sem dado novo na rede: `web/groupcolor.js` (JS) e `ui/GroupColor.kt` (Kotlin) usam a mesma tabela.
- Web: cartão do grupo (barra lateral e nome), seletor de grupo de cada aparelho, e na partitura uma moldura colorida + etiqueta "Grupo: nome" (nome vem de `/api/groups`, em `window.sincronoGroupNames`; no WebView do app só a moldura, o nome não está disponível ali).
- Kotlin (NÃO compilado): nome colorido em `GroupRow`, botão/menu de grupo colorido em `PeerGroupPicker`. A moldura da partitura no app vem do `score-panel.js`.
- Limite: a cor não é escolhida pelo anfitrião (automática). Se quiser escolher, é preciso guardar a cor no grupo (API + app).
- Teste novo: `tools/teste_cor_grupo.py`.

## 9n. v28 — correção de compilação da v27 (primeiro erro real do compilador no cor por grupo)

- `build_apk.py` da v27 falhou em `MetronomoApp.kt` (`PeerGroupPicker`): o projeto tem o seu próprio `FilledTonalButton` em `Components.kt`, sem parâmetro `colors`. Corrigido usando `androidx.compose.material3.FilledTonalButton` com `colors` e `shape` (cantos de 4 dp, como os outros botões) naquele ponto.
- A v28 AINDA não foi compilada por mim; foi o compilador do usuário que achou o erro. O resto do Kotlin das versões anteriores compilou (o log só acusou essas 2 linhas).

## 9. Próximos passos sugeridos (em ordem)

1. O usuário compila a v12 e devolve erros de compilação; corrigir.
2. Testar em aparelhos: tocar/pausar/parar com 2+ aparelhos e 2+ grupos; apelido aparecendo no anfitrião; tela do cliente.
3. Correção da autocalibração com o carimbo mais próximo de cada som (ver seção 6) e comparação com o OboeTester (`Round Trip Latency`) no mesmo aparelho.
4. **Partitura na tela — v13 feita (ver 3a); falta o build do Android e teste com partitura real do usuário**; histórico da decisão em `docs/PARTITURA.md`: formato recomendado MusicXML/MuseScore (não PDF), renderizado por OpenSheetMusicDisplay ou Verovio numa WebView, com cursor guiado pelo relógio compartilhado. Começar por uma prova de conceito num único aparelho.
5. Decidir o destino do código legado de cabo (`CalibrationHub` modo cabo, `calibracao_cabo.py`): remover de vez, trocando o registro de aparelhos do servidor do PC.
6. Medir o servidor no celular anfitrião real com `servidor-pc/simulador.py --host IP:3000`.

## 10. Histórico de versões

- v8: visual novo, grupos ("ilhas"), mixer, página web com abas, QR code, simulador.
- v9: áudio sincronizado, servidor no PC, calibração por cabo (mesa).
- v10: página única no PC ("Ser anfitrião / Entrar"), calibração por soma com um botão, repositório git.
- v11: **autocalibração** (loopback com carimbo do microfone), anfitrião autocalibrável. Validada pelo usuário em aparelhos reais.
- v12: **parar** agendado e transporte com ícones, tela simples do cliente, apelido do aparelho, renomear grupos, cabo retirado da interface, este documento, estudo da partitura.
- v13: **partitura na tela** — leitor MusicXML, desenho SVG com cursor, servidor do PC, página (anfitrião/cliente) e, no app Android, aba PARTITURA do anfitrião e cartão no cliente (WebView). Falta: compilar/testar o Kotlin; marcadores para áudio gravado (v14).
- v13 (revisão): defeito corrigido — a partitura (SVG largo) esticava as grades CSS `main`/`.tab` (min-width:auto) e, em tela estreita, a página ficava com ~1400 px e o desenho parecia em branco no app; agora `grid-template-columns: minmax(0,1fr)` + `min-width:0` (teste `tools/teste_partitura_app.py`, 330 px). Aba ÁUDIO renomeada MÚSICA; aba PARTITURA removida.
- v16: desenho de orquestra, andamento automático no Android, linha do tempo do anfitrião, modo palco.
- v17: marcadores de compasso (timestamp) para áudio gravado.
- v18: repertório (playlist) com músicas preparadas.
- v19: aba PLAYLIST separada com visual de playlist.
- v20: fim da música volta ao 0:00, pausa, botões redondos, renomear no app.
- v21: ícone, metrônomo no visual da playlist, Tocar único na playlist, configuração que não some, modo palco à vista.
- v22: palco com várias linhas, visualização "como na partitura", parte só do anfitrião, botões redondos, Nova música.
- v23: partitura só com cifra e letra (pauta oculta), patch do usuário.
- v24: correção do palco (Marcar compassos cobria os botões).
- v25: linha do tempo acompanha a música em tempo real (controle não fica mais preso).
- v26: modo Página (vira sozinha) e diagrama de conexão.
- v27: cor por grupo.
- v28: correção de compilação (botão de grupo colorido).

### 9o. v29 — cor de cada grupo escolhida pelo anfitrião + arquivo `.sincro` (música ou playlist)

**Cores.** Cada grupo tem cor padrão (paleta de 10 pelo número do id) e o anfitrião pode trocar (bolinhas + seletor na tela de grupos, no PC e no app). A cor fica no grupo (`group_colors`), vai em `/api/groups` (`color`), aparece em todos os aparelhos (nome do grupo, moldura e rótulo "Grupo: nome" na partitura, também no modo palco) e é guardada em cada música da playlist (ao carregar a música, as cores voltam). Músicas antigas sem cores guardadas usam as de agora. Op: `POST /api/groups {op:'color', id, color:'#rrggbb'}`.

**Arquivo `.sincro`.** Zip (sem compressão) com `sincro.json` {format:"sincro", version:1, songs:[{name,bpm,beats,marks,parts,colors,score:{name,size}|null,audio:[{group,file,name,mime,size}]}]} + `song-N/score.bin` + `song-N/audio-K.bin`. Áudio mantido como está (FLAC/WAV: nada é convertido). Endpoints do PC: `GET /api/setlist/export?ids=1,2&name=...` (sem ids = playlist toda) e `POST /api/setlist/import?mode=add|replace` (corpo = o arquivo). Na importação: só aceita nomes de arquivo esperados, limites de tamanho (cada item `MAX_UPLOAD`, total 2 GB, ≤5000 entradas, ≤200 músicas), nomes limpos, dados estranhos ignorados; `replace` recusa com 409 se algo toca. Grupos são recriados pelo NOME.

**Na página (PC e celular pelo navegador):** aba PLAYLIST tem "Salvar playlist (.sincro)", "Abrir .sincro" (juntar/substituir) e, no menu de cada música, "Salvar esta música".
**No app Android (anfitrião):** mesma coisa na aba PLAYLIST, usando o seletor de arquivos do sistema (`Setlist.exportTo/importFrom`, `SessionController.setlistExport/setlistImport`).

**Testes (tools/):** `teste_sincro.py`, `teste_sincro_pagina.py`, `teste_cor_grupo.py` — passam, junto com todos os anteriores.

**Honestidade:** o Kotlin da v29 (e o da v28) NÃO foi compilado por mim; só Python/web foram testados. Se `python3 build_apk.py` reclamar, mande o erro. O servidor HTTP do app não oferece export/import por navegador (só os botões do app). Não existe ainda abrir `.sincro` direto do gerenciador de arquivos (intent-filter) nem ajuste manual de atraso para fone Bluetooth.

### 9p. v30 — patch "pauta oculta" (barras finas na vista só de cifra e letra)
Aplicado o patch `sincrono-pauta-oculta.patch` por cima da v29. A maior parte já estava na v23; entrou o que faltava em `score-view.js`: sem pauta, as barras de compasso viram um traço fino (opacidade 0,45) só na faixa de cifras e letra, e `bar-style none` só some com a barra quando há pauta. Também: exemplo novo `docs/exemplos-partitura/cifra-e-letra-compassos.musicxml` e `tools/teste_partitura_sem_pauta.py` atualizado (12 compassos de 2 tempos a 90 bpm = 16000 ms; aos 5 s o cursor está no compasso 4). Todos os testes Python/web passam. Kotlin igual à v29 (não compilado por mim).

### 9q. v31 — Tocar só com partitura; .sincro pelo navegador no app; abrir .sincro pelo gerenciador de arquivos
- **Correção:** sem nenhum áudio carregado (só partitura), o Tocar da playlist (caixa "Áudio" marcada por padrão) não fazia nada útil. Agora, se nenhum grupo tem áudio, "Áudio" vale como metrônomo (que move a partitura). Com áudio carregado, nada muda. Web: `host.js` (`anyAudio`); app: `SessionController.playTogether`. Teste novo: `tools/teste_so_partitura.py` (passa).
- **Servidor do app (Android):** `GET /api/setlist/export?ids=1,2&name=...` (resposta em fluxo, sem Content-Length, `Connection: close`) e `POST /api/setlist/import?mode=add|replace` (corpo = arquivo, até 2 GB, lido em fluxo; `Request.stream`). Mesmas regras do servidor do PC. A página (`repertorio.js`) já usava esses endpoints, então salvar/abrir .sincro pelo navegador funciona também com o anfitrião no celular.
- **Abrir pelo gerenciador de arquivos / compartilhar:** `AndroidManifest.xml` (vários intent-filters VIEW/SEND por extensão `.sincro` e tipos genéricos), `MainActivity.receiveSincro` (onCreate + onNewIntent) → `SessionController.pendingSincro` → `ui/SincroOpen.kt` (diálogo Juntar/Substituir; sem sessão de anfitrião o arquivo espera). `SessionController.importSincro` é usado também pelo botão "Abrir .sincro".
- **Honestidade:** tudo em Kotlin/manifest (servidor do app, intent-filters, diálogo) NÃO foi compilado nem testado em aparelho por mim; só a parte web/Python foi testada. Alguns gerenciadores de arquivo não listam o app para `.sincro` (o tipo vem como octet-stream); o "Compartilhar → Sincrono" e o botão "Abrir .sincro" dentro do app são o plano B.

### 9r. v32 — corrige 2 erros de compilação da v31
`SetlistCard.kt` (`refresh` era declarada depois de `doImport`: agora recarrega a lista direto) e `SincroOpen.kt` (faltava `import androidx.compose.runtime.setValue`). Nada mais mudou. Kotlin ainda sem compilar por mim; o usuário confirma com `python3 build_apk.py`.

### 9s. v33 — "Ouvir também": mais de um áudio ao mesmo tempo no mesmo aparelho
Cada aparelho (inclusive o do anfitrião) toca o áudio do próprio grupo e, se marcar, o de outros grupos junto (ex.: piano de acompanhamento + a própria voz), cada um com o seu volume. Só afeta o aparelho que marcou; o anfitrião não precisa fazer nada.
- **Como funciona:** cada grupo extra tem o seu tocador, que segue o estado daquele grupo no anfitrião (mesmo relógio, mesmo `startAt` do "Tocar"), então tudo continua alinhado. Os extras NÃO se registram como aparelho do grupo (consulta sem `device`), logo não mexem nas contagens "prontos/aparelhos" do anfitrião. Offsets de latência/calibração valem para todos os tocadores. O volume geral do aparelho (próprio × anfitrião) vale para o conjunto; cada extra tem o seu volume por cima.
- **Web:** `extra-audio.js` (novo) + seção "Ouvir também" (`#extra-audio`, na aba MIXER) em `index.html`; escolha guardada em `localStorage` por servidor. Teste novo: `tools/teste_ouvir_tambem.py` (dois áudios tocando juntos, mesma posição, volume, parar, desmarcar) — passa; todos os outros também.
- **App Android (NÃO compilado por mim):** `SessionController` (`extras`, `setExtra`, `setExtraVolume`, `syncExtras`, `stopExtras`; um `AudioFilePlayer` + `AudioPlayback` por extra, pasta própria `audio-x-<grupo>`), `AudioFilePlayer.gain`, `AudioPlayback` (`dirName`, `stopped`), `ui/ExtraAudio.kt` (cartão "Ouvir também", na aba SYNC do anfitrião e na tela do cliente). A seleção vale só na sessão (ids de grupo são de cada anfitrião).
- **Limites:** vários AudioTracks ao mesmo tempo gastam mais CPU/bateria; cada extra baixa e decodifica o seu arquivo (memória). Sem painel de "pronto" por extra.

### 9t. v34 — Mixer novo e robustez do anfitrião
- **Mixer (web):** um cartão por grupo (cor, volume do grupo, Mudo, Solo) com os aparelhos dentro; faders finos (`.fader`, trilho na cor do grupo); mudo/solo calculados a partir dos volumes (`g:<id>` = 0), volume anterior lembrado. Teste: `tools/teste_mixer.py`.
- **Mixer (app, NÃO compilado):** `ui/SoftFader.kt` (fader leve só com gestos básicos), `Fader(..., accent)`, `HostMixer`/`ToggleChip` em `MetronomoApp.kt`, `SessionController.toggleGroupMute/toggleGroupSolo/isGroupSolo`. "Ouvir também" foi para a aba MIXER.
- **Robustez do anfitrião (app, NÃO compilado):** WakeLock sem limite de 4 h (solto em `onDestroy`); notificação com texto vivo ("Anfitrião: N aparelhos", "Reconectando ao anfitrião…") e botão "Encerrar sessão"; cartão "Ensaio longo" na aba REDE do anfitrião (abre o ajuste de economia de bateria). A reconexão automática do cliente (SSE com espera crescente) e o serviço em primeiro plano já existiam.
- **Robustez (web):** `wakelock.js` mantém a tela acesa enquanto algo toca e solta ao parar. Teste: `tools/teste_tela_acesa.py`.
- **Adiado a pedido do usuário:** som gerado a partir da partitura (a ideia discutida era renderizar para WAV e usar como áudio de grupo; sem plano escrito ainda).

### 9u. v35 — Pausar/Parar da playlist só com partitura (app)
Causa: `SessionController.pauseTogether` mandava o comando de áudio primeiro e, se ele devolvia erro (sem áudio carregado), pulava o comando do metrônomo, então nada parava. Agora o comando de áudio só é enviado se algum grupo tem áudio, e um erro dele nunca impede o metrônomo (que move a partitura) de parar. A página já funcionava; `tools/teste_so_partitura.py` agora também cobre Pausar, tocar de novo e Parar só com partitura. Kotlin não compilado por mim.

### 9v. v36 — Modo palco que ocupa a tela, "Página inteira" e partitura na aba PLAYLIST
- **Palco curto (só uma linha):** no app o modo palco abre num `Dialog`, cuja janela tem altura "wrap_content"; o `fillMaxSize` encolhia a partitura. Agora `StageDialog` (`ui/ScoreView.kt`) impõe o tamanho da tela (`requiredSize` com `LocalConfiguration`, refeito ao girar) e o JS fixa a altura da caixa em `window.innerHeight` (`score-panel.js`, `draw()`), sem depender de `100vh`. **Kotlin NÃO compilado/testado em aparelho**; o JS foi testado em Chromium (vertical e horizontal).
- **Opção de ver a página inteira:** novo modo `fit` ("Página inteira"), no ciclo do botão de visualização (uma linha → página → **inteira** → linhas → partitura). `fitAll()` escolhe o maior zoom (até o do usuário, A−/A+ ajustam o teto) com que TODAS as linhas cabem na altura. Valores de zoom abaixo de ~0.4 não são usados: partitura muito longa pode não caber inteira (então rola).
- **Etiqueta "Grupo: …" sobre os botões:** `.gchip{display:inline-block}` anulava o atributo `hidden`; agora `.gchip[hidden]{display:none !important}` e o JS também define `display`.
- **Partitura na aba PLAYLIST:** web: `#pl-score-slot` em `#setlist`; `host.js` (`moveScore`) move a seção `#score` para lá ao abrir a aba PLAYLIST e a devolve à aba SYNC nas outras. App: `SetlistCard(ui)` mostra `ScoreCard(ui)` (com o botão de palco) acima dos controles. O botão "Modo palco" da PLAYLIST não troca mais de aba.
- **Testes:** `tools/teste_palco_inteiro.py` (novo: altura do palco nas duas orientações, página inteira cabe, etiqueta some, cursor anda na PLAYLIST, partitura volta à SYNC); `teste_palco_quebras.py` atualizado para o novo ciclo de modos.

### 9w. v37 — Tempo e caixas ao vivo; anfitrião que reinicia
- **Anfitrião reiniciou e o cursor da partitura ficou parado nos clientes (causa provável):** o cliente descartava como "antigo" todo estado com revisão menor que a última vista (`AudioPlayback.handle`, `applySongState`, `applyState`). Depois que o anfitrião reinicia, as revisões recomeçam do zero e o id do arquivo pode se repetir. Agora o servidor (PC e app) manda uma **época** (`epoch`, instante em que subiu) no estado do áudio e do metrônomo; quando ela muda, o cliente zera a revisão, baixa o arquivo de novo e refaz o estado. A página da partitura também recarrega se id+nome+tamanho mudarem. **Não consegui reproduzir no aparelho**: a causa é a mais provável pela leitura do código; se o cursor ainda parar depois do anfitrião voltar, me diga se o áudio também falha e o que aparece na aba REDE.
- **Tempo ao vivo:** o comando `settings` (PC e app) com o metrônomo tocando agora mantém a batida e a posição na partitura (recalcula o `startAt`: `agora − (agora − startAt)·bpmAntigo/bpmNovo`); antes o clique reiniciava. Na página, digitar o BPM/compasso vale sozinho ~0,6 s depois (`app.js`); no app já era assim (700 ms).
- **Caixas Metrônomo/Áudio em tempo real:** com algo tocando, marcar liga e desmarcar desliga aquela parte na hora (web `liveToggle` em `host.js`; app `SessionController.liveToggle`, ligado em `SetlistCard`). Ao ligar entra na batida: o clique no múltiplo do intervalo da posição do áudio; o áudio na batida do metrônomo. Sem nada tocando, só vale no próximo Tocar.
- **Testes:** `tools/teste_ao_vivo.py` (batida não pula, caixas ao vivo, BPM digitado, reinício do servidor com revisão menor). Kotlin NÃO compilado.
- **Velocidade do áudio proporcional ao BPM (ainda NÃO feita):** análise: tocar mais lento/rápido mantendo o tom exige esticar o áudio (WSOLA/fase). Caminho mais seguro: o anfitrião gera uma cópia esticada (fator = bpmNovo/bpmOriginal, ex.: 84/120 = 0,7) e a usa como áudio do grupo; os clientes recebem como um arquivo comum (sem mexer no motor de sincronia). Os marcadores de compasso do áudio são reescalados por 1/fator. Alternativa mais simples (muda o tom): `playbackRate`. Aguardando a escolha do usuário.
