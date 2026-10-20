#include "steam_apps.h"

#include <cstdlib>
#include <fstream>
#include <nlohmann/json.hpp>
#include <sstream>

#include "log.h"

#ifdef _WIN32
#include <windows.h>
#include <shellapi.h>
#endif

namespace phonesteam {

static std::string SteamPath() {
#ifdef _WIN32
    char buf[MAX_PATH] = {};
    DWORD size = sizeof(buf);
    if (RegGetValueA(HKEY_CURRENT_USER, "Software\\Valve\\Steam", "SteamPath", RRF_RT_REG_SZ, nullptr, buf, &size) == ERROR_SUCCESS)
        return buf;
    return "C:/Program Files (x86)/Steam";
#else
    const char* home = std::getenv("HOME");
    return std::string(home ? home : "") + "/.steam/steam";
#endif
}

static nlohmann::json LoadManifest() {
    std::ifstream f(SteamPath() + "/config/steamapps.vrmanifest");
    if (!f) return nlohmann::json::object();
    try {
        return nlohmann::json::parse(f);
    } catch (const std::exception& e) {
        Log("steamapps.vrmanifest inválido: %s", e.what());
        return nlohmann::json::object();
    }
}

static std::string AppName(const nlohmann::json& app) {
    const auto& strings = app.value("strings", nlohmann::json::object());
    for (const char* lang : {"pt_br", "en_us"}) {
        if (strings.contains(lang) && strings[lang].contains("name")) return strings[lang]["name"].get<std::string>();
    }
    if (!strings.empty() && strings.begin()->contains("name")) return (*strings.begin())["name"].get<std::string>();
    return app.value("app_key", "?");
}

std::string ListSteamVrAppsJson() {
    nlohmann::json out = nlohmann::json::array();
    const auto manifest = LoadManifest();
    for (const auto& app : manifest.value("applications", nlohmann::json::array())) {
        std::string key = app.value("app_key", "");
        if (key.empty() || app.value("is_dashboard_overlay", false)) continue;
        out.push_back({{"key", key}, {"name", AppName(app)}});
    }
    return out.dump();
}

bool LaunchSteamVrApp(const std::string& appKey) {
    std::string url;
    const std::string prefix = "steam.app.";
    if (appKey.rfind(prefix, 0) == 0) {
        const std::string id = appKey.substr(prefix.size());
        if (id.find_first_not_of("0123456789") != std::string::npos) return false;
        url = "steam://launch/" + id + "/VR";
    } else {
        // App não-Steam registrado no manifest: usa a URL declarada, se houver.
        for (const auto& app : LoadManifest().value("applications", nlohmann::json::array())) {
            if (app.value("app_key", "") == appKey && app.value("launch_type", "") == "url") url = app.value("url", "");
        }
        // Só abre URLs do Steam, e nada que possa escapar das aspas do shell.
        if (url.rfind("steam://", 0) != 0 || url.find_first_of("'\"`$;&|") != std::string::npos) return false;
    }
    Log("Abrindo %s", url.c_str());
#ifdef _WIN32
    return reinterpret_cast<intptr_t>(ShellExecuteA(nullptr, "open", url.c_str(), nullptr, nullptr, SW_SHOWNORMAL)) > 32;
#else
    return std::system(("xdg-open '" + url + "' &").c_str()) == 0;
#endif
}

}  // namespace phonesteam
