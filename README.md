# Bela — sua assistente para o dia a dia

App Android nativo (Kotlin) com uma assistente de voz feita para uma pessoa com **cegueira total**.
Tudo é feito falando e ouvindo, em português do Brasil, sem precisar enxergar a tela nem acertar
botões pequenos.

> Desenvolvido inicialmente para uso pessoal e testes num **Moto G75 (Android 14)**, instalado por
> APK. O que falta para publicar na Google Play está em [`docs/GOOGLE_PLAY.md`](docs/GOOGLE_PLAY.md).

## O que a Bela faz

| Pedido (do jeito que ela quiser falar) | O que acontece |
|---|---|
| "Ligue para o Matheus" | procura na agenda, confirma ("Encontrei Matheus Marcelino. Quer que eu ligue?") ou lê as opções se houver nomes repetidos, e liga |
| "Quem está me ligando?" · "Atender" · "Recusar" · "Desligue" | diz o nome (ou "número que não está na agenda, terminado em…"), atende, recusa ou desliga e confirma |
| "Ligue para o SAMU / polícia / bombeiros" | confirma e liga para 192, 190 ou 193 |
| "Quero ouvir Gabriela Rocha" · "Toque um louvor" | busca no YouTube, lê três títulos, você escolhe ("o segundo") e ele abre tocando |
| "Pause" · "Continue" · "Pare a música" · "Quero ouvir outro" | controla o player pelas teclas de mídia do Android |
| "Pesquise uma receita de bolo de cenoura" · qualquer pergunta | pesquisa no Google, lê um resumo, diz a fonte e oferece mais detalhes |
| "Que horas são?" · "Que dia é hoje?" | responde, mesmo sem internet |
| "Abra o WhatsApp" · "Aumente o volume" | abre o app e ajusta o volume |
| Compartilhar um texto com a Bela | lê em voz alta, em partes |
| "Pare" · "Cancele" · "Repita" · "Fale mais devagar/rápido" · "Não entendi" · "Me ajude" · "Volte" · "O que posso fazer?" | controla a conversa a qualquer momento |

Ela **nunca diz que fez algo sem verificar** (por exemplo, só considera a ligação iniciada quando o
Android informa que a chamada começou) e **sempre pede confirmação antes de ligar**.

## Como chamar a Bela

1. **Abrir o app:** ela já começa ouvindo. Tocar em qualquer lugar do botão grande fala ou interrompe.
2. **Segurar o botão de ligar** de qualquer tela, até bloqueado, depois de escolher a Bela como assistente:
   **Configurações → Apps → Apps padrão → App assistente digital → Bela** e
   **Configurações → Sistema → Gestos → Pressionar e segurar o botão liga/desliga → Assistente digital**.
3. **Ladrilho "Bela"** nas Configurações Rápidas (puxar a barra de notificações → lápis → arrastar "Bela").

Não existe palavra de ativação "Bela" em segundo plano. O motivo está em [`docs/ARQUITETURA.md`](docs/ARQUITETURA.md#7-limitações-do-android-e-o-que-a-bela-faz-em-cada-caso).

**Recomendado no Moto:** ativar o anúncio nativo de chamadas do app Telefone do Google, que fala o nome
de quem liga por cima do toque: **Telefone → Configurações → Anúncio do identificador de chamadas → Sempre**.

## Primeira vez

Ao abrir pela primeira vez, a Bela conduz a configuração **por voz**: se apresenta, pede o microfone,
testa se ouve bem, ajusta a velocidade da fala, pede as permissões de ligação (uma de cada vez,
explicando cada uma), verifica a internet e o TalkBack e explica como chamá-la. Em qualquer pergunta
dá para dizer "repita" ou "me ajude".

As janelas de permissão são do próprio Android. Se a usuária não usa o TalkBack, é bom ter alguém por
perto nesse momento.

## Gerar o APK

### Pelo GitHub (sem instalar nada no computador)

1. Em **Settings → Secrets and variables → Actions**, cadastre:
   - `GEMINI_API_KEY`: obrigatória (pesquisa). Gere em [aistudio.google.com](https://aistudio.google.com/apikey).
   - `YOUTUBE_API_KEY`: opcional, mas sem ela a Bela não consegue ler os títulos dos vídeos. Gere no
     [Google Cloud Console](https://console.cloud.google.com/) ativando a "YouTube Data API v3". É
     gratuita: 10.000 unidades por dia, cerca de 100 buscas.
2. Cada push roda os testes automatizados e, se passarem, gera o APK em **Actions → Gerar APK →
   Bela-apk** (também dá para rodar manualmente em **Run workflow**).

### Pelo Android Studio

1. **Open** → pasta do projeto. Se o Android Studio avisar que falta o Gradle wrapper, aceite
   recriá-lo (o projeto usa Gradle 8.9).
2. Copie `local.properties.example` para `local.properties` e preencha `GEMINI_API_KEY` e
   `YOUTUBE_API_KEY`. Esse arquivo não vai para o Git.
3. Testes: `gradle testDebugUnitTest`. APK: **Build → Build APK(s)** ou `gradle assembleDebug`
   (fica em `app/build/outputs/apk/debug/`).
4. **Emulador:** crie um dispositivo com Google Play (imagem "Google APIs/Play"), para ter o
   reconhecimento e a voz do Google, e ative o microfone do computador em
   *Extended controls → Microphone*. Ligações podem ser simuladas em *Extended controls → Phone*.

### Instalar no Motorola

1. Mande o `Bela.apk` para o celular (WhatsApp como **Documento**, cabo, Drive…).
2. Toque no arquivo e permita "instalar apps desconhecidos" para o app usado para abrir.
3. Se aparecer "App não instalado", desinstale a versão anterior: cada APK de teste sai assinado com
   uma chave diferente.

## Documentação

- [`docs/ARQUITETURA.md`](docs/ARQUITETURA.md): arquitetura, APIs de cada função, permissões, o que
  precisa de internet, limitações do Android (atender chamadas, palavra de ativação, YouTube),
  dependências e versões.
- [`docs/PLANO_DE_TESTES.md`](docs/PLANO_DE_TESTES.md): testes automatizados e roteiro de testes no Moto G75.
- [`docs/GOOGLE_PLAY.md`](docs/GOOGLE_PLAY.md): o que fazer antes de publicar.

## Privacidade

- Nenhuma conversa é gravada ou guardada.
- A fala é transcrita pelo serviço de reconhecimento de voz do Google no aparelho. As perguntas de
  pesquisa vão para a API do Gemini (Google). Os contatos são lidos só no celular.
- O número de quem está ligando fica guardado só durante a chamada e é apagado ao desligar.
- A Bela nunca pede senhas, códigos ou dados bancários.
- Sem serviço de acessibilidade, sobreposição de tela, captura de tela ou leitura de notificações: são as
  permissões que fazem apps de banco bloquearem o celular.
- **Atenção:** nos APKs de teste, as chaves de API ficam dentro do APK. Não distribua o APK para
  outras pessoas. Para a Play, elas vão para um backend (veja `docs/GOOGLE_PLAY.md`).
