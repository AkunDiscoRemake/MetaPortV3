#include "hmd_device.h"

#include <cmath>

#include "log.h"

namespace phonesteam {

HmdDevice::HmdDevice(PhoneServer& server, const HmdConfig& cfg) : server_(server), cfg_(cfg) {
    pose_.poseIsValid = true;
    pose_.result = vr::TrackingResult_Running_OK;
    pose_.deviceIsConnected = true;
    pose_.qWorldFromDriverRotation.w = 1;
    pose_.qDriverFromHeadRotation.w = 1;
    pose_.qRotation.w = 1;
    pose_.vecPosition[1] = cfg.eyeHeightMeters;
}

HmdDevice::~HmdDevice() { Deactivate(); }

vr::EVRInitError HmdDevice::Activate(uint32_t objectId) {
    id_ = objectId;
    auto* p = vr::VRProperties();
    props_ = p->TrackedDeviceToPropertyContainer(objectId);

    p->SetStringProperty(props_, vr::Prop_ModelNumber_String, "PhoneSteam Cardboard");
    p->SetStringProperty(props_, vr::Prop_ManufacturerName_String, "PhoneSteam");
    p->SetStringProperty(props_, vr::Prop_TrackingSystemName_String, "phonesteam");
    p->SetStringProperty(props_, vr::Prop_RenderModelName_String, "generic_hmd");
    p->SetFloatProperty(props_, vr::Prop_UserIpdMeters_Float, cfg_.ipd);
    p->SetFloatProperty(props_, vr::Prop_UserHeadToEyeDepthMeters_Float, 0.f);
    p->SetFloatProperty(props_, vr::Prop_DisplayFrequency_Float, static_cast<float>(cfg_.fps));
    p->SetFloatProperty(props_, vr::Prop_SecondsFromVsyncToPhotons_Float, 0.011f);
    p->SetBoolProperty(props_, vr::Prop_IsOnDesktop_Bool, false);
    p->SetBoolProperty(props_, vr::Prop_HasDisplayComponent_Bool, true);
    p->SetBoolProperty(props_, vr::Prop_HasVirtualDisplayComponent_Bool, true);
    p->SetBoolProperty(props_, vr::Prop_DisplayDebugMode_Bool, false);
    p->SetBoolProperty(props_, vr::Prop_DeviceProvidesBatteryStatus_Bool, false);
    p->SetInt32Property(props_, vr::Prop_DeviceClass_Int32, vr::TrackedDeviceClass_HMD);
    p->SetStringProperty(props_, vr::Prop_InputProfilePath_String, "{phonesteam}/input/phonesteam_hmd_profile.json");

    captureOk_ = capture_.Init();
    if (captureOk_) p->SetUint64Property(props_, vr::Prop_GraphicsAdapterLuid_Uint64, capture_.AdapterLuid());

    running_ = true;
    encodeThread_ = std::thread(&HmdDevice::EncodeLoop, this);
    Log("HMD ativado: %dx%d/olho @ %d Hz", cfg_.eyeWidth, cfg_.eyeHeight, cfg_.fps);
    return vr::VRInitError_None;
}

void HmdDevice::Deactivate() {
    if (!running_) return;
    running_ = false;
    frameCv_.notify_all();
    if (encodeThread_.joinable()) encodeThread_.join();
    encoder_.Close();
    id_ = vr::k_unTrackedDeviceIndexInvalid;
}

void* HmdDevice::GetComponent(const char* name) {
    if (!std::strcmp(name, vr::IVRDisplayComponent_Version)) return static_cast<vr::IVRDisplayComponent*>(this);
    if (!std::strcmp(name, vr::IVRVirtualDisplay_Version)) return static_cast<vr::IVRVirtualDisplay*>(this);
    return nullptr;
}

vr::DriverPose_t HmdDevice::GetPose() {
    std::lock_guard<std::mutex> lk(poseMutex_);
    return pose_;
}

void HmdDevice::UpdatePose(const PoseBody* p) {
    if (id_ == vr::k_unTrackedDeviceIndexInvalid) return;
    vr::DriverPose_t pose;
    {
        std::lock_guard<std::mutex> lk(poseMutex_);
        if (p) {
            pose_.qRotation = {p->hqw, p->hqx, p->hqy, p->hqz};
            pose_.vecPosition[0] = p->hpx;
            pose_.vecPosition[1] = p->hpy;
            pose_.vecPosition[2] = p->hpz;
            pose_.result = vr::TrackingResult_Running_OK;
        }
        pose = pose_;
    }
    vr::VRServerDriverHost()->TrackedDevicePoseUpdated(id_, pose, sizeof(pose));
}

void HmdDevice::OnClientConnected() {
    Config c{static_cast<uint16_t>(cfg_.eyeWidth), static_cast<uint16_t>(cfg_.eyeHeight), static_cast<uint8_t>(cfg_.fps), 0,
             static_cast<uint16_t>(cfg_.bitrateMbps)};
    server_.SendConfig(c);
    encoder_.RequestIdr();
}

// ---- display ----

void HmdDevice::GetWindowBounds(int32_t* x, int32_t* y, uint32_t* w, uint32_t* h) {
    *x = 0;
    *y = 0;
    *w = static_cast<uint32_t>(cfg_.eyeWidth * 2);
    *h = static_cast<uint32_t>(cfg_.eyeHeight);
}

void HmdDevice::GetRecommendedRenderTargetSize(uint32_t* w, uint32_t* h) {
    *w = static_cast<uint32_t>(cfg_.eyeWidth);
    *h = static_cast<uint32_t>(cfg_.eyeHeight);
}

void HmdDevice::GetEyeOutputViewport(vr::EVREye eye, uint32_t* x, uint32_t* y, uint32_t* w, uint32_t* h) {
    *x = eye == vr::Eye_Left ? 0 : static_cast<uint32_t>(cfg_.eyeWidth);
    *y = 0;
    *w = static_cast<uint32_t>(cfg_.eyeWidth);
    *h = static_cast<uint32_t>(cfg_.eyeHeight);
}

void HmdDevice::GetProjectionRaw(vr::EVREye eye, float* left, float* right, float* top, float* bottom) {
    // OpenVR: esquerda/topo negativos. O olho direito é o espelho do esquerdo.
    const bool l = eye == vr::Eye_Left;
    *left = -(l ? cfg_.tanLeft : cfg_.tanRight);
    *right = l ? cfg_.tanRight : cfg_.tanLeft;
    *top = -cfg_.tanUp;
    *bottom = cfg_.tanDown;
}

vr::DistortionCoordinates_t HmdDevice::ComputeDistortion(vr::EVREye, float u, float v) {
    // Sem distorção: o celular aplica a distorção da lente do Cardboard localmente.
    vr::DistortionCoordinates_t d{};
    d.rfRed[0] = d.rfGreen[0] = d.rfBlue[0] = u;
    d.rfRed[1] = d.rfGreen[1] = d.rfBlue[1] = v;
    return d;
}

bool HmdDevice::ComputeInverseDistortion(vr::HmdVector2_t* result, vr::EVREye, uint32_t, float u, float v) {
    result->v[0] = u;
    result->v[1] = v;
    return true;
}

// ---- virtual display ----

void HmdDevice::Present(const vr::PresentInfo_t* info, uint32_t) {
    if (!captureOk_ || !server_.HasStreamClient()) return;
    vr::HmdQuaternion_t q;
    {
        std::lock_guard<std::mutex> lk(poseMutex_);
        q = pose_.qRotation;
    }
    std::lock_guard<std::mutex> lk(frameMutex_);
    if (capture_.Copy(info->backbufferTextureHandle)) {
        framePose_ = q;
        frameReady_ = true;
        frameCv_.notify_one();
    }
}

void HmdDevice::WaitForPresent() {
    // Ritma o compositor no "vsync" virtual do celular.
    float since;
    uint64_t counter;
    GetTimeSinceLastVsync(&since, &counter);
    const double period = 1.0 / cfg_.fps;
    std::this_thread::sleep_for(std::chrono::duration<double>(period - since));
}

bool HmdDevice::GetTimeSinceLastVsync(float* secondsSinceVsync, uint64_t* frameCounter) {
    const double period = 1.0 / cfg_.fps;
    const double t = std::chrono::duration<double>(std::chrono::steady_clock::now() - vsyncStart_).count();
    const auto n = static_cast<uint64_t>(t / period);
    *secondsSinceVsync = static_cast<float>(t - n * period);
    *frameCounter = n;
    return true;
}

void HmdDevice::EncodeLoop() {
    std::vector<uint8_t> pixels, bitstream;
    int openedW = 0, openedH = 0;
    while (running_) {
        vr::HmdQuaternion_t q;
        int w = 0, h = 0, stride = 0;
        PixelFormat fmt = PixelFormat::BGRA;
        {
            std::unique_lock<std::mutex> lk(frameMutex_);
            frameCv_.wait(lk, [&] { return frameReady_ || !running_; });
            if (!running_) break;
            frameReady_ = false;
            q = framePose_;
            if (!capture_.Read(pixels, w, h, stride, fmt)) continue;
        }
        if (!encoder_.IsOpen() || w != openedW || h != openedH) {
            if (!encoder_.Open(w, h, cfg_.fps, cfg_.bitrateMbps, cfg_.encoder)) {
                std::this_thread::sleep_for(std::chrono::seconds(1));
                continue;
            }
            openedW = w;
            openedH = h;
        }
        bool key = false;
        if (!encoder_.Encode(pixels.data(), stride, fmt, bitstream, key) || bitstream.empty()) continue;
        VideoHeader vh{};
        vh.frameIndex = ++frameIndex_;
        vh.qx = static_cast<float>(q.x);
        vh.qy = static_cast<float>(q.y);
        vh.qz = static_cast<float>(q.z);
        vh.qw = static_cast<float>(q.w);
        vh.keyframe = key ? 1 : 0;
        server_.SendVideo(vh, std::move(bitstream));
        bitstream = {};
    }
}

}  // namespace phonesteam
