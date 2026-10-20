// Controles virtuais: Joy-Cons e/ou mãos rastreadas pelo celular.
#pragma once
#include <openvr_driver.h>

#include "protocol.h"

namespace phonesteam {

class ControllerDevice : public vr::ITrackedDeviceServerDriver {
public:
    ControllerDevice(bool left, bool emulateTouch) : left_(left), touch_(emulateTouch) {}

    vr::EVRInitError Activate(uint32_t objectId) override;
    void Deactivate() override { id_ = vr::k_unTrackedDeviceIndexInvalid; }
    void EnterStandby() override {}
    void* GetComponent(const char*) override { return nullptr; }
    void DebugRequest(const char*, char* response, uint32_t size) override {
        if (size) response[0] = 0;
    }
    vr::DriverPose_t GetPose() override { return pose_; }

    void Update(const ControllerData* c);
    vr::VRInputComponentHandle_t HapticHandle() const { return haptic_; }
    const char* Serial() const { return left_ ? "PhoneSteam-Left" : "PhoneSteam-Right"; }
    bool IsLeft() const { return left_; }

private:
    enum Comp {
        kSystem, kMenu, kA, kB, kATouch, kBTouch,
        kTriggerClick, kTriggerTouch, kGripClick, kGripTouch, kStickClick, kStickTouch,
        kBoolCount
    };
    enum Scalar { kTrigger, kGrip, kStickX, kStickY, kScalarCount };

    bool left_;
    bool touch_;
    uint32_t id_ = vr::k_unTrackedDeviceIndexInvalid;
    vr::PropertyContainerHandle_t props_ = vr::k_ulInvalidPropertyContainer;
    vr::VRInputComponentHandle_t bools_[kBoolCount] = {};
    vr::VRInputComponentHandle_t scalars_[kScalarCount] = {};
    vr::VRInputComponentHandle_t haptic_ = vr::k_ulInvalidInputComponentHandle;
    vr::DriverPose_t pose_{};
};

}  // namespace phonesteam
