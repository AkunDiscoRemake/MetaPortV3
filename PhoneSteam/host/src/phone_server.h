// Servidor de rede do host: recebe pose/entrada do celular e envia vídeo/háptico.
#pragma once
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <deque>
#include <functional>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "net.h"
#include "protocol.h"

namespace phonesteam {

class PhoneServer {
public:
    struct Callbacks {
        std::function<void(const Hello&)> onStreamClient;    // celular conectou pedindo streaming
        std::function<void()> onStreamClientLost;
        std::function<void()> onRequestIdr;
        std::function<std::string()> appListJson;
        std::function<void(const std::string&)> onLaunchApp;
    };

    bool Start(Callbacks cb);
    void Stop();

    /** Pose mais recente; `ageSeconds` = tempo desde que chegou. */
    bool LatestPose(PoseBody& out, double& ageSeconds) const;
    bool HasStreamClient() const { return streamSocket_ != kInvalidSocket; }

    void SendConfig(const Config& c);
    /** Enfileira um frame; frames antigos (não-keyframe) são descartados se a rede atrasar. */
    void SendVideo(const VideoHeader& h, std::vector<uint8_t>&& data);
    void SendHaptic(const Haptic& h);

private:
    void UdpLoop();
    void AcceptLoop();
    void ClientLoop(socket_t s);
    void SenderLoop();
    void StorePose(const PoseBody& p);
    bool SendMessage(socket_t s, uint8_t type, const void* payload, uint32_t len);

    Callbacks cb_;
    std::atomic<bool> running_{false};
    socket_t udp_ = kInvalidSocket;
    socket_t listen_ = kInvalidSocket;
    std::atomic<socket_t> streamSocket_{kInvalidSocket};
    std::thread udpThread_, acceptThread_, senderThread_;
    std::mutex clientThreadsMutex_;
    std::vector<std::thread> clientThreads_;

    mutable std::mutex poseMutex_;
    PoseBody pose_{};
    bool hasPose_ = false;
    uint32_t lastSeq_ = 0;
    std::chrono::steady_clock::time_point poseTime_;

    std::mutex sendMutex_;  // serializa escritas no socket de streaming

    struct QueuedFrame {
        VideoHeader header;
        std::vector<uint8_t> data;
    };
    std::mutex queueMutex_;
    std::condition_variable queueCv_;
    std::deque<QueuedFrame> queue_;
    bool dropping_ = false;  // protegido por queueMutex_
};

}  // namespace phonesteam
