#include "settings.h"

#include <cmath>
#include <cstdlib>

#include <openvr_driver.h>

namespace phonesteam {

HmdConfig HmdConfig::Load() {
    HmdConfig c;
    auto* s = vr::VRSettings();
    if (!s) return c;
    vr::EVRSettingsError err;
    auto i = [&](const char* k, int def) {
        int v = s->GetInt32(kSettingsSection, k, &err);
        return err == vr::VRSettingsError_None && v > 0 ? v : def;
    };
    auto f = [&](const char* k, float def) {
        float v = s->GetFloat(kSettingsSection, k, &err);
        return err == vr::VRSettingsError_None && v > 0 ? v : def;
    };
    c.eyeWidth = i("eyeWidth", c.eyeWidth);
    c.eyeHeight = i("eyeHeight", c.eyeHeight);
    c.fps = i("fps", c.fps);
    c.bitrateMbps = i("bitrateMbps", c.bitrateMbps);
    c.ipd = f("ipd", c.ipd);
    c.tanLeft = f("tanLeft", c.tanLeft);
    c.tanRight = f("tanRight", c.tanRight);
    c.tanUp = f("tanUp", c.tanUp);
    c.tanDown = f("tanDown", c.tanDown);
    c.eyeHeightMeters = f("eyeHeightMeters", c.eyeHeightMeters);
    bool b = s->GetBool(kSettingsSection, "emulateTouch", &err);
    if (err == vr::VRSettingsError_None) c.emulateTouch = b;
    char enc[64] = {};
    s->GetString(kSettingsSection, "encoder", enc, sizeof(enc), &err);
    if (err == vr::VRSettingsError_None && enc[0]) c.encoder = enc;
    return c;
}

void HmdConfig::Save() const {
    auto* s = vr::VRSettings();
    if (!s) return;
    s->SetInt32(kSettingsSection, "eyeWidth", eyeWidth);
    s->SetInt32(kSettingsSection, "eyeHeight", eyeHeight);
    s->SetInt32(kSettingsSection, "fps", fps);
    s->SetInt32(kSettingsSection, "bitrateMbps", bitrateMbps);
    s->SetFloat(kSettingsSection, "ipd", ipd);
    s->SetFloat(kSettingsSection, "tanLeft", tanLeft);
    s->SetFloat(kSettingsSection, "tanRight", tanRight);
    s->SetFloat(kSettingsSection, "tanUp", tanUp);
    s->SetFloat(kSettingsSection, "tanDown", tanDown);
}

bool HmdConfig::NeedsRestartFor(const HmdConfig& o) const {
    auto diff = [](float a, float b) { return std::fabs(a - b) > 0.02f; };
    return std::abs(eyeWidth - o.eyeWidth) > 16 || std::abs(eyeHeight - o.eyeHeight) > 16 || fps != o.fps ||
           diff(tanLeft, o.tanLeft) || diff(tanRight, o.tanRight) || diff(tanUp, o.tanUp) || diff(tanDown, o.tanDown);
}

}  // namespace phonesteam
