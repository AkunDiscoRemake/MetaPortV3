#include "device_provider.h"

#include "log.h"
#include "steam_apps.h"

namespace phonesteam {

vr::EVRInitError DeviceProvider::Init(vr::IVRDriverContext* ctx) {
    VR_INIT_SERVER_DRIVER_CONTEXT(ctx);
    DriverContextReady() = true;
    cfg_ = HmdConfig::Load();

    hmd_ = std::make_unique<HmdDevice>(server_, cfg_);
    controllers_[0] = std::make_unique<ControllerDevice>(true, cfg_.emulateTouch);
    controllers_[1] = std::make_unique<ControllerDevice>(false, cfg_.emulateTouch);

    PhoneServer::Callbacks cb;
    cb.onStreamClient = [this](const Hello& h) { OnStreamClient(h); };
    cb.onRequestIdr = [this] { hmd_->RequestIdr(); };
    cb.appListJson = [] { return ListSteamVrAppsJson(); };
    cb.onLaunchApp = [](const std::string& key) { LaunchSteamVrApp(key); };
    if (!server_.Start(cb)) return vr::VRInitError_Driver_Failed;

    vr::VRServerDriverHost()->TrackedDeviceAdded("PhoneSteam-HMD", vr::TrackedDeviceClass_HMD, hmd_.get());
    for (auto& c : controllers_)
        vr::VRServerDriverHost()->TrackedDeviceAdded(c->Serial(), vr::TrackedDeviceClass_Controller, c.get());
    return vr::VRInitError_None;
}

void DeviceProvider::OnStreamClient(const Hello& hello) {
    HmdConfig wanted = cfg_;
    wanted.eyeWidth = hello.eyeWidth;
    wanted.eyeHeight = hello.eyeHeight;
    wanted.fps = hello.fps;
    wanted.bitrateMbps = hello.bitrateMbps;
    wanted.ipd = hello.ipd;
    wanted.tanLeft = hello.tanLeft;
    wanted.tanRight = hello.tanRight;
    wanted.tanUp = hello.tanUp;
    wanted.tanDown = hello.tanDown;
    if (cfg_.NeedsRestartFor(wanted)) {
        // Resolução/FOV só são lidos na inicialização do SteamVR: salva e reinicia.
        Log("Novo visor/celular: %dx%d @ %d Hz — reiniciando o SteamVR", wanted.eyeWidth, wanted.eyeHeight, wanted.fps);
        wanted.Save();
        vr::VRServerDriverHost()->RequestRestart("PhoneSteam: aplicando configuração do celular", nullptr, nullptr, nullptr);
        return;
    }
    hmd_->OnClientConnected();
}

void DeviceProvider::RunFrame() {
    PoseBody pose;
    double age = 0;
    const bool has = server_.LatestPose(pose, age);
    const bool fresh = has && pose.sequence != lastSeq_;
    if (fresh) lastSeq_ = pose.sequence;
    hmd_->UpdatePose(fresh ? &pose : nullptr);
    for (int i = 0; i < 2; ++i) {
        // Sem pacotes há mais de 1 s: considera os controles desconectados.
        controllers_[i]->Update(has && age < 1.0 ? &pose.controllers[i] : nullptr);
    }

    vr::VREvent_t ev;
    while (vr::VRServerDriverHost()->PollNextEvent(&ev, sizeof(ev))) {
        if (ev.eventType != vr::VREvent_Input_HapticVibration) continue;
        const auto& hv = ev.data.hapticVibration;
        for (int i = 0; i < 2; ++i) {
            if (hv.componentHandle != controllers_[i]->HapticHandle()) continue;
            Haptic h{static_cast<uint8_t>(i), {}, hv.fDurationSeconds, hv.fFrequency, hv.fAmplitude};
            server_.SendHaptic(h);
        }
    }
}

void DeviceProvider::Cleanup() {
    server_.Stop();
    if (hmd_) hmd_->Deactivate();
    DriverContextReady() = false;
    VR_CLEANUP_SERVER_DRIVER_CONTEXT();
}

}  // namespace phonesteam
