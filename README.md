# J.A.R.V.I.S. — Fase 1

Assistente de voz pessoal para Android. Você toca no orb e fala. O app transcreve em pt-BR, manda o texto para o Claude e lê a resposta em voz alta. No **modo conversa**, ele volta a ouvir sozinho quando termina de falar.

- Kotlin + Jetpack Compose (Material 3), minSdk 26, target/compileSdk 35
- Voz: `SpeechRecognizer` e `TextToSpeech` nativos do Android
- API: Claude Messages API via OkHttp + `org.json`

## Estrutura

```
app/src/main/java/dev/breno/jarvis/
├── MainActivity.kt          # edge-to-edge, permissão do microfone, setContent
├── JarvisViewModel.kt       # estado (StateFlow), histórico, ciclo ouvir → pensar → falar
├── model/ChatMessage.kt     # Role, ChatMessage, JarvisStatus, JarvisUiState
├── api/ClaudeClient.kt      # chamada HTTP à Messages API (já aceita "tools" para a Fase 2)
├── voice/SpeechListener.kt  # SpeechRecognizer (main thread), erros em português
├── voice/VoiceSpeaker.kt    # TextToSpeech pt-BR + limpeza de markdown
└── ui/
    ├── JarvisScreen.kt      # título, chat em bolhas, status, orb, switch
    ├── Orb.kt               # orb com gradiente radial animado
    └── theme/Theme.kt       # cores do tema escuro
```

## 1. Configurar a API key

1. Crie uma chave em <https://console.anthropic.com/settings/keys>.
2. Na raiz do projeto, copie o modelo:
   ```bash
   cp local.properties.example local.properties
   ```
3. Edite o `local.properties`:
   ```properties
   CLAUDE_API_KEY=sk-ant-sua-chave-aqui
   CLAUDE_MODEL=claude-sonnet-5-5   # opcional
   ```
   O Android Studio também grava `sdk.dir=...` nesse arquivo. Pode deixar.

O `local.properties` está no `.gitignore` e **nunca** vai para o repositório. A chave entra no app pelo `BuildConfig` na hora de compilar, então **recompile** depois de trocar a chave. Se ela estiver vazia, o app avisa na tela.

> ⚠️ A chave fica embutida no APK. Isso serve para uso pessoal; não distribua esse APK para outras pessoas.

## 2. Abrir no Android Studio

1. Instale uma versão recente do Android Studio (com suporte ao AGP 9.5).
2. **File → Open** e escolha a pasta do projeto (a que tem o `settings.gradle.kts`).
3. Espere o Gradle sincronizar. Na primeira vez ele baixa o Gradle 9.9.2 e as dependências.
4. Para compilar pela linha de comando:
   ```bash
   ./gradlew assembleDebug
   ```
   O APK sai em `app/build/outputs/apk/debug/app-debug.apk`.

## 3. Rodar no celular

### Via cabo USB
1. No celular, ative as **Opções do desenvolvedor**: *Configurações → Sobre o telefone →* toque 7 vezes em **Número da versão**.
2. Em *Opções do desenvolvedor*, ative **Depuração USB**.
3. Conecte o cabo e aceite o pedido "Permitir depuração USB?" que aparece no celular.
4. No Android Studio, escolha o aparelho na barra de cima e clique em **Run ▶**.
   Pelo terminal, também funciona:
   ```bash
   ./gradlew installDebug
   ```

### Via Wi-Fi (depuração sem fio, Android 11+)
1. Celular e computador precisam estar **na mesma rede Wi-Fi**.
2. Em *Opções do desenvolvedor*, ative **Depuração por Wi-Fi**.
3. No Android Studio: no menu de dispositivos, **Pair Devices Using Wi-Fi**. Escaneie o QR code com o celular (*Depuração por Wi-Fi → Parear dispositivo com QR code*).
4. Ou pelo terminal: em *Depuração por Wi-Fi → Parear com código*, use o IP:porta e o código que aparecem:
   ```bash
   adb pair 192.168.0.10:37123
   ```
   Depois conecte no IP:porta mostrado na tela principal de *Depuração por Wi-Fi* (essa porta é diferente da de pareamento):
   ```bash
   adb connect 192.168.0.10:41234
   ```
5. Clique em **Run ▶** normalmente.

## Como usar

| Estado | Orb | O que o toque faz |
|---|---|---|
| Ocioso, "Toque para falar" | ciano | começa a ouvir (na 1ª vez pede a permissão do microfone) |
| Ouvindo | ciano forte, pulsa com a sua voz | para de ouvir |
| Pensando | âmbar | nada (aguardando a resposta) |
| Falando | roxo | interrompe a fala e já começa a ouvir |

Com o **Modo conversa** ligado (padrão), o Jarvis volta a ouvir quando termina de falar. Se você não disser nada, ele volta para o ocioso sem mostrar erro.

## Detalhes da chamada à API

- `POST https://api.anthropic.com/v1/messages` com `x-api-key` e `anthropic-version: 2023-06-01`.
- Vão as últimas 20 mensagens do histórico, sempre começando por uma do usuário. Se a chamada falhar, a pergunta é removida do histórico para manter a alternância user/assistant.
- `output_config.effort = "low"`: as respostas são curtas e faladas, então pouco raciocínio deixa tudo mais rápido.
- `fallbacks: "default"` (beta `server-side-fallback-2026-07-01`): se o filtro de segurança recusar uma pergunta, o próprio servidor tenta outro modelo. Se a recusa continuar, o Jarvis responde com uma frase curta.
- Timeouts: conexão 15 s, leitura 60 s. Erros da API mostram o `error.message` na tela.

Se você trocar o `CLAUDE_MODEL` por um modelo antigo que não aceite `effort` ou `fallbacks`, a API devolve erro 400. Nesse caso, remova esses campos em `ClaudeClient.createMessage`.

## Problemas comuns

- **"Este aparelho não tem reconhecimento de voz disponível"**: o `SpeechRecognizer` depende de um serviço de reconhecimento instalado, normalmente o **app do Google** (ou *Speech Services by Google*). Celulares sem serviços do Google (Huawei recentes, ROMs "degoogled", alguns emuladores) não têm esse serviço. Instale ou ative o app do Google e confira em *Configurações → Sistema → Idioma → Reconhecimento de voz*.
- **Reconhece em inglês ou não entende português**: no app do Google, adicione *Português (Brasil)* em *Voz → Idiomas* e baixe o pacote offline, se quiser.
- **Não fala nada ou fala com sotaque estranho**: instale a voz pt-BR no motor de TTS (*Configurações → Acessibilidade → Saída de texto para fala* → Motor do Google → instalar dados de voz → Português (Brasil)).
- **"Sem conexão para o reconhecimento de voz"**: o reconhecimento do Google costuma precisar de internet, a menos que o pacote offline pt-BR esteja baixado.
- **"O reconhecimento de voz está ocupado"**: outro app (assistente, gravador) está usando o microfone. Feche-o e tente de novo.
- **"Erro da API (401)…"**: chave inválida. Confira o `local.properties` e recompile.
- **"Erro da API (404)…" / modelo não encontrado**: confira o `CLAUDE_MODEL`.
- **Negou a permissão do microfone sem querer**: *Configurações → Apps → Jarvis → Permissões → Microfone → Permitir*.
- **Emulador**: o microfone do emulador precisa estar ligado (*Extended controls → Microphone → "Virtual microphone uses host audio input"*), e a imagem precisa ter Google Play.

## Próximo passo: Fase 2

- **Tool calling**: `ClaudeClient.createMessage(...)` já recebe `tools` e devolve `ClaudeResponse.content` (os blocos crus) e `stopReason`. O loop `tool_use` → `tool_result` vai numa classe de agente acima do cliente, e o histórico passa a guardar blocos em vez de só texto.
- **Agente no PC**: as ferramentas que rodam no computador ficam atrás de uma interface própria, e o ViewModel continua só orquestrando voz e estado.
