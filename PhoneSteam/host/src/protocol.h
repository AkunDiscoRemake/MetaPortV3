// Protocolo PhoneSteam v1 — ver docs/PROTOCOL.md. Todos os campos são little-endian.
#pragma once
#include <cstdint>
#include <cstring>

namespace phonesteam {

constexpr uint32_t kMagic = 0x4D545350;  // "PSTM"
constexpr uint8_t kVersion = 1;
constexpr uint16_t kUdpPosePort = 9944;
constexpr uint16_t kTcpPort = 9945;

enum MsgType : uint8_t {
    MSG_POSE = 1,
    MSG_HELLO = 2,
    MSG_CONFIG = 3,
    MSG_VIDEO = 4,
    MSG_APP_LIST_REQ = 5,
    MSG_APP_LIST = 6,
    MSG_LAUNCH_APP = 7,
    MSG_HAPTIC = 8,
    MSG_REQUEST_IDR = 9,
};

enum HelloMode : uint8_t { MODE_CONTROL = 0, MODE_STREAM = 1 };

enum ButtonBits : uint32_t {
    BTN_SYSTEM = 1u << 0,
    BTN_MENU = 1u << 1,
    BTN_A = 1u << 2,
    BTN_B = 1u << 3,
    BTN_TRIGGER_CLICK = 1u << 4,
    BTN_GRIP_CLICK = 1u << 5,
    BTN_STICK_CLICK = 1u << 6,
};

enum ControllerFlagBits : uint8_t {
    CTRL_CONNECTED = 1,
    CTRL_POSE_VALID = 2,
    CTRL_HAND_TRACKED = 4,
};

#pragma pack(push, 1)
struct MsgHeader {
    uint32_t magic;
    uint8_t type;
    uint8_t flags;
    uint16_t reserved;
    uint32_t length;
};
static_assert(sizeof(MsgHeader) == 12, "MsgHeader");

struct Hello {
    uint16_t eyeWidth, eyeHeight;
    uint8_t fps, mode;
    uint16_t bitrateMbps;
    float ipd;
    float tanLeft, tanRight, tanUp, tanDown;  // olho esquerdo; o direito é espelhado
};
static_assert(sizeof(Hello) == 28, "Hello");

struct Config {
    uint16_t eyeWidth, eyeHeight;
    uint8_t fps, codec;
    uint16_t bitrateMbps;
};
static_assert(sizeof(Config) == 8, "Config");

struct VideoHeader {
    uint32_t frameIndex;
    float qx, qy, qz, qw;
    uint8_t keyframe;
    uint8_t pad[3];
};
static_assert(sizeof(VideoHeader) == 24, "VideoHeader");

struct ControllerData {
    uint8_t flags;
    uint8_t source;
    uint16_t reserved;
    float px, py, pz;
    float qx, qy, qz, qw;
    uint32_t buttons;
    float trigger, grip, stickX, stickY;
};
static_assert(sizeof(ControllerData) == 52, "ControllerData");

struct PoseBody {
    uint32_t sequence;
    uint64_t timestampUs;
    float hqx, hqy, hqz, hqw;
    float hpx, hpy, hpz;
    ControllerData controllers[2];  // 0 = esquerdo, 1 = direito
};
static_assert(sizeof(PoseBody) == 144, "PoseBody");

struct UdpPosePacket {
    uint32_t magic;
    uint8_t version;
    uint8_t type;
    uint16_t reserved;
    PoseBody body;
};
static_assert(sizeof(UdpPosePacket) == 152, "UdpPosePacket");

struct Haptic {
    uint8_t controller;
    uint8_t pad[3];
    float duration, frequency, amplitude;
};
static_assert(sizeof(Haptic) == 16, "Haptic");
#pragma pack(pop)

}  // namespace phonesteam
