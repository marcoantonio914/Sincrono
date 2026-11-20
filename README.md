> **Para continuar o trabalho (pessoa ou agente de IA): leia primeiro `CONTINUAR.md`** (estado atual, regras, mapa do código, pendências). Estudo de partitura na tela: `docs/PARTITURA.md`.

# Sincrono v8 (Android + servidor para o computador)

Pasta nova; `sincrono` (v7 + banner) e as anteriores não foram alteradas. App `com.sincrono.app`.

> **Aviso honesto:** o código **Kotlin não foi compilado nem executado** onde foi escrito — o primeiro `python build_apk.py` pode apontar erros de compilação que eu terei de corrigir. Foram testados de verdade: a lógica em Java (`tools/*Test.java`), a página web (simulação em Node), o servidor Python (`servidor-pc/teste_servidor.py`) e o simulador. Não foi testado em aparelho: telas, NSD, leitor de QR, anúncios, assinatura do AAB.

## Gerar APK e AAB

`python build_apk.py` gera `sincrono-debug.apk` (para instalar) e `sincrono-release.aab` (para a Play Store, assinado com uma chave de upload criada em `~/.sincrono-keystore`). **Faça backup dessa pasta**: sem a chave não dá para atualizar o app na loja. `--no-aab` gera só o APK; `--version-code N` define o versionCode do AAB (aumente a cada envio).

## Novidades da v8

- **Visual novo** (zip "Acoustic Metric"): tema claro, cartões ("módulos") de borda fina, rótulos em maiúsculas, botões de 4 dp, números de largura fixa, navegação inferior SYNC / ÁUDIO / MIXER / REDE. A página web usa a mesma paleta. Diferenças em relação às imagens: fonte do sistema (Inter não está embutida), navegação só com texto (sem ícones) e só tema claro.
- **Grupos ("ilhas")**: o anfitrião cria grupos (violinos, coral, banda…), escolhe um arquivo para cada um, move aparelhos entre grupos (aba ÁUDIO) e controla o volume de cada grupo e de cada aparelho (aba MIXER). "Tocar todos" usa **um único instante de início** para todos os grupos. Aparelho sem grupo fica no "Geral". A página web também segue o grupo atribuído.
- **Página web com a mesma interface** (abas SYNC / ÁUDIO / MIXER / REDE): grupos, envio de arquivos (no servidor do computador), volume por grupo e por aparelho, aparelho → grupo, e **QR code** do endereço (aba REDE). Um aviso vermelho "Som desligado" aparece até você tocar/clicar uma vez (regra dos navegadores); depois a página do computador toca o metrônomo e a música.
- **Simulador** (`servidor-pc/simulador.py`): `python3 simulador.py --spawn --devices 100 --groups 4` simula aparelhos com relógios defasados, jitter, perda e assimetria; mede latência por requisição, distribuição de comandos, erro de relógio e CPU do servidor. Também testa um servidor real: `--host IP:3000` (inclusive um celular anfitrião).

Resultados medidos aqui (servidor Python e simulador na mesma máquina, RTT 6 ms, jitter 4 ms): 100 aparelhos → 0 erros, comandos distribuídos em mediana 9 ms (máx 17 ms), erro de relógio p95 1,3 ms, CPU 11%; 300 aparelhos → 0 erros, distribuição mediana 39 ms (máx 64 ms), CPU 29%. Isto vale para o servidor no computador num cenário simulado; **não** representa o Wi-Fi real nem o celular como anfitrião, que ainda deve ser medido com `--host`.

Mantido da v7: descoberta automática (NSD), QR code, IP manual, volumes e banner AdMob.

---

O restante (calibração, cabo, perfis, diagnóstico, áudio sincronizado) é igual ao da v6, descrito abaixo.

---

## 1. Diagnóstico de qualidade na tela

O bloco "Sincronização" da tela de sessão passou a mostrar por que o app considera (ou não) a sincronização confiável:

- **Nível:** Excelente, Aceitável, Degradada ou Não confiável, com a **precisão estimada** do relógio (±X ms).
- **Wi-Fi:** RTT mediano e mínimo, jitter (variação do RTT), amostras aceitas e descartadas.
- **Áudio:** se o aparelho informa o horário do áudio com precisão, em estimativa reduzida, ou instável (item 4).
- **Calibração:** válida (com confiança e variação), ausente para esta saída, ou "este aparelho é a referência".
- **Música** (quando tocando): erro de posição, velocidade de correção aplicada, saltos e quanto tempo a correção ficou no limite. Se ficar no limite com frequência, o app avisa: indica relógio, timestamp ou calibração errados.

Como é calculado (`SyncMath.java`): jitter = desvio médio do RTT em torno da mediana; dispersão do offset entre as melhores amostras; precisão = a maior entre essa dispersão e pisos proporcionais ao jitter e ao RTT mínimo. Nos testes simulados a precisão estimada ficou **um pouco acima** do erro real (não otimista). **Limite importante:** uma assimetria constante entre ida e volta da rede (o roteador atrasar mais um sentido) causa um erro fixo que nenhuma medição por troca de mensagens enxerga. Por isso o número é "precisão estimada", não uma garantia. Níveis: Excelente = jitter ≤ 1 ms, RTT ≤ 20 ms e precisão ≤ 1 ms; Aceitável = jitter ≤ 3 ms, RTT ≤ 60 ms, precisão ≤ 3 ms; Não confiável = sem sincronização, amostra boa com mais de 10 s ou precisão pior que 20 ms. Timestamp de áudio em estimativa reduzida limita o nível a "Aceitável"; instável, a "Degradada".

## 2. Confiança da calibração

Cada resultado de calibração recebe **alta, média ou baixa** confiança:
- alta: variação ≤ 1,5 ms e pelo menos 3 repetições aproveitadas;
- média: variação ≤ 4 ms;
- baixa: acima disso, ou poucas repetições.

Regras:
- Uma calibração de **baixa confiança não substitui** uma calibração válida (contra o mesmo anfitrião) que já esteja em uso: o app mantém a anterior e avisa para refazer.
- Se não havia calibração, a de baixa confiança é aplicada, mas marcada com aviso.
- A confiança e a variação aparecem em cada linha do resultado e na tela de sessão. São guardadas junto do perfil da saída (com a data e a versão do algoritmo, só informativas).
- A página web segue a mesma regra.
- Se o áudio do aparelho não informou horários precisos durante a calibração, o resultado traz um aviso de que o erro pode ser maior.

## 3. Áudio por evento (SSE)

Play, pause, seek e arquivo novo chegam por um fluxo de eventos próprio (`/events/audio`) assim que o anfitrião muda algo, sem esperar o ciclo de consulta. A consulta (`/api/audio`) continua, mas lenta (1,5 s), só para registrar o aparelho, atualizar "X de Y prontos" e **recuperar o estado completo** se o fluxo cair. Cada mensagem traz o estado completo e uma `revision`: mensagens atrasadas ou duplicadas são ignoradas. A antecedência de 2,5 s do play não foi reduzida. O fluxo é separado de `/events`, então clientes antigos não são afetados.

## 4. Validação do timestamp do AudioTrack

Metrônomo, música e calibração agora usam o mesmo rastreador (`AudioClockTracker.java`), que valida `AudioTrack.getTimestamp()`:
- descarta timestamps que voltam no tempo;
- conta saltos maiores que 5 ms;
- detecta timestamps que pararam de chegar;
- estados: **iniciando**, **preciso**, **estimativa reduzida** (aparelho sem timestamp; usa a posição de reprodução com 20 ms fixos, agora **sinalizado** na tela) e **instável** (3 ou mais saltos em 10 s, fora de ordem, ou parado há mais de 2 s).
O rastreador não aloca memória nem faz I/O na thread de áudio. O estado vai para o diagnóstico; ele não muda sozinho o comportamento do áudio (não recria a trilha), só informa e rebaixa o nível.

## Limites
- A estimativa de 20 ms do modo de reserva continua sendo uma suposição; ela agora aparece como "estimativa reduzida", mas não foi refinada.
- Não há modelo de deriva do relógio entre aparelhos (decisão consciente: com sincronização a cada 2 s o erro por deriva fica em ~0,1 ms).
- Tudo que depende do Android real (timestamps, telas, SSE) só foi verificado por leitura e simulação.

## Testes
`tools/SyncTest.java` (relógio, rastreador de timestamp, confiança), `tools/AudioSyncTest.java` (deriva da música), `tools/DspTest.java` (calibração, inclui cabo), `tools/SelfCalTest.java` (autocalibração).
```
D=out; mkdir -p $D
javac -encoding UTF-8 -d $D app/src/main/java/com/metronomo/diag/*.java app/src/main/java/com/metronomo/calibration/CalibrationDsp.java app/src/main/java/com/metronomo/calibration/SelfCalibrationDsp.java app/src/main/java/com/metronomo/audio/AudioSync.java tools/SyncTest.java tools/AudioSyncTest.java tools/DspTest.java tools/SelfCalTest.java
java -cp $D SyncTest && java -cp $D AudioSyncTest && java -cp $D DspTest && java -cp $D SelfCalTest
```

## Computador como anfitrião e calibração por cabo

- **Servidor no computador** (`servidor-pc/`): `python3 sincrono_server.py` abre **uma página só** (Chrome/Edge com o som já liberado). Como no app, ela pergunta **"Ser anfitrião"** ou **"Entrar em uma sessão"**. Como anfitrião: metrônomo, músicas por grupo, volumes, QR code (aba REDE) e calibração, tudo na mesma página. Celulares entram pelo app ("Sessões encontradas" com `pip install zeroconf`, ou QR/IP). O endereço `/admin` antigo agora só leva à página inicial.
- **Calibrar todos com um botão** ("Calibrar todos os aparelhos", no computador ou no celular): cada aparelho e o computador ligam a saída de fone à **mesa de som**; a mesa soma tudo e vai ao computador por USB. Cada aparelho toca o som de teste no seu horário, o computador grava, mede e manda o ajuste a cada um. Sem escolher canal nem interface (a mesa USB é escolhida automaticamente; "Avançado" permite trocar). Precisa de `numpy` e `sounddevice` (num ambiente virtual no Ubuntu). **Testado só com gravação simulada** (11 aparelhos, erro < 0,001 ms nos testes, `teste_calibracao_cabo.py`); com mesa de verdade, ainda não.
- **Instalação no Ubuntu**: `sudo apt install python3-venv libportaudio2`, `python3 -m venv ~/sincrono-venv`, `~/sincrono-venv/bin/pip install numpy sounddevice zeroconf`, e rodar com `~/sincrono-venv/bin/python sincrono_server.py`.
- **Diagnóstico da mesa**: `python diagnostico_interface.py` mostra o nível de cada entrada da interface.

## Autocalibração (v11)

Botão **"Autocalibrar este aparelho"** na seção de calibração. O aparelho toca 8 sons de teste e o **próprio microfone** os grava (alto-falante perto do microfone; com fone, encostado nele; ou cabo de loopback). Mede, em valor **absoluto**, quanto o aparelho soa depois do horário combinado no relógio compartilhado, e guarda o ajuste por tipo de saída. **Não precisa de outro aparelho nem de referência.**

- O horário de cada amostra gravada vem do carimbo de tempo do microfone (`AudioRecord.getTimestamp`), que já inclui o atraso de entrada. **Se o aparelho não informa esse carimbo, o resultado não é aplicado** (a tela mostra a medida bruta só como informação).
- Confiança: variação entre os sons (sem os extremos) e estabilidade do carimbo; baixa confiança não substitui uma calibração melhor já em uso.
- O **anfitrião também pode se autocalibrar** e passa a soar no horário certo. Para o grupo ficar alinhado, todos (anfitrião incluído) devem estar autocalibrados. Se um aparelho for calibrado em conjunto contra um anfitrião autocalibrado, o ajuste do anfitrião é somado automaticamente.
- A tela avisa se o aparelho não declara baixa latência (`FEATURE_AUDIO_LOW_LATENCY` / `FEATURE_AUDIO_PRO`).
- **Verificado só por simulação** (`tools/SelfCalTest.java`: atraso, perdas de sons, ruído, saturação, jitter, carimbo instável). **Ainda não testado em aparelho real**, e o Kotlin da v11 não foi compilado. Comparar com a calibração em conjunto (mesa/cabo) nos seus aparelhos é o jeito de saber o erro real de cada modelo.

## v12: parar, ícones, tela do cliente, apelidos

- **Parar** (⏹): todos os aparelhos param no mesmo instante combinado e a música volta ao início; vale por grupo e para "todos os grupos". Tocar e pausar já eram agendados (início em +2,5 s, parada em +1,2 s).
- Botões de transporte só com **ícones** no app e na página web. O metrônomo tem ▶ (inicia ou recomeça) e ⏹.
- **Cliente vê só o básico:** metrônomo e música apenas para acompanhar (barra de progresso e tempo), botão de autocalibrar e o nome do aparelho. O anfitrião mantém todas as opções.
- **Apelido** do aparelho (cliente): aparece para o anfitrião nas listas de aparelhos, grupos e mixer.
- O anfitrião **renomeia grupos** (botão "Renomear" em cada grupo).
- Calibração por cabo retirada da interface (veja `CONTINUAR.md` sobre o código legado).
- Testes novos: `servidor-pc/teste_transporte.py`.

## v13: partitura na tela

No MuseScore: Arquivo → Exportar → **MusicXML** (a partitura completa, uma vez só). No Sincrono (computador como anfitrião) abra a seção "Partitura" na aba SYNC, importe o arquivo e, no cartão de cada grupo (aba MÚSICA), escolha a **Parte da partitura** do grupo — os clientes do grupo passam a ver na hora; cada músico pode trocar a sua parte no próprio aparelho. O cursor segue o metrônomo e, se houver áudio tocando (exportado do MuseScore), o áudio. Repetições, casas 1/2, da capo/segno/coda e mudanças de andamento são respeitados.

No app Android: na aba **MÚSICA** o anfitrião importa a partitura e, no cartão de cada grupo, escolhe o áudio e a parte (uma coisa só); cada aparelho vê um cartão **Partitura** na aba SYNC com a parte do grupo dele (pode trocar). A partitura é desenhada numa WebView (sem internet); o cursor usa o relógio, o metrônomo e o áudio do próprio app.

Testes (precisam de Python + Playwright/Chromium): `python3 tools/teste_partitura_app.py`, `python3 tools/teste_partitura_leitor.py`, `python3 tools/teste_partitura_desenho.py`, `python3 tools/teste_partitura_pagina.py`, `python3 servidor-pc/teste_partitura.py`. Exemplo: `docs/exemplos-partitura/completa.musicxml` (gerado por `tools/gerar_exemplos_partitura.py`).

