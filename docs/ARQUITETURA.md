# Bela — arquitetura, APIs e limitações

Este documento responde às perguntas feitas antes do desenvolvimento: arquitetura, versão mínima
do Android, compatibilidade com Motorola, APIs usadas, permissões, dependência de internet,
limitações do Android e como compilar e instalar.

## 1. Arquitetura

```
 Toque na tela / ladrilho / segurar o botão de ligar / "Compartilhar"
                              │
                       MainActivity  (tela única, foco de áudio, ciclo de vida)
                              │
            ┌─────────────────┴──────────────────┐
            │  domain/  (Kotlin puro, testado na JVM)  │
            │  Onboarding      ConversationEngine      │
            │  IntentParser    máquina de estados      │
            │  TextNormalizer  PtBr (frases faladas)   │
            │  Ports.kt  ← interfaces abaixo           │
            └─────────────────┬──────────────────┘
   voice/        telephony/      contacts/   media/        search/        apps/  device/  settings/
   SpeechRecognizer  TelecomManager  Contacts   YouTube Data  Gemini API +    Package  Áudio,   Shared
   TextToSpeech      TelephonyMgr    Provider   API + teclas  Google Search   Manager  rede,    Prefs
   ToneGenerator     PHONE_STATE                de mídia      grounding                papéis
```

- **domain/** não importa nada do Android. Isso permite testar toda a lógica da conversa
  (confirmações, escolhas, erros, repetição) com testes de JVM rápidos, e trocar qualquer serviço
  externo (por exemplo, outro motor de pesquisa ou de IA) implementando a mesma interface.
- **Injeção de dependências manual**, no construtor, montada na `MainActivity`. O app tem uma
  tela só e poucas dependências, então Hilt/Koin trariam mais complexidade do que benefício.
- **Views XML em vez de Jetpack Compose.** A tela é um botão grande e dois botões de apoio. Manter
  Views evita uma migração sem ganho para a usuária. Compose pode entrar mais tarde.
- **SharedPreferences em vez de Room.** Só se guardam a velocidade da fala, se a configuração
  inicial foi concluída e, durante uma chamada, o número de quem está ligando.

### Máquina de estados

`INATIVA → OUVINDO → PROCESSANDO → (FALANDO | AGUARDANDO_CONFIRMACAO | EXECUTANDO_ACAO) → … → INATIVA`,
com `ERRO` e `CANCELADA` como saídas. Regras:

- O microfone só abre depois que a fala termina: `speak()` suspende até o TextToSpeech avisar
  `onDone`, e só então o `SpeechRecognizer` começa. Os dois nunca disputam o áudio.
- Tocar no botão grande durante qualquer estado cancela a conversa (fala e microfone param na hora).
- Silêncio durante uma pergunta repete a pergunta uma vez e depois encerra a conversa.
- A conversa continua depois de uma resposta (para perguntas de acompanhamento) e termina quando
  uma ação leva para outro app (ligação, YouTube, WhatsApp) ou quando ela se despede.
- Nada é anunciado como concluído sem verificação: depois de ligar, a Bela espera o estado da
  chamada virar "em ligação"; depois de atender, desligar ou recusar, confere o novo estado.

### Por que não usar mais o Gemini Live (áudio nativo)

A versão anterior mandava o áudio direto para o Gemini Live. O novo núcleo usa o reconhecimento e
a voz do próprio Android porque isso permite:

- funcionar sem internet para horas, data, ligações, apps, volume e ajuda;
- ajustar a velocidade da fala, repetir e interromper de forma confiável;
- verificar cada ação antes de falar o resultado;
- não depender de um único serviço de IA (o Gemini fica só na pesquisa).

O custo é que a voz do Android é menos natural que a do Gemini Live.

## 2. Versão mínima do Android

- `minSdk 26` (Android 8.0): é a primeira versão com a permissão `ANSWER_PHONE_CALLS` e com
  `TelecomManager.acceptRingingCall()`.
- Recusar e desligar ligações (`TelecomManager.endCall()`) exigem Android 9 (API 28). No Android
  8 a Bela avisa que não conseguiu.
- `targetSdk 35` / `compileSdk 35`.

## 3. Compatibilidade com Motorola (Moto G75)

O Moto G75 sai de fábrica com Android 14 e usa os apps do Google:

- **Telefone do Google** como discador padrão, o que é importante para o anúncio de chamadas (seção 7);
- **app Google** como serviço de reconhecimento de voz;
- **Serviços de fala do Google** como TextToSpeech, com vozes pt-BR offline.

A Bela usa só APIs públicas e intents padrão do Android, e nenhuma API exclusiva da Motorola.
Um ponto específico da Motorola: a opção "pressionar e segurar o botão liga/desliga" fica em
**Configurações → Sistema → Gestos**, e é preciso escolher **Assistente digital**.

## 4. APIs usadas em cada função

| Função | API oficial |
|---|---|
| Ouvir | `android.speech.SpeechRecognizer` (pt-BR, até 5 alternativas, nova tentativa offline com `EXTRA_PREFER_OFFLINE`) |
| Falar | `android.speech.tts.TextToSpeech`, voz pt-BR local de maior qualidade, `USAGE_ASSISTANT` |
| Pausar a música enquanto fala | `AudioManager.requestAudioFocus(AUDIOFOCUS_GAIN_TRANSIENT)` |
| Achar contatos | `ContactsContract.CommonDataKinds.Phone` (comparação sem acento) |
| Nome de quem liga | `ContactsContract.PhoneLookup` + `TelephonyManager.ACTION_PHONE_STATE_CHANGED` |
| Fazer ligação | `Intent.ACTION_CALL` (ou `ACTION_DIAL` sem permissão) |
| Estado da chamada | `TelephonyManager.getCallState()` |
| Atender | `TelecomManager.acceptRingingCall()` |
| Recusar / desligar | `TelecomManager.endCall()` |
| Buscar vídeos | YouTube Data API v3 `search.list` |
| Tocar | `Intent.ACTION_VIEW` com o link do vídeo, no app YouTube |
| Pausar / continuar / próxima | `AudioManager.dispatchMediaKeyEvent` (teclas de mídia para o player ativo) |
| Pesquisar | Gemini API `generateContent` com `google_search` (resumo com fontes); Wikipédia REST API (`/w/rest.php/v1/search/page` + `/api/rest_v1/page/summary`); Google Notícias RSS (`news.google.com/rss`) |
| Abrir resultado / pesquisa no navegador | `Intent.ACTION_VIEW` com a URL do resultado ou `google.com/search?q=` codificada |
| Abrir apps | `PackageManager.queryIntentActivities(CATEGORY_LAUNCHER)` + `getLaunchIntentForPackage` |
| Volume | `AudioManager.adjustStreamVolume(STREAM_MUSIC)` com a barra do sistema |
| Ler texto de outro app | `Intent.ACTION_SEND` (`text/plain`) |
| Chamar de qualquer tela | `Intent.ACTION_ASSIST` (assistente padrão) e `TileService` (Configurações Rápidas) |
| TalkBack ligado? | `AccessibilityManager.isTouchExplorationEnabled()` (só consulta) |
| É a assistente padrão? | `RoleManager.isRoleHeld(ROLE_ASSISTANT)` |

## 5. Permissões

Cada permissão é pedida só quando é necessária: na etapa correspondente da configuração inicial
ou na primeira vez que a função é usada. Antes de cada janela do Android, a Bela explica por voz
para que serve a permissão.

| Permissão | Para quê | Quando é pedida |
|---|---|---|
| `RECORD_AUDIO` | ouvir a usuária | 1ª etapa da configuração |
| `READ_CONTACTS` | achar contatos pelo nome; dizer o nome de quem liga | etapa "ligações" ou 1º pedido de ligação |
| `CALL_PHONE` + `ANSWER_PHONE_CALLS` + `READ_PHONE_STATE` | ligar, atender, recusar, desligar, saber se está tocando (uma janela só: grupo Telefone) | idem |
| `READ_CALL_LOG` | receber o número de quem está ligando | etapa "ligações" |
| `INTERNET`, `ACCESS_NETWORK_STATE` | pesquisa, YouTube, reconhecimento online; checar conexão | automáticas (não perigosas) |

**Não usadas:** serviço de acessibilidade, sobreposição de tela, captura de tela, leitor de
notificações e administrador do dispositivo. Essas são as permissões que fazem apps de banco
bloquearem o celular, e a Bela não precisa de nenhuma delas.

## 6. O que depende de internet

| Funciona sem internet | Precisa de internet |
|---|---|
| hora e data, ajuda, repetir, velocidade | pesquisa (Gemini + Google Search) |
| achar contatos, ligar, atender, recusar, desligar, quem está ligando | buscar e tocar músicas e vídeos |
| abrir apps, volume, ler textos compartilhados | reconhecimento de voz, **a menos que** o pacote offline de português esteja baixado |
| voz da Bela (voz pt-BR local) | |

O reconhecimento usa o serviço do Google no aparelho. Com internet, o áudio é processado pelo
Google; sem internet, a Bela tenta o modo offline. O modo offline só funciona se o português
estiver baixado no app Google (Configurações do app Google → Voz → Reconhecimento de voz offline).
Se nenhum dos dois estiver disponível, a Bela fala que o reconhecimento precisa de internet.

## 7. Limitações do Android (e o que a Bela faz em cada caso)

### Atender chamadas

- Com a permissão `ANSWER_PHONE_CALLS`, um app comum pode atender (`acceptRingingCall`) e recusar
  ou desligar (`endCall`). Essas APIs estão marcadas como obsoletas desde o Android 10, mas
  continuam funcionando para quem tem a permissão. **Isso ainda precisa ser confirmado no Moto G75.**
- **A Bela não começa a ouvir sozinha quando o telefone toca.** Desde o Android 14, um app em
  segundo plano não pode abrir o microfone nem um serviço de microfone em primeiro plano a partir
  de um aviso do sistema. O fluxo é este: o telefone toca → ela segura o botão de ligar (ou toca
  no ladrilho) → a Bela abre → "quem está ligando?" / "atender" / "recusar".
  **Ainda precisa ser testado** se segurar o botão de ligar durante o toque abre a assistente ou
  só silencia a campainha.
- **Anúncio falado de quem está ligando:** por enquanto, a recomendação é a função nativa do app
  Telefone do Google: **Telefone → Configurações → Anúncio do identificador de chamadas → Sempre**.
  Ela fala o nome do contato por cima do toque, é confiável e não exige nada da Bela. Com o
  TalkBack ligado, a tela de chamada também é lida.
- Viva-voz, silenciar o microfone e controles completos durante a chamada exigiriam que a Bela
  fosse o **app de telefone padrão** (`InCallService`). Nesse caso, ela teria que desenhar toda a
  tela de chamada, cuidar de chamadas de emergência e de chamadas simultâneas, e a Google Play
  passaria a exigir que ela fosse um discador completo. Isso é arriscado para a função mais
  importante do celular, por isso **não foi feito**. Durante a ligação, o app Telefone do
  sistema continua responsável por tudo, e emergências não são afetadas.

### Palavra de ativação ("Bela") em segundo plano

Não foi implementada. Ouvir o tempo todo exigiria um serviço com o microfone sempre aberto. Isso
gasta bateria, levanta questões de privacidade e é bloqueado ou encerrado pelo sistema em
segundo plano. As formas de chamar a Bela são:

1. abrir o app (ela já começa ouvindo);
2. tocar no ladrilho "Bela" nas Configurações Rápidas, que funciona até com a tela bloqueada;
3. **segurar o botão de ligar**, se ela for a assistente digital padrão;
4. "Compartilhar → Bela" num texto, para ela ler.

### YouTube

A Bela busca, lê os títulos e abre o vídeo escolhido no app oficial. Ela não controla o app do
YouTube por dentro. Pausar, continuar e "próxima" usam as teclas de mídia do Android, que o
YouTube atende quando está tocando. A Bela não promete: reprodução com a tela desligada,
ausência de anúncios ou recursos do YouTube Premium. Sem a chave da YouTube Data API, ela só
consegue abrir a busca, e os resultados ficam na tela sem serem lidos.

### Pesquisa

A pesquisa combina três fontes reais (`search/WebSearch.kt`):

| Pedido | 1ª fonte | Se falhar |
|---|---|---|
| notícias ("notícias de hoje", "notícias sobre…") | Google Notícias (RSS, sem chave): manchetes reais, uma por vez | Gemini |
| qualquer outra pergunta | Gemini + Pesquisa Google: resumo com os sites usados | Wikipédia (sem chave): resumo do artigo + lista de artigos |

- A Bela sempre diz de onde veio a informação: "a informação é de…", "encontrei na Wikipédia…",
  "notícias do Google Notícias". Se o Gemini responder sem fontes, ela avisa que a resposta não
  veio de uma fonte conferida.
- Depois de cada resposta ela oferece **mais detalhes**, **o próximo resultado** (ou a próxima
  notícia) e **abrir no navegador**. Perguntas de acompanhamento usam o contexto.
- Se tudo falhar, ela diz o motivo real (veja abaixo) e oferece **tentar de novo** ou **abrir a
  pesquisa do Google no navegador**. Nesse caso, avisa que não leu aqueles resultados.
- O modelo do Gemini não é fixo: o app tenta `gemini-flash-latest`. Se a API responder 404
  (modelo aposentado ou bloqueado para a chave), consulta a lista de modelos da própria chave e
  passa a usar o melhor modelo "flash" disponível.

#### Mensagens de erro (cada causa tem a sua)

| Causa (`SearchError`) | Origem técnica | O que a Bela fala |
|---|---|---|
| `NO_CONNECTION` | `ConnectException` / sem rede | "Não consegui me conectar ao serviço de pesquisa. A internet do celular parece estar fora do ar." |
| `DNS` | `UnknownHostException` | "Não consegui encontrar o endereço do serviço de pesquisa…" |
| `TIMEOUT` | `SocketTimeoutException` / callTimeout | "A pesquisa demorou demais." + "tentar de novo?" |
| `UNAUTHORIZED` | HTTP 400 de chave, 401, 403 | "O serviço de pesquisa recusou a chave de acesso…" |
| `MODEL_UNAVAILABLE` | HTTP 404 | "O serviço de pesquisa configurado não está mais disponível para esta chave…" |
| `RATE_LIMITED` | HTTP 429 | "O limite de pesquisas do serviço foi atingido…" |
| `SERVER` | HTTP 5xx | "O serviço de pesquisa está com problemas agora." |
| `BAD_REQUEST` | outros 4xx | "O serviço de pesquisa não aceitou o pedido." |
| `EMPTY` | nenhum resultado | "Não encontrei resultados para essa pergunta." |
| `INVALID_RESPONSE` | JSON/XML inválido | "O serviço de pesquisa respondeu de um jeito que eu não entendi." |
| `UNEXPECTED` | outra exceção | "Aconteceu um erro inesperado na pesquisa." |
| não configurado | sem chave e sem resultado | "O serviço de pesquisa precisa ser configurado neste aplicativo." |

"Sem internet" só é falado quando o Android não tem **nenhuma** rede com acesso à internet. A
checagem não exige mais a "validação" do Android, que falha em algumas redes que funcionam.

Registro técnico: logcat, tag `BelaPesquisa`, com a etapa, o código do erro, o HTTP e o modelo.
Nunca registra a pergunta nem a chave. Para ver: `adb logcat -s BelaPesquisa`.

#### Diagnóstico de 21/10/2026: por que a pesquisa falhava

Chamada real feita no CI com a chave do projeto (`LiveSearchDiagnosticTest`):

```
gemini-2.5-flash com google_search -> HTTP 404 | status=NOT_FOUND
mensagem=This model models/gemini-2.5-flash is no longer available to new users.
Please update your code to use models/gemini-3.8-flash ...
Modelos disponíveis para a chave -> HTTP 200 | ..., gemini-flash-latest, ..., gemini-3.8-flash, ...
```

O app fixava `gemini-2.5-flash`, que o Google restringiu a contas que já o usavam (changelog de
18/09/2026). O 404 caía no mesmo `SearchResult.Failure` de qualquer erro, e era falado como "pode
ser a internet". A chave e a internet estavam funcionando.

### Chaves de API

Para uso pessoal, as chaves do Gemini e do YouTube entram no APK pelo GitHub Actions (secrets).
**Não estão no código-fonte**, mas qualquer pessoa com o APK consegue extraí-las. Antes de
publicar na Google Play, elas precisam ir para um backend próprio (veja `GOOGLE_PLAY.md`).

## 8. Compilar e instalar

- **APK pelo GitHub (sem instalar nada):** cada push gera o APK na aba **Actions → Gerar APK**,
  item **Bela-apk**. As chaves vêm de **Settings → Secrets and variables → Actions**
  (`GEMINI_API_KEY` e, opcionalmente, `YOUTUBE_API_KEY`). Os testes automatizados rodam antes, e
  o APK só é gerado se todos passarem.
- **Android Studio:** veja o README.

## Dependências e versões

| Dependência | Versão | Motivo |
|---|---|---|
| Android Gradle Plugin | 8.6.0 | compatível com compileSdk 35 e Gradle 8.9 |
| Kotlin | 2.0.20 | estável, compatível com o AGP acima |
| Gradle | 8.9 | exigido pelo AGP 8.6 |
| androidx.core-ktx | 1.13.1 | APIs de compatibilidade |
| androidx.appcompat | 1.7.0 | Activity e tema |
| material | 1.12.0 | botões e tema Material 3 |
| androidx.activity-ktx | 1.9.2 | `registerForActivityResult` (permissões) |
| androidx.lifecycle-runtime-ktx | 2.8.6 | `lifecycleScope` |
| okhttp | 4.12.0 | HTTPS para YouTube e Gemini |
| kotlinx-coroutines | 1.8.1 | conversa assíncrona e cancelável |
| junit / coroutines-test / org.json | 4.13.2 / 1.8.1 / 20240303 | testes de JVM |

São versões estáveis que já compilam juntas neste projeto. Elas não são necessariamente as mais
novas: atualizar a stack é uma tarefa separada, feita com o CI verde a cada passo.
