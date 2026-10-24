# Plano de testes da Bela

O projeto só está pronto quando estes testes passam **no Moto G75 de verdade**. Compilar não basta.

## 1. Testes automatizados (rodam no GitHub Actions a cada push)

O APK só é gerado se todos passarem (`gradle testDebugUnitTest`).

| Arquivo | O que cobre |
|---|---|
| `IntentParserTest` | variações naturais de "ligue para…", emergência, atender/recusar/desligar/quem está ligando, pare/cancele/repita/mais devagar/me ajude/volte/o que posso fazer, sim/não/ordinais, música, YouTube, hora/data/volume/abrir app, perguntas que **não** podem virar comando ("a que horas abre a farmácia", "o prédio mais alto", "como se chama…") |
| `ConversationEngineTest` | confirmação antes de ligar; "não" cancela; nomes repetidos lidos e escolhidos por ordinal e por nome completo; contato inexistente; permissão negada; ligação que não começou **não** é anunciada como iniciada; quem está ligando (contato e número desconhecido, sem inventar nome); atender sem ligação tocando; desligar; repetir; velocidade; música com títulos lidos e escolha; sem chave do YouTube; pesquisa com fonte e detalhes; sem internet não finge pesquisar; abrir app; silêncio durante pergunta; falha de rede no reconhecimento; leitura de texto longo em partes |
| `PtBrTest` | horas, datas e listas faladas corretamente |
| `GeminiSearchParseTest` | separação resumo/detalhes, fontes sem repetição, resposta sem fonte, resposta vazia, escolha de modelo pela lista real da chave |
| `SearchClientsTest` | com servidor falso (MockWebServer): modelo aposentado (corpo 404 real) → troca de modelo; HTTP 400 de chave/401/403/429/500/503/outros 4xx; timeout; conexão recusada; DNS; conexão que cai; JSON inválido; resposta vazia; sem chave; Wikipédia (resumo, detalhes, lista, sem resultado, resumo que falha); Google Notícias (RSS, assunto, XML inválido); Gemini falhando e Wikipédia respondendo; chave nunca aparece no log |
| `BibleReferenceTest` | referências faladas e escritas (1 Pedro 5,7; primeira de Pedro capítulo cinco versículo sete; Salmo vinte e três; Mateus 5 do 3 ao 10; I Coríntios 13), referência inexistente, grafia de 1911 para a fala |
| `SearchQueryTest` | "pesquise no Google…" vira a pergunta; assunto para a enciclopédia; notícias com e sem assunto |
| `LiveSearchDiagnosticTest` | **chamadas reais** (só no passo "Diagnóstico da pesquisa" do CI): Gemini, lista de modelos da chave, Wikipédia, Google Notícias e as 4 frases de exemplo pelas classes do app, ponta a ponta |
| `YouTubeTitleTest` | títulos limpos para ouvir ("(Clipe Oficial)", "[HD]", "\| Ao Vivo", entidades HTML) |

## 2. Testes manuais no Moto G75 (Android 14)

Preencha a coluna **Resultado** durante o teste. Faça cada bloco **com o TalkBack desligado e
depois ligado**.

### Preparação
- [ ] Instalar o APK e abrir: a configuração inicial começa sozinha, falando.
- [ ] Negar o microfone: a Bela explica que não dá para continuar e como resolver.
- [ ] Reabrir e permitir: a configuração continua.
- [ ] Teste de voz ("olá, Bela"): ela repete o que ouviu.
- [ ] Velocidade: "mais devagar" e "mais rápido" mudam a voz; "está bom" segue em frente.
- [ ] Permissões de contatos, telefone e registro de chamadas: uma janela por vez, cada uma explicada antes.
- [ ] Assistente digital: a Bela abre a tela certa; escolher Bela; segurar o botão de ligar abre a Bela.
- [ ] Ladrilho nas Configurações Rápidas abre a Bela ouvindo, inclusive com a tela bloqueada.

### Ligações (prioridade máxima)
| Cenário | Resultado esperado | Resultado |
|---|---|---|
| "Ligue para [contato único]" → "sim" | liga; a Bela não diz que ligou antes de a chamada começar | |
| "Ligue para [contato único]" → "não" | não liga | |
| Nome repetido (dois "Matheus") → "o segundo" | liga para o segundo | |
| Nome repetido → "Matheus Silva" | liga para o Matheus Silva | |
| Contato inexistente | pergunta outro nome; não liga | |
| Contato com celular e casa | lê "celular" e "casa" para escolher | |
| Negar a permissão de telefone | abre o discador com o número e orienta onde fica o botão | |
| Sem sinal / modo avião | a Bela avisa que não conseguiu confirmar a ligação | |
| Receber ligação de contato → segurar o botão de ligar → "quem está ligando?" | fala o nome e pergunta se quer atender | |
| Receber de número desconhecido | "número que não está na sua agenda, terminado em…"; não inventa nome | |
| Número oculto | diz que não conseguiu identificar | |
| "Atender" durante o toque | atende (**confirmar que `acceptRingingCall` funciona no Moto**) | |
| "Recusar" durante o toque | recusa e confirma | |
| Durante uma ligação → abrir a Bela → "desligue" | desliga e confirma | |
| Segurar o botão de ligar **durante o toque** | descobrir se abre a assistente ou silencia a campainha | |
| Anúncio nativo do app Telefone ligado | o nome é falado por cima do toque | |
| Chamada de emergência pelo discador do sistema | não é afetada pela Bela | |

### Conversa e acessibilidade
- [ ] "Pare" e tocar na tela interrompem a fala na hora.
- [ ] "Repita" e o botão Repetir repetem a última resposta; "não entendi" repete mais devagar.
- [ ] "Me ajude" durante uma pergunta explica o que ela está esperando.
- [ ] Silêncio depois do bipe: a conversa termina com um bipe, sem travar.
- [ ] Ruído ambiente (TV ligada) e vozes diferentes: anotar as frases que falharam.
- [ ] TalkBack ligado: ordem de foco (título → estado → Falar com a Bela → Repetir → Ajuda) e todos os botões com nome.
- [ ] TalkBack ligado: a fala da Bela não fica embolada com a do TalkBack.
- [ ] Fonte do sistema no tamanho máximo: nada cortado, botões continuam grandes.
- [ ] Fechar o app no meio da conversa e reabrir: volta normal.
- [ ] Bela aberta por cima da tela bloqueada: funciona sem desbloquear.

### Música, pesquisa e dia a dia
- [ ] "Quero ouvir Gabriela Rocha": lê 3 títulos; "o segundo" abre no YouTube e toca.
- [ ] "Outros" lê mais 3; "quero ouvir outro" depois de tocar passa para o próximo da lista.
- [ ] "Pause", "continue", "pare a música" funcionam com o YouTube tocando.
- [ ] "Bela, pesquise quem foi Moisés": "Vou pesquisar" → resumo + fonte → "mais detalhes, o próximo resultado, ou abrir no navegador?".
- [ ] "Pesquise notícias de hoje": lê a primeira manchete e o site; "próxima notícia" lê a seguinte; "abra no navegador" abre a notícia.
- [ ] "Pesquise uma receita de bolo de cenoura" → "mais detalhes" lê os passos.
- [ ] "Pesquise no Google como usar o WhatsApp": resposta com fonte.
- [ ] Modo avião: "o celular não está conectado a nenhuma rede". Wi-Fi sem internet de verdade: mensagem de conexão/DNS/timeout, **não** "pesquisa não configurada".
- [ ] Depois de um erro: "tente de novo" pesquisa de novo; "abra no navegador" abre o Google e ela avisa que não leu os resultados.
- [ ] "Leia 1 Pedro 5,7": diz a tradução (só na primeira vez), a referência e o versículo; oferece explicação, próximo versículo, capítulo inteiro.
- [ ] "Primeira de Pedro, capítulo cinco, versículo sete" e "Salmo vinte e três" funcionam falados por extenso.
- [ ] "Explica pra mim": avisa que é explicação por inteligência artificial. Sem internet: diz que precisa de internet e a leitura continua.
- [ ] "Salmo 119": lê de 10 em 10 versículos e pergunta se continua; "não" para.
- [ ] Modo avião: "leia João 3,16" funciona.
- [ ] `adb logcat -s BelaPesquisa` mostra a etapa e o código do erro, sem a pergunta nem a chave.
- [ ] Pergunta de acompanhamento ("e sem glúten?") usa o contexto.
- [ ] Sem internet: pesquisa e música avisam; hora, ligação e apps funcionam.
- [ ] "Que horas são", "que dia é hoje", "aumente o volume", "abra o WhatsApp".
- [ ] Compartilhar uma notícia do navegador com a Bela: ela lê em partes e pergunta se continua.
- [ ] Bateria: usar normalmente por um dia e conferir em Configurações → Bateria (a Bela não fica rodando em segundo plano).

## 3. O que foi testado até agora

- **Testes de JVM do domínio:** executados localmente e no CI.
- **Compilação do APK:** feita pelo GitHub Actions (o ambiente de desenvolvimento não tem o Android SDK).
- **Nenhum teste em aparelho ou emulador foi feito ainda.** Todo o item 2 está pendente no Moto G75.
