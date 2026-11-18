# Estudo de viabilidade: partitura na tela, "quase como um MuseScore"

Escrito na v12. **Nada foi implementado.** Marcado como (fonte) o que li em páginas oficiais nesta pesquisa e como (a confirmar) o que vem de conhecimento geral e precisa de uma prova de conceito.

## Resposta curta

É **viável**, e o formato certo **não é o PDF**. O caminho recomendado é: o usuário exporta a partitura do MuseScore (ou de outro programa) em **MusicXML** (`.musicxml` ou `.mxl`), o anfitrião a envia aos aparelhos como já faz com o áudio, e cada aparelho a **desenha** numa WebView (biblioteca JavaScript), com um **cursor** que anda pelo mesmo relógio compartilhado do metrônomo e da música. Isso é "visualizar e acompanhar", não "editar como o MuseScore": a edição continua no MuseScore, no computador.

## Por que não PDF

- Um PDF é uma **imagem de página**: não diz onde está cada compasso nem cada nota, então não há como mover um cursor pelo tempo.
- Para transformar PDF em notas é preciso reconhecimento óptico de música (OMR). O Audiveris, o mais conhecido de código aberto, aceita imagens digitalizadas de partituras **impressas** (não manuscritas) e o próprio manual diz que o reconhecimento "ainda está longe da perfeição", sendo comum corrigir à mão (fonte: manual do Audiveris). A licença é AGPL v3 (fonte), o que pesa se for embutido no app. Não serve como caminho principal, mas pode ser uma **ferramenta opcional no computador** do usuário (PDF → MusicXML → corrigir no MuseScore).
- O PDF ainda tem uso: mostrar a página como imagem (o Android tem `PdfRenderer`) e virar página **manualmente** ou por marcadores de tempo. Sem cursor, sem escolher instrumento.

## O formato certo: MusicXML

- É o formato de troca de partituras que os programas de notação exportam; o MuseScore exporta `.musicxml`/`.mxl` (a confirmar na versão que o usuário usa). Contém notas, compassos, andamento, fórmula de compasso e **cada instrumento separado** (as "partes").
- **Combina com os grupos do Sincrono:** cada grupo ou aparelho pode exibir **a sua própria parte** (violinos veem a parte dos violinos), enquanto todos seguem o mesmo relógio.

## Como desenhar no aparelho

Duas bibliotecas JavaScript abertas, ambas rodam dentro de uma **WebView** do Android (o app já embute uma página web) e na página do navegador do computador, com um só código:

| | OpenSheetMusicDisplay (OSMD) | Verovio |
|---|---|---|
| Entrada | MusicXML; "interpreta a maioria das marcações" (fonte) | MEI, MusicXML, ABC, Humdrum (fonte) |
| Saída | SVG ou PNG, baseada no VexFlow (fonte) | SVG, MIDI, **timemap** (fonte) |
| Tempo | cursor que anda nota a nota (a confirmar na prova de conceito; a página que li não detalha) | o **timemap** é uma lista JSON com `tstamp` (ms), `qstamp` (tempo em semínimas), `tempo`, e os ids das notas que **começam** (`on`) e **terminam** (`off`) em cada instante (fonte) |
| Limites conhecidos | nem todas as marcações são suportadas: pedal avançado, trêmolo entre duas notas, ligaduras entre pautas (fonte) | exige conferir cada recurso na prova de conceito |
| Licenças | a confirmar (a página que li não informa) | a confirmar |

Qualquer das duas serve; a escolha final deve sair de uma **prova de conceito** com partituras reais do usuário (desempenho em aparelho simples, tempo de desenho de uma partitura longa, fidelidade).

## Como fazer o cursor seguir o tempo

A partitura tem um **mapa de tempo** próprio (compasso 1 = 0 ms, compasso 2 = tantos ms, mudanças de andamento). O app já tem um relógio compartilhado. Então:

1. **Com o metrônomo:** a posição é `(agora − startAt)` em batidas, o que dá compasso e batida, e o cursor vai para a nota correspondente. É o caso mais simples e preciso. O andamento da sessão precisa ser o da partitura (ou a partitura segue o andamento da sessão).
2. **Com um arquivo de áudio gravado:** uma gravação de pessoas não é perfeitamente regular, então o tempo da partitura **não coincide sozinho** com o do áudio. A solução prática são **marcadores**: o anfitrião marca (tocando junto, uma vez) em que instante do áudio começa cada compasso ou cada seção, e o cursor interpola entre os marcadores. Se o áudio foi gerado da própria partitura (exportado do MuseScore), o mapa de tempo já coincide.
3. **Para todos ao mesmo tempo:** o cursor depende só do relógio compartilhado e da posição do áudio/metrônomo; nenhuma rede extra por quadro. Entrar atrasado já cai no ponto certo, como no áudio.

## O que isso NÃO seria

- Não é editor: não cria nem altera notas. O MuseScore continua sendo o lugar de editar.
- Não toca a partitura sozinho (síntese de som a partir das notas). O Verovio gera MIDI (fonte), mas tocar MIDI com bom som num celular é um projeto à parte. Quem toca é o arquivo de áudio ou o metrônomo.

## Riscos e custos

- **Desempenho em aparelhos simples:** desenhar uma partitura grande em WebView pode levar alguns segundos; desenhar uma vez e só mover o cursor depois. Verificar no tipo de aparelho do usuário.
- **Fidelidade do MusicXML:** cada exportador gera variações; algumas marcações podem sair diferentes (limites acima).
- **Layout:** rolagem horizontal contínua (uma linha, como a vista contínua do MuseScore) versus páginas; o cursor precisa "seguir" a tela (rolar ou virar página).
- **Tamanho dos arquivos:** MusicXML é texto; o `.mxl` é compactado. Muito menor que áudio.
- **Offline:** as bibliotecas precisam ir embutidas nos assets do app (como o QR code próprio), sem depender de internet.

## Plano sugerido (em etapas, cada uma numa versão nova)

1. **Prova de conceito (um aparelho):** anfitrião escolhe um `.musicxml`/`.mxl`; o aparelho desenha a partitura numa WebView e mostra o cursor andando pelo **metrônomo** da sessão. Decide OSMD x Verovio.
2. **Distribuição:** o anfitrião envia a partitura aos outros pelo mesmo caminho do áudio (`/api/score?group=`), cada aparelho a baixa uma vez; **cada grupo escolhe a sua parte**.
3. **Áudio:** marcadores de compasso/seção para acompanhar um arquivo gravado; rolagem automática ou virar página.
4. **Extras:** zoom, tema escuro, repetições/codas, ferramenta opcional no PC para PDF → MusicXML (Audiveris), edição de marcadores.

## Perguntas ao usuário antes de começar

- As partituras vêm de MuseScore (então já há `.mscz`/MusicXML), de PDFs prontos, ou de fotos?
- O principal é acompanhar o **metrônomo** (mais simples) ou um **áudio gravado** (precisa de marcadores)?
- Cada músico deve ver **só a sua parte**?

## Fontes

- OpenSheetMusicDisplay: https://unpkg.com/opensheetmusicdisplay@2.0.0/README.md
- Verovio, formatos de saída e timemap: https://book.verovio.org/toolkit-reference/output-formats.html
- Audiveris, manual: https://audiveris.github.io/audiveris/_pages/handbook
