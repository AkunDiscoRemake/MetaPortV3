#pragma once
#include <string>

namespace phonesteam {

/** Configuração do HMD virtual, persistida em default.vrsettings / steamvr.vrsettings ("driver_phonesteam"). */
struct HmdConfig {
    int eyeWidth = 1024;
    int eyeHeight = 1152;
    int fps = 60;
    int bitrateMbps = 30;
    float ipd = 0.0639f;
    float tanLeft = 1.19f, tanRight = 1.0f, tanUp = 1.19f, tanDown = 1.19f;  // olho esquerdo
    float eyeHeightMeters = 1.65f;
    bool emulateTouch = true;
    std::string encoder = "auto";

    static HmdConfig Load();
    void Save() const;
    /** O SteamVR só lê resolução/FOV na inicialização: true se a diferença exige reiniciar. */
    bool NeedsRestartFor(const HmdConfig& other) const;
};

constexpr const char* kSettingsSection = "driver_phonesteam";

}  // namespace phonesteam
