# Bela — assistente de voz particular 

App Android com a **Bela**, uma assistente de voz por IA (Gemini Live, áudio nativo), pensado
para uma pessoa com deficiência visual: um único botão enorme na tela — ou **3 cliques rápidos
no botão de aumentar volume, de qualquer lugar** (mesmo com o app fechado ou o celular bloqueado)
— ela ativa o assistente, fala o que precisa, e o app responde por voz. Também consegue:

- Tocar uma música/artista no **YouTube Music**, por pedido de voz.
- **Ligar** para um contato da agenda, por pedido de voz (usando o nome da pessoa).

Não é publicado na Play Store — é instalado direto no celular via APK (sideload).

## Como funciona por baixo dos panos

O app conecta direto (via WebSocket) na **Gemini Live API** usando a sua chave de API do
Google AI Studio (a mesma que aparece no seu dashboard com quota ilimitada para os modelos de
áudio "Live"). Não usa Firebase nem nenhuma chave intermediária — é a sua chave, direto.

- Toque no botão (ou aperte o volume 3x) → o app grava o microfone e manda o áudio em tempo real
  para o Gemini.
- O próprio Gemini detecta quando você parou de falar e começa a responder em áudio (o app
  configura essa detecção — `realtimeInputConfig.automaticActivityDetection` em
  [`LiveMessages.kt`](app/src/main/java/com/theuzxl13/assistente/gemini/LiveMessages.kt) — pra
  ser mais tolerante com pausas no meio de frases longas, em vez de cortar a fala cedo demais).
- **Diga "por favor" em qualquer parte do pedido** e a Bela responde na hora, sem esperar a pausa
  de silêncio configurada — o app liga a transcrição ao vivo da fala (`inputAudioTranscription`)
  só pra detectar essa palavra e avisar a Live API que pode responder (`audioStreamEnd`), o que
  deixa a resposta bem mais rápida quando você já sabe que terminou de falar.
- Depois que a Bela termina de responder, a sessão se encerra — pra fazer outro pedido, é só
  tocar no botão ou apertar o volume 3x de novo.
- Se você pedir para tocar uma música ou ligar para alguém, o Gemini "chama" uma função que o
  app implementa nativamente (abrir o YouTube Music / discar o telefone) — isso é feito com
  *function calling* da Live API.

Modelo usado por padrão: `gemini-2.5-flash-native-audio-preview-12-2025` (constante
`GEMINI_LIVE_MODEL` em [`LiveMessages.kt`](app/src/main/java/com/theuzxl13/assistente/gemini/LiveMessages.kt)).
Se no seu AI Studio aparecer outro nome de modelo Live que você prefira usar (ex: uma versão mais
nova), é só trocar essa constante.

## Ativar de qualquer lugar: 3 cliques no volume

O [`VolumeButtonAccessibilityService`](app/src/main/java/com/theuzxl13/assistente/VolumeButtonAccessibilityService.kt)
detecta **3 toques rápidos seguidos no botão de aumentar volume**, em qualquer lugar do sistema
(app fechado, tela bloqueada, dentro de outro app), e abre a Bela automaticamente, já pronta pra
ouvir.

Isso só é possível usando um **Serviço de Acessibilidade** do Android — o mesmo tipo de permissão
usada por leitores de tela como o TalkBack. Não dá pra ativar com um popup comum; é preciso ir
manualmente em **Configurações → Acessibilidade → Bela** e ligar o interruptor (o app mostra um
aviso na primeira abertura, com um botão "Ativar" que leva direto pra essa tela). O Android exibe
um aviso padrão do sistema sobre o que serviços de acessibilidade podem fazer — nesse caso, o
serviço só observa os botões físicos de volume, não lê nem coleta nada da tela.

O volume pode subir um pouquinho como efeito colateral dos 3 cliques (o app não bloqueia o
funcionamento normal do botão) — é um efeito colateral aceitável em troca de não interferir no uso
normal do volume.

Quando detecta os 3 cliques, o [`AssistantLauncher`](app/src/main/java/com/theuzxl13/assistente/AssistantLauncher.kt)
tenta abrir a tela direto (se tiver a permissão "Aparecer sobre outros apps" — pedida na primeira
abertura do app) ou, sem essa permissão, dispara uma notificação de **tela cheia** que abre
sozinha quando o celular está bloqueado, mas exige um toque quando está desbloqueado (é uma
proteção do próprio Android contra apps abrirem por cima de outros sem avisar).

**Importante — configurações restritas (Android 13+):** como o app é instalado fora da Play
Store, o Android pode bloquear por segurança a ativação do serviço de acessibilidade até você
liberar manualmente em **Configurações → Apps → Bela → menu (⋮) → Permitir configurações
restritas**.

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

Abra o Android Studio → **Open** → selecione a pasta `assistente-mae`.

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
3. Abra o app uma vez você mesmo para conceder as permissões (microfone, telefone, contatos,
   notificações) e ativar "aparecer sobre outros apps" e o serviço de acessibilidade do volume
   (nas Configurações do Android, seguindo os avisos que o app mostra), já que ela não vai
   conseguir navegar nesses diálogos sem enxergar.

## Ideias para depois (fora do escopo inicial)

- Lembretes de remédio/rotina por voz.
- Abrir o app automaticamente ao ligar o celular.
- Detecção de palavra de ativação ("Ei, Bela"), em vez de precisar de um gesto/botão.

## Estrutura do código

```
app/src/main/java/com/theuzxl13/assistente/
  MainActivity.kt                     # tela única, estados do botão, orquestra tudo
  AssistantLauncher.kt                # abre a Bela de fora (3 cliques no volume)
  VolumeButtonAccessibilityService.kt # detecta 3 cliques no volume em qualquer lugar do sistema
  gemini/GeminiLiveClient.kt   # conexão WebSocket com a Live API
  gemini/LiveMessages.kt       # monta/interpreta as mensagens JSON da Live API
  audio/AudioCapture.kt        # microfone -> PCM 16kHz -> base64
  audio/AudioPlayer.kt         # toca a resposta em áudio (PCM 24kHz)
  tools/AssistantTools.kt      # implementação de "tocar música" e "ligar para"
  tools/ContactsHelper.kt      # busca de contato por nome
```
