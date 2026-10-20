# PhoneSteam

**SteamVR no Google Cardboard**, rodando num PC na nuvem (app **GameHub**) ou num PC local, com
**hand tracking**, **Joy-Cons** e um **launcher OpenXR**.

```
 ┌──────────── Celular (app Android) ────────────┐          ┌──── PC na nuvem (GameHub) / PC local ────┐
 │ giroscópio ─► pose da cabeça ─┐               │  UDP/TCP │  driver_phonesteam (SteamVR)             │
 │ câmera ─► MediaPipe ─► mãos ──┼─► pose+botões ├─────────►│   ├─ HMD virtual "PhoneSteam Cardboard"  │
 │ Joy-Cons (Bluetooth) ─────────┘               │          │   ├─ 2 controles (emula Oculus Touch)    │
 │                                               │   TCP    │   └─ captura D3D11 ─► H.264 (NVENC/AMF…) │
 │ MediaCodec ◄─ vídeo H.264 lado a lado ◄───────┼──────────┤  SteamVR = runtime OpenXR do PC          │
 │ timewarp + distorção da lente ─► Cardboard    │          │  jogos SteamVR / OpenXR                  │
 └───────────────────────────────────────────────┘          └──────────────────────────────────────────┘
```

## O que tem

| Recurso | Onde |
|---|---|
| Estéreo para Cardboard com distorção de barril por perfil de lente (v1, v2, visor 100°) | `android/.../vr/CardboardRenderer.kt`, `CardboardOptics.kt` |
| Rastreamento de cabeça 3DoF + modelo de pescoço, recentralizar | `vr/HeadTracker.kt` |
| Streaming do SteamVR: H.264 por hardware (MediaCodec), **timewarp rotacional** para esconder a latência da nuvem | `stream/StreamClient.kt`, `VideoDecoder.kt` |
| **Modo nuvem (GameHub)**: pose também via TCP (atravessa NAT/firewall), botão para abrir o app GameHub, link `phonesteam://host:porta` | `cloud/GameHubCloud.kt`, `MainActivity.kt` |
| **Hand tracking** pela câmera traseira (MediaPipe): pinça = gatilho, mão fechada = grip, polegar+médio = A, polegar+anelar = B, polegar+mindinho = menu | `hand/HandTracker.kt` |
| **Joy-Cons** (L/R avulsos, Pro Controller, gamepads): botões, analógicos, gatilhos, giroscópio (Android 12+) e vibração | `input/JoyConManager.kt` |
| Fusão: mão rastreada dá a posição, Joy-Con dá botões e orientação ("híbrido") | `vr/InputFusion.kt` |
| **Launcher OpenXR**: apps OpenXR/Cardboard instalados no celular (mostra o runtime OpenXR ativo) + biblioteca **SteamVR do PC** (abre o jogo no PC e já entra no stream) | `openxr/` |
| Driver SteamVR: HMD virtual + 2 controles + háptico + lista/abre apps VR do Steam | `host/src/` |

## 1. PC (local ou na nuvem do GameHub)

Requisitos: Windows 10/11, Steam + SteamVR, GPU com encoder de vídeo (NVIDIA NVENC, AMD AMF ou
Intel QSV; sem isso usa x264 na CPU).

### Compilar o driver

```bat
:: FFmpeg 6.1+ "shared" (ex.: https://github.com/BtbN/FFmpeg-Builds/releases ou gyan.dev)
cmake -S host -B host/build -G "Visual Studio 17 2022" -A x64 -DFFMPEG_DIR=C:/ffmpeg
cmake --build host/build --config Release
copy C:\ffmpeg\bin\*.dll host\driver\phonesteam\bin\win64\
```

O resultado fica pronto em `host/driver/phonesteam/` (manifest, recursos e `bin/win64/driver_phonesteam.dll`).

### Registrar no SteamVR

```bat
"C:\Program Files (x86)\Steam\steamapps\common\SteamVR\bin\win64\vrpathreg.exe" adddriver "%CD%\host\driver\phonesteam"
```

Depois abra o SteamVR. Ele vai mostrar o HMD "PhoneSteam Cardboard" aguardando o celular.

**Firewall / nuvem:** libere **TCP 9945** (obrigatório) e **UDP 9944** (opcional, menor latência
na rede local). No PC do GameHub, encaminhe a porta TCP 9945 no painel do serviço (ou use a VPN /
IP público que ele fornece) e use esse endereço no app. Recomendado: ≥ 25 Mbps de download e
ping < 40 ms até o datacenter.

Configurações (opcionais) em `steamvr.vrsettings`, seção `driver_phonesteam`: `encoder`
(`auto`, `h264_nvenc`, `h264_amf`, `h264_qsv`, `libx264`), `emulateTouch` (padrão `true`: os
controles aparecem como Oculus Touch, então quase todo jogo já tem os controles mapeados),
`eyeHeightMeters`. A resolução, a taxa de atualização e o FOV vêm do celular. Quando mudam, o
driver salva os novos valores e reinicia o SteamVR sozinho.

## 2. Celular (Android 8+, giroscópio obrigatório)

```bash
cd android
./gradlew assembleDebug        # APK em app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

1. **GameHub:** toque em *Abrir GameHub*, ligue o PC na nuvem e deixe o SteamVR aberto nele.
2. Volte ao PhoneSteam e digite o endereço do PC (`ip` ou `host:porta`). Deixe *Modo nuvem* ligado
   para PC na nuvem, ou desligado para PC na mesma rede Wi-Fi (5 GHz).
3. **Joy-Cons:** pareie nas configurações de Bluetooth do Android (segure o botão de sync). O
   Joy-Con (L) vira a mão esquerda e o (R) a direita.
4. Escolha o perfil do visor e toque em **Entrar no SteamVR**. Coloque o celular no Cardboard.

Dentro do VR: segurar na tela = gatilho (botão do Cardboard) · toque duplo ou Volume+ = recentralizar
a cabeça · segurar Home/Captura do Joy-Con por 1 s = recentralizar os controles. Sem conexão, você vê
uma sala de teste com os controles e as mãos, que serve para conferir o tracking.

**Hand tracking** usa a câmera traseira. O visor precisa deixar a câmera descoberta (a maioria
deixa). O modelo do MediaPipe (~8 MB) é baixado no primeiro uso.

### Launcher OpenXR

- **Celular:** lista os apps com as categorias `org.khronos.openxr.intent.category.IMMERSIVE_HMD`
  (OpenXR), Cardboard e mobile VR, e mostra qual runtime OpenXR está ativo (pelo *runtime broker* da
  Khronos). Para apps OpenXR **nativos** rodarem no Cardboard, é preciso ter um runtime OpenXR para
  celular instalado (ex.: Monado).
- **SteamVR (PC):** lista os jogos VR do `steamapps.vrmanifest` do PC e abre o escolhido
  (`steam://launch/<id>/VR`). Como o SteamVR é um runtime OpenXR, os jogos OpenXR do PC também rodam
  no Cardboard por esse caminho.

## Testes

```bash
cd android && ./gradlew testDebugUnitTest                 # layout do protocolo + matemática da cabeça
cd host && cmake -S . -B build -DPHONESTEAM_BUILD_SELFTEST=ON && cmake --build build
./build/phonesteam_selftest 8 & python3 tests/fake_phone.py   # host real + celular simulado
```

## Limitações conhecidas

- O rastreamento é **3DoF** na cabeça (giroscópio). A posição vem de um modelo de pescoço, sem andar
  pela sala. As mãos têm posição estimada pela câmera, com profundidade calculada pelo tamanho da mão.
- A captura de vídeo do driver é **só para Windows** (D3D11). No Linux, o driver compila e rastreia,
  mas não transmite vídeo.
- A conversão de cor é feita na CPU (`swscale`). Em resoluções altas, isso pode limitar o FPS.
- O "GameHub" é tratado como o serviço que fornece o PC na nuvem. O PhoneSteam só abre o app dele e
  conecta ao endereço do PC; não há integração com a API dele.
- O mapeamento dos eixos do giroscópio dos Joy-Cons depende do kernel/fabricante. Se a orientação vier
  invertida, recentralize ou ajuste `integrateGyro` em `JoyConManager.kt`.

Protocolo completo: [`docs/PROTOCOL.md`](docs/PROTOCOL.md).
