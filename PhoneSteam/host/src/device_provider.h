#pragma once
#include <memory>

#include <openvr_driver.h>

#include "controller_device.h"
#include "hmd_device.h"
#include "phone_server.h"
#include "settings.h"

namespace phonesteam {

class DeviceProvider : public vr::IServerTrackedDeviceProvider {
public:
    vr::EVRInitError Init(vr::IVRDriverContext* ctx) override;
    void Cleanup() override;
    const char* const* GetInterfaceVersions() override { return vr::k_InterfaceVersions; }
    void RunFrame() override;
    bool ShouldBlockStandbyMode() override { return false; }
    void EnterStandby() override {}
    void LeaveStandby() override {}

private:
    void OnStreamClient(const Hello& hello);

    HmdConfig cfg_;
    PhoneServer server_;
    std::unique_ptr<HmdDevice> hmd_;
    std::unique_ptr<ControllerDevice> controllers_[2];
    uint32_t lastSeq_ = UINT32_MAX;
};

}  // namespace phonesteam
