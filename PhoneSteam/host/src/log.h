#pragma once
#include <cstdarg>
#include <cstdio>
#include <string>

#include <openvr_driver.h>

namespace phonesteam {

/** true depois que o SteamVR inicializou o contexto do driver (VRDriverLog() só é válido a partir daí). */
inline bool& DriverContextReady() {
    static bool ready = false;
    return ready;
}

/** Log no vrserver.txt do SteamVR (ou stderr fora do SteamVR). */
inline void Log(const char* fmt, ...) {
    char buf[1024];
    va_list args;
    va_start(args, fmt);
    std::vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);
    if (DriverContextReady() && vr::VRDriverLog())
        vr::VRDriverLog()->Log((std::string("[PhoneSteam] ") + buf).c_str());
    else
        std::fprintf(stderr, "[PhoneSteam] %s\n", buf);
}

}  // namespace phonesteam
