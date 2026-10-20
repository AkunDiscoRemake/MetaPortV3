// Biblioteca VR do Steam do PC: lista (steamapps.vrmanifest) e abre apps a pedido do celular.
#pragma once
#include <string>

namespace phonesteam {

/** JSON `[{"key":"steam.app.620980","name":"Beat Saber"}, ...]`. */
std::string ListSteamVrAppsJson();
/** Abre um app pelo app key do SteamVR (ex.: "steam.app.620980"). */
bool LaunchSteamVrApp(const std::string& appKey);

}  // namespace phonesteam
