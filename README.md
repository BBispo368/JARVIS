# J.A.R.V.I.S. — Fases 1 e 2

Assistente de voz pessoal para Android. Você toca no orb e fala. O app transcreve em pt-BR, manda o texto para o Claude e lê a resposta em voz alta. No **modo conversa**, ele volta a ouvir sozinho quando termina de falar.

Na **Fase 2**, o Jarvis também **age**: usa ferramentas no celular (timer, alarme, lanterna) e no seu computador, por meio de um agente pequeno que roda no PC (abrir sites e apps, controlar mídia e volume, bloquear a tela, copiar texto, rodar atalhos que você define).

- Kotlin + Jetpack Compose (Material 3), minSdk 26, target/compileSdk 35
- Voz: `SpeechRecognizer` e `TextToSpeech` nativos do Android
- API: Claude Messages API via OkHttp + `org.json`

## Estrutura

```
app/src/main/java/dev/breno/jarvis/
├── MainActivity.kt          # edge-to-edge, permissão do microfone, setContent
├── JarvisViewModel.kt       # estado (StateFlow), histórico, ciclo ouvir → pensar → falar
├── model/ChatMessage.kt     # Role, ChatMessage, JarvisStatus, JarvisUiState
├── api/ClaudeClient.kt      # chamada HTTP à Messages API
├── agent/
│   ├── JarvisAgent.kt       # loop de tool calling (tool_use → tool_result → resposta)
│   ├── JarvisTool.kt        # interface das ferramentas + helpers de schema
│   └── ConversationHistory.kt # histórico por turnos, janela de ~20 mensagens
├── tools/PhoneTools.kt      # ferramentas do celular: timer, alarme, lanterna
├── pc/PcAgentClient.kt      # descobre e chama as ferramentas do agente do PC
├── voice/SpeechListener.kt  # SpeechRecognizer (main thread), erros em português
├── voice/VoiceSpeaker.kt    # TextToSpeech pt-BR + limpeza de markdown
└── ui/
    ├── JarvisScreen.kt      # título, chat em bolhas, status, orb, switch
    ├── Orb.kt               # orb com gradiente radial animado
    └── theme/Theme.kt       # cores do tema escuro

pc-agent/
├── jarvis_agent.py          # agente do PC (Python 3.9+, só biblioteca padrão)
├── config.example.json      # exemplo de apps e atalhos
└── test_jarvis_agent.py     # testes do agente
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

## Fase 2: ferramentas

### O que o Jarvis consegue fazer

| Onde | Ferramenta | Exemplo de pedido |
|---|---|---|
| Celular | `phone_set_timer` | "me avisa em dez minutos" |
| Celular | `phone_set_alarm` | "me acorda às seis e meia" |
| Celular | `phone_flashlight` | "liga a lanterna" |
| PC | `pc_open_url` | "abre o YouTube no PC", "pesquisa receita de lasanha" |
| PC | `pc_open_app` | "abre o Spotify" (só apps do `config.json`) |
| PC | `pc_run_shortcut` | "qual meu IP público?" (só atalhos do `config.json`) |
| PC | `pc_media` | "pausa a música", "aumenta o volume" |
| PC | `pc_lock_screen` | "bloqueia o computador" |
| PC | `pc_clipboard` | "copia esse texto pro PC: ..." |
| PC | `pc_system_status` | "quanto de disco sobrou no PC?" |

Enquanto usa uma ferramenta, a tela mostra o que ele está fazendo ("Criando timer…"). Embaixo do título, um indicador mostra se o PC está conectado. Toque nele para procurar o PC de novo.

### Subir o agente no PC

1. Instale o Python 3.9 ou mais novo (<https://www.python.org/downloads/>; no Windows, marque *Add Python to PATH*).
2. Rode o agente:
   ```bash
   cd pc-agent
   ```
   ```bash
   python jarvis_agent.py
   ```
   Na primeira vez ele cria o `pc-agent/config.json` com um **token aleatório**. Esse arquivo está no `.gitignore`. Depois ele mostra o endereço, algo como `http://192.168.0.10:8765`.
3. No `local.properties` do app, coloque o endereço e o token (o `token` do `config.json`):
   ```properties
   PC_AGENT_URL=http://192.168.0.10:8765
   PC_AGENT_TOKEN=o-token-do-config-json
   ```
   Recompile e instale o app. O indicador deve mostrar "PC conectado".
4. **Firewall**: no Windows, quando aparecer o aviso do Firewall, permita o Python em **redes privadas**. A rede Wi-Fi de casa precisa estar marcada como *Privada*. No macOS, clique em *Permitir* no aviso de conexões de entrada.

O celular e o PC precisam estar na **mesma rede Wi-Fi**. Para o IP do PC não mudar, fixe um IP para ele no roteador (reserva DHCP). Senão, você vai precisar atualizar o `PC_AGENT_URL` e recompilar.

### Configurar apps e atalhos

Edite o `pc-agent/config.json`, usando o `config.example.json` como base, e reinicie o agente. O app pega as mudanças sozinho, **sem recompilar**, porque o PC anuncia as próprias ferramentas.

```json
{
  "apps": {
    "spotify": "start spotify:",
    "vscode": "code"
  },
  "shortcuts": {
    "ip_publico": { "description": "mostra o IP público", "command": "curl -s https://api.ipify.org" },
    "desligar": { "description": "desliga o PC em 1 minuto (irreversível)", "command": "shutdown /s /t 60" }
  }
}
```

- **`apps`**: nome falado → comando que abre o app no **seu** sistema. No Windows: `start chrome`, `notepad`; no macOS: `open -a Spotify`; no Linux: `firefox`.
- **`shortcuts`**: qualquer comando seu. A `description` é o que o Claude lê para decidir quando usar. A saída do comando volta para ele (útil para perguntas).
- O exemplo de `config.example.json` usa comandos do Windows. Troque pelos do seu sistema.

**Segurança**: o modelo nunca escreve comandos. Ele só escolhe, pelo nome, um app ou atalho que você cadastrou. Toda requisição exige o token, o agente só existe enquanto o script está rodando e ele responde só na rede local. O tráfego entre celular e PC é HTTP sem criptografia, então use apenas numa rede de confiança (a de casa). Nunca exponha a porta 8765 na internet.

Dependências opcionais por sistema:
- **Linux**: `pactl` (volume), `playerctl` (mídia), `wl-copy`/`xclip`/`xsel` (área de transferência), `loginctl` (bloquear tela).
- **macOS**: o controle de mídia usa o Spotify (se aberto) ou o app Música; bloquear a tela usa `pmset displaysleepnow`, então ative *Exigir senha imediatamente* nos Ajustes.

### Testes

```bash
./gradlew testDebugUnitTest
```
```bash
cd pc-agent && python -m unittest -v test_jarvis_agent.py
```

### Como o loop funciona (para quem for mexer no código)

1. O `JarvisViewModel` monta a lista de ferramentas do turno (celular + PC) e chama `JarvisAgent.runTurn`.
2. O agente chama a API. Se vier `stop_reason: "tool_use"`, ele roda as ferramentas pedidas em paralelo e devolve todos os `tool_result` numa única mensagem. Repete até vir a resposta em texto, com no máximo 8 etapas.
3. Dentro de um turno, o `system`, as `tools` e as mensagens anteriores ficam **iguais**: só se acrescenta coisa no final. Os blocos de `thinking` voltam intactos entre as chamadas, como o Sonnet 5.5 exige.
4. Quando o turno termina, o `ConversationHistory` guarda o turno inteiro **sem** os blocos de thinking. A janela corta por turnos inteiros, sempre começando com uma mensagem do usuário e nunca separando um `tool_use` do seu `tool_result`. Assim, cortar o começo da conversa ou mudar a data no system prompt não invalida nada.
5. Para criar uma ferramenta nova no celular, implemente `JarvisTool` e adicione em `PhoneTools.all`. No PC, adicione uma `Tool` em `build_tools` no `jarvis_agent.py`.

### Problemas comuns da Fase 2

- **"PC offline"**: o agente está rodando? Celular e PC estão no mesmo Wi-Fi? O IP do PC mudou? Teste no navegador do celular: `http://IP-DO-PC:8765/health` deve responder `{"ok": false, "error": "não autorizado"}`. Se não abrir, é rede ou firewall.
- **"token recusado pelo PC"**: o `PC_AGENT_TOKEN` não bate com o `token` do `config.json`. Corrija e recompile.
- **Redes de visitante ou corporativas** costumam bloquear a comunicação entre aparelhos ("isolamento de cliente"). Use a rede principal de casa.
- **Timer ou alarme não aparece**: precisa de um app de relógio que aceite pedidos de outros apps (o Relógio do Google aceita).
