# Antes de publicar a Bela na Google Play

Hoje o app é feito para testes pessoais (APK instalado direto). Para publicar, faltam estes passos.

## 1. Tirar as chaves do APK
- Criar um backend pequeno (por exemplo Cloud Run ou Cloud Functions) que receba a pergunta e chame
  o Gemini e a YouTube Data API com as chaves guardadas no servidor.
- Proteger o backend com **Firebase App Check (Play Integrity)**, para só o app oficial conseguir usar.
- No app, trocar `GeminiSearch` e a busca do `YouTubeMedia` por chamadas ao backend. As interfaces
  `SearchPort` e `MediaPort` já isolam isso.

## 2. Permissões com regras especiais
- **`READ_CALL_LOG`** é uma permissão restrita: a Play só aceita com um formulário de declaração e
  para funções principais permitidas. Alternativa recomendada: usar um **`CallScreeningService`**
  com o papel `ROLE_CALL_SCREENING` (pedido com `RoleManager.createRequestRoleIntent`). Esse papel
  é o caminho aprovado para identificar quem liga. Ele entrega o número da chamada recebida sem
  `READ_CALL_LOG` e permitiria anunciar o nome por voz no momento em que o telefone toca. Esse é o
  próximo passo da Etapa 2.
- **`ANSWER_PHONE_CALLS`**, **`CALL_PHONE`**, **`READ_CONTACTS`**, **`RECORD_AUDIO`**: perigosas,
  mas sem formulário especial. Precisam aparecer na política de privacidade e na seção
  "Segurança dos dados".
- **Não** usar serviço de acessibilidade (a Play exige justificativa e revisão), sobreposição de
  tela nem leitura de notificações.

## 3. Documentos e formulários
- Política de privacidade pública (URL), explicando que:
  - o áudio é processado pelo serviço de reconhecimento de voz do Google no aparelho;
  - as perguntas são enviadas à API do Gemini (Google) para pesquisa;
  - os contatos são lidos só no aparelho e nunca enviados;
  - o número de quem liga fica só durante a chamada;
  - nenhuma conversa é gravada.
- Formulário "Segurança dos dados" coerente com a política.
- Declaração de acessibilidade/descrição clara na página do app.

## 4. Técnico
- Assinatura de release com chave própria (hoje o APK de teste é "debug"), guardada em segredo do CI.
- `targetSdk` exigido pela Play no ano da publicação.
- Gerar `.aab` (`gradle bundleRelease`).
- Ativar `isMinifyEnabled` com regras de ProGuard revisadas.
- Teste fechado (closed testing) com pessoas cegas usando TalkBack antes da produção.

## 5. Escala
- Quotas: a YouTube Data API tem 10.000 unidades/dia por projeto (cada busca custa 100), o que não
  basta para muitos usuários. Será preciso pedir aumento de quota ou usar cache no backend.
- Gemini: conferir os limites e preços do modelo escolhido no momento da publicação.
