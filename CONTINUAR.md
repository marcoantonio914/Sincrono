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
