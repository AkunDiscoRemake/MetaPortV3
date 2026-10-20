#include "controller_device.h"

#include <cmath>

#include "log.h"

namespace phonesteam {

vr::EVRInitError ControllerDevice::Activate(uint32_t objectId) {
    id_ = objectId;
    auto* p = vr::VRProperties();
    auto* in = vr::VRDriverInput();
    props_ = p->TrackedDeviceToPropertyContainer(objectId);

    p->SetInt32Property(props_, vr::Prop_ControllerRoleHint_Int32,
                        left_ ? vr::TrackedControllerRole_LeftHand : vr::TrackedControllerRole_RightHand);
    p->SetInt32Property(props_, vr::Prop_DeviceClass_Int32, vr::TrackedDeviceClass_Controller);
    p->SetStringProperty(props_, vr::Prop_SerialNumber_String, Serial());

    if (touch_) {
        // Emula Oculus Touch: o SteamVR já vem com o perfil e quase todo jogo tem bindings para ele.
        p->SetStringProperty(props_, vr::Prop_TrackingSystemName_String, "oculus");
        p->SetStringProperty(props_, vr::Prop_ManufacturerName_String, "Oculus");
        p->SetStringProperty(props_, vr::Prop_ModelNumber_String,
                             left_ ? "Oculus Rift CV1 (Left Controller)" : "Oculus Rift CV1 (Right Controller)");
        p->SetStringProperty(props_, vr::Prop_RenderModelName_String,
                             left_ ? "oculus_cv1_controller_left" : "oculus_cv1_controller_right");
        p->SetStringProperty(props_, vr::Prop_ControllerType_String, "oculus_touch");
        p->SetStringProperty(props_, vr::Prop_InputProfilePath_String, "{oculus}/input/touch_profile.json");
    } else {
        p->SetStringProperty(props_, vr::Prop_TrackingSystemName_String, "phonesteam");
        p->SetStringProperty(props_, vr::Prop_ManufacturerName_String, "PhoneSteam");
        p->SetStringProperty(props_, vr::Prop_ModelNumber_String, left_ ? "PhoneSteam Joy-Con (L)" : "PhoneSteam Joy-Con (R)");
        p->SetStringProperty(props_, vr::Prop_RenderModelName_String, "vr_controller_vive_1_5");
        p->SetStringProperty(props_, vr::Prop_ControllerType_String, "phonesteam_controller");
        p->SetStringProperty(props_, vr::Prop_InputProfilePath_String, "{phonesteam}/input/phonesteam_controller_profile.json");
    }

    // No Touch o botão A/B do esquerdo se chama X/Y.
    const bool xy = touch_ && left_;
    in->CreateBooleanComponent(props_, "/input/system/click", &bools_[kSystem]);
    in->CreateBooleanComponent(props_, "/input/application_menu/click", &bools_[kMenu]);
    in->CreateBooleanComponent(props_, xy ? "/input/x/click" : "/input/a/click", &bools_[kA]);
    in->CreateBooleanComponent(props_, xy ? "/input/y/click" : "/input/b/click", &bools_[kB]);
    in->CreateBooleanComponent(props_, xy ? "/input/x/touch" : "/input/a/touch", &bools_[kATouch]);
    in->CreateBooleanComponent(props_, xy ? "/input/y/touch" : "/input/b/touch", &bools_[kBTouch]);
    in->CreateBooleanComponent(props_, "/input/trigger/click", &bools_[kTriggerClick]);
    in->CreateBooleanComponent(props_, "/input/trigger/touch", &bools_[kTriggerTouch]);
    in->CreateBooleanComponent(props_, "/input/grip/click", &bools_[kGripClick]);
    in->CreateBooleanComponent(props_, "/input/grip/touch", &bools_[kGripTouch]);
    in->CreateBooleanComponent(props_, "/input/joystick/click", &bools_[kStickClick]);
    in->CreateBooleanComponent(props_, "/input/joystick/touch", &bools_[kStickTouch]);
    in->CreateScalarComponent(props_, "/input/trigger/value", &scalars_[kTrigger], vr::VRScalarType_Absolute, vr::VRScalarUnits_NormalizedOneSided);
    in->CreateScalarComponent(props_, "/input/grip/value", &scalars_[kGrip], vr::VRScalarType_Absolute, vr::VRScalarUnits_NormalizedOneSided);
    in->CreateScalarComponent(props_, "/input/joystick/x", &scalars_[kStickX], vr::VRScalarType_Absolute, vr::VRScalarUnits_NormalizedTwoSided);
    in->CreateScalarComponent(props_, "/input/joystick/y", &scalars_[kStickY], vr::VRScalarType_Absolute, vr::VRScalarUnits_NormalizedTwoSided);
    in->CreateHapticComponent(props_, "/output/haptic", &haptic_);

    pose_.qWorldFromDriverRotation.w = 1;
    pose_.qDriverFromHeadRotation.w = 1;
    pose_.qRotation.w = 1;
    pose_.deviceIsConnected = false;
    Log("Controle %s ativado (%s)", left_ ? "esquerdo" : "direito", touch_ ? "emulando Oculus Touch" : "perfil PhoneSteam");
    return vr::VRInitError_None;
}

void ControllerDevice::Update(const ControllerData* c) {
    if (id_ == vr::k_unTrackedDeviceIndexInvalid) return;
    const bool connected = c && (c->flags & CTRL_CONNECTED);
    pose_.deviceIsConnected = connected;
    pose_.poseIsValid = connected && (c->flags & CTRL_POSE_VALID);
    pose_.result = pose_.poseIsValid ? vr::TrackingResult_Running_OK : vr::TrackingResult_Running_OutOfRange;
    if (connected) {
        pose_.vecPosition[0] = c->px;
        pose_.vecPosition[1] = c->py;
        pose_.vecPosition[2] = c->pz;
        pose_.qRotation = {c->qw, c->qx, c->qy, c->qz};
    }
    vr::VRServerDriverHost()->TrackedDevicePoseUpdated(id_, pose_, sizeof(pose_));
    if (!connected) return;

    auto* in = vr::VRDriverInput();
    const uint32_t b = c->buttons;
    in->UpdateBooleanComponent(bools_[kSystem], (b & BTN_SYSTEM) != 0, 0);
    in->UpdateBooleanComponent(bools_[kMenu], (b & BTN_MENU) != 0, 0);
    in->UpdateBooleanComponent(bools_[kA], (b & BTN_A) != 0, 0);
    in->UpdateBooleanComponent(bools_[kB], (b & BTN_B) != 0, 0);
    in->UpdateBooleanComponent(bools_[kATouch], (b & BTN_A) != 0, 0);
    in->UpdateBooleanComponent(bools_[kBTouch], (b & BTN_B) != 0, 0);
    in->UpdateBooleanComponent(bools_[kTriggerClick], (b & BTN_TRIGGER_CLICK) != 0 || c->trigger > 0.9f, 0);
    in->UpdateBooleanComponent(bools_[kTriggerTouch], c->trigger > 0.05f, 0);
    in->UpdateBooleanComponent(bools_[kGripClick], (b & BTN_GRIP_CLICK) != 0 || c->grip > 0.9f, 0);
    in->UpdateBooleanComponent(bools_[kGripTouch], c->grip > 0.05f, 0);
    in->UpdateBooleanComponent(bools_[kStickClick], (b & BTN_STICK_CLICK) != 0, 0);
    in->UpdateBooleanComponent(bools_[kStickTouch], std::fabs(c->stickX) + std::fabs(c->stickY) > 0.05f, 0);
    in->UpdateScalarComponent(scalars_[kTrigger], c->trigger, 0);
    in->UpdateScalarComponent(scalars_[kGrip], c->grip, 0);
    in->UpdateScalarComponent(scalars_[kStickX], c->stickX, 0);
    in->UpdateScalarComponent(scalars_[kStickY], c->stickY, 0);
}

}  // namespace phonesteam
