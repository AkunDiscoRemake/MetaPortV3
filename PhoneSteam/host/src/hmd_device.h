// HMD virtual "PhoneSteam Cardboard": recebe a pose do celular e transmite o backbuffer do compositor.
#pragma once
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <mutex>
#include <thread>

#include <openvr_driver.h>

#include "d3d11_capture.h"
#include "encoder.h"
#include "phone_server.h"
#include "settings.h"

namespace phonesteam {

class HmdDevice : public vr::ITrackedDeviceServerDriver, public vr::IVRDisplayComponent, public vr::IVRVirtualDisplay {
public:
    HmdDevice(PhoneServer& server, const HmdConfig& cfg);
    ~HmdDevice();

    // ITrackedDeviceServerDriver
    vr::EVRInitError Activate(uint32_t objectId) override;
    void Deactivate() override;
    void EnterStandby() override {}
    void* GetComponent(const char* name) override;
    void DebugRequest(const char*, char* response, uint32_t size) override {
        if (size) response[0] = 0;
    }
    vr::DriverPose_t GetPose() override;

    // IVRDisplayComponent
    void GetWindowBounds(int32_t* x, int32_t* y, uint32_t* w, uint32_t* h) override;
    bool IsDisplayOnDesktop() override { return false; }
    bool IsDisplayRealDisplay() override { return false; }
    void GetRecommendedRenderTargetSize(uint32_t* w, uint32_t* h) override;
    void GetEyeOutputViewport(vr::EVREye eye, uint32_t* x, uint32_t* y, uint32_t* w, uint32_t* h) override;
    void GetProjectionRaw(vr::EVREye eye, float* left, float* right, float* top, float* bottom) override;
    vr::DistortionCoordinates_t ComputeDistortion(vr::EVREye eye, float u, float v) override;
    bool ComputeInverseDistortion(vr::HmdVector2_t* result, vr::EVREye eye, uint32_t channel, float u, float v) override;

    // IVRVirtualDisplay
    void Present(const vr::PresentInfo_t* info, uint32_t size) override;
    void WaitForPresent() override;
    bool GetTimeSinceLastVsync(float* secondsSinceVsync, uint64_t* frameCounter) override;

    void UpdatePose(const PoseBody* pose);
    void OnClientConnected();
    void RequestIdr() { encoder_.RequestIdr(); }
    uint32_t Id() const { return id_; }

private:
    void EncodeLoop();

    PhoneServer& server_;
    HmdConfig cfg_;
    uint32_t id_ = vr::k_unTrackedDeviceIndexInvalid;
    vr::PropertyContainerHandle_t props_ = vr::k_ulInvalidPropertyContainer;

    std::mutex poseMutex_;
    vr::DriverPose_t pose_{};

    FrameCapture capture_;
    bool captureOk_ = false;
    VideoEncoder encoder_;
    std::atomic<bool> running_{false};
    std::thread encodeThread_;
    std::mutex frameMutex_;
    std::condition_variable frameCv_;
    bool frameReady_ = false;
    vr::HmdQuaternion_t framePose_{1, 0, 0, 0};
    uint32_t frameIndex_ = 0;
    std::chrono::steady_clock::time_point vsyncStart_ = std::chrono::steady_clock::now();
};

}  // namespace phonesteam
