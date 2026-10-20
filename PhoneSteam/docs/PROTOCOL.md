# Protocolo PhoneSteam (v1)

Todos os valores são **little-endian**. Floats são IEEE-754 32 bits.
Sistema de coordenadas = OpenVR: destro, +Y para cima, -Z para frente, metros.
Quaternions na ordem `x, y, z, w`.

## Portas

| Porta | Transporte | Direção        | Uso                                   |
|-------|------------|----------------|---------------------------------------|
| 9944  | UDP        | celular → host | Pose da cabeça + controles (~90 Hz)   |
| 9945  | TCP        | bidirecional   | Handshake, vídeo, apps, háptico       |

No modo nuvem (GameHub) a pose também vai pelo TCP (`MSG_POSE`), porque
muitos PCs na nuvem/NATs bloqueiam UDP de entrada.

## Cabeçalho TCP (12 bytes)

```
u32 magic   = 0x4D545350  ("PSTM")
u8  type
u8  flags
u16 reserved
u32 length  (bytes do payload)
```

## Mensagens

| type | nome            | direção | payload |
|------|-----------------|---------|---------|
| 1    | POSE            | C→H     | `PosePacket` (igual ao UDP, sem o cabeçalho UDP) |
| 2    | HELLO           | C→H     | `u16 eyeW, u16 eyeH, u8 fps, u8 mode, u16 bitrateMbps, f32 ipd, f32 tanL, tanR, tanU, tanD` — `mode`: 0 = só controle, 1 = streaming |
| 3    | CONFIG          | H→C     | `u16 eyeW, u16 eyeH, u8 fps, u8 codec(0=H.264), u16 bitrateMbps` |
| 4    | VIDEO           | H→C     | `u32 frameIndex, f32 qx,qy,qz,qw (pose usada no render), u8 keyframe, u8[3] pad, H.264 Annex-B` (lado a lado: olho esquerdo à esquerda) |
| 5    | APP_LIST_REQ    | C→H     | vazio |
| 6    | APP_LIST        | H→C     | JSON UTF-8 `[{"key":"steam.app.620980","name":"Beat Saber"}]` |
| 7    | LAUNCH_APP      | C→H     | app key UTF-8 |
| 8    | HAPTIC          | H→C     | `u8 controller(0=esq,1=dir), u8[3] pad, f32 duration, f32 frequency, f32 amplitude` |
| 9    | REQUEST_IDR     | C→H     | vazio — pede um keyframe (ex.: após o decoder reiniciar) |

## PosePacket UDP (152 bytes)

```
u32 magic, u8 version(=1), u8 type(=1), u16 reserved
u32 sequence
u64 timestampUs
f32 head qx,qy,qz,qw
f32 head px,py,pz
2x Controller (esquerdo, direito):
   u8  flags   (1=conectado, 2=pose válida, 4=mão rastreada)
   u8  source  (0=nenhum, 1=Joy-Con, 2=mão, 3=híbrido)
   u16 reserved
   f32 px,py,pz
   f32 qx,qy,qz,qw
   u32 buttons (bit0 system, bit1 menu, bit2 A, bit3 B, bit4 trigger click, bit5 grip click, bit6 stick click)
   f32 trigger, grip, stickX, stickY
```
