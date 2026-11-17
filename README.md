# Sincrono v8 (Android + servidor para o computador)

Pasta nova; `sincrono` (v7 + banner) e as anteriores não foram alteradas. App `com.sincrono.app`.

> **Aviso honesto:** o código **Kotlin não foi compilado nem executado** onde foi escrito — o primeiro `python build_apk.py` pode apontar erros de compilação que eu terei de corrigir. Foram testados de verdade: a lógica em Java (`tools/*Test.java`), a página web (simulação em Node), o servidor Python (`servidor-pc/teste_servidor.py`) e o simulador. Não foi testado em aparelho: telas, NSD, leitor de QR, anúncios, assinatura do AAB.

## Gerar APK e AAB

`python build_apk.py` gera `sincrono-debug.apk` (para instalar) e `sincrono-release.aab` (para a Play Store, assinado com uma chave de upload criada em `~/.sincrono-keystore`). **Faça backup dessa pasta**: sem a chave não dá para atualizar o app na loja. `--no-aab` gera só o APK; `--version-code N` define o versionCode do AAB (aumente a cada envio).

## Novidades da v8

- **Visual novo** (zip "Acoustic Metric"): tema claro, cartões ("módulos") de borda fina, rótulos em maiúsculas, botões de 4 dp, números de largura fixa, navegação inferior SYNC / ÁUDIO / MIXER / REDE. A página web usa a mesma paleta. Diferenças em relação às imagens: fonte do sistema (Inter não está embutida), navegação só com texto (sem ícones) e só tema claro.
- **Grupos ("ilhas")**: o anfitrião cria grupos (violinos, coral, banda…), escolhe um arquivo para cada um, move aparelhos entre grupos (aba ÁUDIO) e controla o volume de cada grupo e de cada aparelho (aba MIXER). "Tocar todos" usa **um único instante de início** para todos os grupos. Aparelho sem grupo fica no "Geral". A página web também segue o grupo atribuído.
- **Página web com a mesma interface** (abas SYNC / ÁUDIO / MIXER / REDE): grupos, envio de arquivos (no servidor do computador), volume por grupo e por aparelho, aparelho → grupo, e **QR code** do endereço (aba REDE). Um aviso vermelho "Som desligado" aparece até você tocar/clicar uma vez (regra dos navegadores); depois a página do computador toca o metrônomo e a música.
- **Servidor no computador** (`servidor-pc/`): `python3 sincrono_server.py` — o computador vira anfitrião (mesmo protocolo; apps e navegadores entram nele). Painel em `http://localhost:3000/admin` (grupos, envio de arquivos, volumes, metrônomo, calibração por cabo). `pip install zeroconf` (opcional) faz o servidor aparecer em "Sessões encontradas". O servidor abre a página do computador em Chrome/Edge/Chromium com o autoplay liberado (som sem clique); em outro navegador é preciso um clique em "Ativar som". **É essa página que toca o som no computador.**
- **Calibração por cabo com interface multicanal** (`servidor-pc/calibracao_cabo.py`, `pip install numpy sounddevice`): cada aparelho vai por cabo a um canal da interface (ex.: Teyun Q-12, 2 canais; com 8 canais, 7 aparelhos + a referência por rodada). No `/admin` escolha a interface, o canal de cada aparelho e a referência (a página do computador, também ligada a um canal) e clique em "Calibrar por cabo". Testado só com gravação simulada (erro 0,000 ms nos testes, `teste_calibracao_cabo.py`); **não testado com interface real**.
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
`tools/SyncTest.java` (relógio, rastreador de timestamp, confiança), `tools/AudioSyncTest.java` (deriva da música), `tools/DspTest.java` (calibração, inclui cabo).
```
D=out; mkdir -p $D
javac -encoding UTF-8 -d $D app/src/main/java/com/metronomo/diag/*.java app/src/main/java/com/metronomo/calibration/CalibrationDsp.java app/src/main/java/com/metronomo/audio/AudioSync.java tools/SyncTest.java tools/AudioSyncTest.java tools/DspTest.java
java -cp $D SyncTest && java -cp $D AudioSyncTest && java -cp $D DspTest
```
