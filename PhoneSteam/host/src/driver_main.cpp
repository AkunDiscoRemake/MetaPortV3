#include <cstring>

#include <openvr_driver.h>

#include "device_provider.h"

#if defined(_WIN32)
#define PHONESTEAM_EXPORT extern "C" __declspec(dllexport)
#else
#define PHONESTEAM_EXPORT extern "C" __attribute__((visibility("default")))
#endif

static phonesteam::DeviceProvider g_provider;

PHONESTEAM_EXPORT void* HmdDriverFactory(const char* interfaceName, int* returnCode) {
    if (std::strcmp(interfaceName, vr::IServerTrackedDeviceProvider_Version) == 0) return &g_provider;
    if (returnCode) *returnCode = vr::VRInitError_Init_InterfaceNotFound;
    return nullptr;
}
