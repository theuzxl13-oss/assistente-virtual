# Bela — assistente de voz particular 

App Android com a **Bela**, uma assistente de voz por IA (Gemini Live, áudio nativo), pensado
para uma pessoa com deficiência visual: um único botão enorme na tela — ou o **ladrilho da Bela
nas Configurações Rápidas do Android**, acessível de qualquer lugar (até com a tela bloqueada,
puxando a barra de notificações) — ela ativa o assistente, fala o que precisa, e o app responde
por voz. Também consegue:

- Tocar uma música/artista no **YouTube Music**, por pedido de voz.
- **Ligar** para um contato da agenda, por pedido de voz (usando o nome da pessoa).

Não é publicado na Play Store — é instalado direto no celular via APK (sideload).

## Como funciona por baixo dos panos

O app conecta direto (via WebSocket) na **Gemini Live API** usando a sua chave de API do
Google AI Studio (a mesma que aparece no seu dashboard com quota ilimitada para os modelos de
áudio "Live"). Não usa Firebase nem nenhuma chave intermediária — é a sua chave, direto.

- Toque no botão (ou no ladrilho das Configurações Rápidas) → o app grava o microfone e manda o
  áudio em tempo real para o Gemini.
- O próprio Gemini detecta quando você parou de falar e começa a responder em áudio (o app
  configura essa detecção — `realtimeInputConfig.automaticActivityDetection` em
  [`LiveMessages.kt`](app/src/main/java/com/theuzxl13/assistente/gemini/LiveMessages.kt) — pra
  ser mais tolerante com pausas no meio de frases longas, em vez de cortar a fala cedo demais).
- **Diga "por favor" em qualquer parte do pedido** e a Bela responde na hora, sem esperar a pausa
  de silêncio configurada — o app liga a transcrição ao vivo da fala (`inputAudioTranscription`)
  só pra detectar essa palavra e avisar a Live API que pode responder (`audioStreamEnd`), o que
  deixa a resposta bem mais rápida quando você já sabe que terminou de falar.
- Depois que a Bela termina de responder, a sessão se encerra — pra fazer outro pedido, é só
  tocar no botão ou no ladrilho de novo.
- Se você pedir para tocar uma música ou ligar para alguém, o Gemini "chama" uma função que o
  app implementa nativamente (abrir o YouTube Music / discar o telefone) — isso é feito com
  *function calling* da Live API.

Modelo usado por padrão: `gemini-2.5-flash-native-audio-preview-12-2025` (constante
`GEMINI_LIVE_MODEL` em [`LiveMessages.kt`](app/src/main/java/com/theuzxl13/assistente/gemini/LiveMessages.kt)).
Se no seu AI Studio aparecer outro nome de modelo Live que você prefira usar (ex: uma versão mais
nova), é só trocar essa constante.

## Ativar de qualquer lugar: ladrilho nas Configurações Rápidas

O [`BelaTileService`](app/src/main/java/com/theuzxl13/assistente/BelaTileService.kt) adiciona um
ladrilho da Bela nas **Configurações Rápidas** do Android (o painel que abre puxando a barra de
notificações duas vezes, ou uma vez já na tela de bloqueio) — toca nele de qualquer lugar do
sistema e a Bela abre pronta pra ouvir.

Pra aparecer lá, é preciso adicionar o ladrilho uma vez (puxa as Configurações Rápidas → ícone de
lápis/editar → arrasta o ladrilho "Bela" pro painel) — é a forma padrão do Android de configurar
qualquer ladrilho, sem precisar de nenhuma tela de permissão especial.

## Ativar segurando o botão de ligar (assistente padrão)

A Bela pode ser escolhida como **assistente digital padrão** do Android, no lugar do Google
Assistente. Aí, **segurar o botão de ligar** abre a Bela já ouvindo — de qualquer lugar, até com a
tela bloqueada. O app só declara que sabe responder ao pedido de assistente
(`android.intent.action.ASSIST` no `AndroidManifest.xml`); não usa a versão de assistente que lê o
conteúdo da tela, então não ganha nenhum acesso novo.

Configuração, uma vez só (Moto G, Android 14/15):

1. **Configurações → Apps → Apps padrão → Assistente digital** (ou "App assistente digital") →
   escolha **Bela**.
2. **Configurações → Sistema → Gestos → Pressionar e segurar o botão liga/desliga** (o nome pode
   variar um pouco) → escolha **Assistente digital** em vez de "Menu liga/desliga". O menu de
   desligar continua acessível apertando liga/desliga + aumentar volume juntos.

Com a tela bloqueada, a Bela abre por cima da tela de bloqueio (`showWhenLocked`) pra ela não
precisar desbloquear — o que também significa que dá pra pedir música ou ligação sem desbloquear,
igual ao Google Assistente.

### Por que não usar chacoalhar, botão de volume ou sobreposição de tela

Versões anteriores deste app usaram um serviço de **Acessibilidade** (pra detectar cliques no
volume) combinado com a permissão **"Aparecer sobre outros apps"** (`SYSTEM_ALERT_WINDOW`) pra
abrir a tela sozinha de qualquer lugar. Essa combinação especificamente é a mesma "assinatura" de
comportamento usada por trojans bancários Android (tipo BRata, PixPirate) pra roubar senha e fazer
transferências — então os sistemas antifraude de apps de banco passaram a bloquear o próprio banco
nesse celular, tratando a Bela como app suspeito.

O ladrilho de Configurações Rápidas resolve isso porque é uma API do Android feita exatamente pra
esse tipo de atalho, sem nenhuma permissão sensível — nada de acessibilidade, nada de sobreposição
de tela, nada que pareça com malware bancário.

## Tocar a música automaticamente (YouTube Data API)

Por padrão, um comando genérico do Android pedindo pra "tocar X" nem sempre faz o YouTube Music
realmente começar a tocar sozinho (alguns apps só abrem a tela de busca). Pra resolver isso de
forma confiável, o app usa a **YouTube Data API v3** pra achar o vídeo certo e abrir ele já
tocando. Isso é opcional — sem essa chave, o app ainda funciona, só volta a abrir a busca do
YouTube Music (aí ela precisaria tocar no resultado).

Como gerar a chave (gratuita, mesmo se você não tiver usado o Cloud Console antes):

1. Acesse [console.cloud.google.com](https://console.cloud.google.com/) com a mesma conta Google
   do seu AI Studio.
2. No topo, selecione o mesmo projeto que você usou pro Gemini (ex: "Default Gemini Project" ou
   o que apareceu ao criar a chave do Gemini).
3. Vá em **APIs e serviços → Biblioteca**, procure por **"YouTube Data API v3"** e clique em
   **Ativar**.
4. Vá em **APIs e serviços → Credenciais → Criar credenciais → Chave de API**.
5. (Recomendado) Clique na chave criada e em "Restrições de API" marque só a "YouTube Data API
   v3", pra essa chave não poder ser usada pra mais nada.
6. Cole essa chave em `local.properties`, na linha `YOUTUBE_API_KEY=...`.

## Pré-requisitos

- [Android Studio](https://developer.android.com/studio) instalado (ele já traz Java, Android SDK
  e Gradle — não precisa instalar nada disso separadamente).
- Uma chave de API do Gemini gerada em [aistudio.google.com](https://aistudio.google.com/apikey).
- Um celular Android (o dela, ou outro para testar) com a depuração USB ativada, **ou** disposição
  para instalar o APK manualmente depois.

## Passo a passo

### 1. Abrir o projeto

Abra o Android Studio → **Open** → selecione a pasta `assistente-virtual`.

> O projeto não inclui o `gradlew`/`gradle-wrapper.jar` (são arquivos binários). Na primeira vez
> que abrir, o Android Studio vai avisar algo como *"Gradle wrapper is missing"* — é só clicar em
> **OK** / deixar o Android Studio recriar o wrapper com a distribuição embutida dele, ou usar
> **File > Sync Project with Gradle Files**. Isso é automático e só acontece uma vez.

### 2. Configurar sua chave da API

1. Copie o arquivo `local.properties.example` e renomeie a cópia para `local.properties` (mesma
   pasta, raiz do projeto).
2. Abra o `local.properties` e cole sua chave na linha `GEMINI_API_KEY=...`.
3. Esse arquivo **nunca** é enviado ao Git (já está no `.gitignore`) — sua chave fica só na sua
   máquina.

### 3. Testar

Conecte um celular Android por USB (com depuração USB ativada) ou use um emulador, e clique em
**Run ▶** no Android Studio. Teste:

- Tocar no botão e fazer uma pergunta simples.
- Pedir "toca uma música do [artista]" — deve abrir o YouTube Music.
- Pedir "liga para [nome de algum contato de teste]" — deve iniciar uma ligação de verdade
  (o app pede permissão de telefone/contatos na primeira abertura).

### 4. Gerar o APK para instalar no celular dela

No Android Studio: **Build > Build Bundle(s) / APK(s) > Build APK(s)**. Ao terminar, clique em
**locate** para achar o arquivo `app-debug.apk` (fica em `app/build/outputs/apk/debug/`).

Para instalar no celular dela:
1. Envie o `.apk` para o celular dela (cabo USB, WhatsApp Web, Google Drive, e-mail — qualquer
   jeito).
2. No celular dela, toque no arquivo `.apk` para instalar. O Android vai pedir para permitir
   "instalar apps de fontes desconhecidas" para o app usado para abrir o arquivo (Arquivos,
   WhatsApp, etc.) — autorize.
3. Abra o app uma vez você mesmo para conceder as permissões (microfone, telefone, contatos), já
   que ela não vai conseguir navegar nesses diálogos sem enxergar.
4. Adicione o ladrilho da Bela nas Configurações Rápidas (puxa a barra de notificações duas
   vezes → ícone de editar/lápis → arrasta "Bela" pro painel) — assim ela consegue ativar o
   assistente de qualquer lugar sem precisar abrir o app manualmente.

## Ideias para depois (fora do escopo inicial)

- Lembretes de remédio/rotina por voz.
- Abrir o app automaticamente ao ligar o celular.
- Virar o **assistente padrão do Android** (`VoiceInteractionService`), ativável segurando o botão
  Home ou com o gesto de canto — é a forma mais "nativa" possível de disparar por voz (como o
  Google Assistant), mas dá bem mais trabalho pra implementar que o ladrilho atual.
- Ler mensagens do WhatsApp em voz alta (via acesso a notificações) e/ou abrir uma mensagem já
  escrita pra ela confirmar o envio.

## Estrutura do código

```
app/src/main/java/com/theuzxl13/assistente/
  MainActivity.kt              # tela única, estados do botão, orquestra tudo
  BelaTileService.kt           # ladrilho das Configurações Rápidas pra ativar de qualquer lugar
  gemini/GeminiLiveClient.kt   # conexão WebSocket com a Live API
  gemini/LiveMessages.kt       # monta/interpreta as mensagens JSON da Live API
  audio/AudioCapture.kt        # microfone -> PCM 16kHz -> base64
  audio/AudioPlayer.kt         # toca a resposta em áudio (PCM 24kHz)
  tools/AssistantTools.kt      # implementação de "tocar música" e "ligar para"
  tools/ContactsHelper.kt      # busca de contato por nome
```
