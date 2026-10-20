#include "phone_server.h"

#include <chrono>

#include "log.h"

namespace phonesteam {

bool PhoneServer::Start(Callbacks cb) {
    cb_ = std::move(cb);
    if (!net::Init()) return false;
    udp_ = net::BindUdp(kUdpPosePort);
    listen_ = net::ListenTcp(kTcpPort);
    if (udp_ == kInvalidSocket || listen_ == kInvalidSocket) {
        Log("Falha ao abrir as portas %u/udp e %u/tcp (firewall? outra instância?)", kUdpPosePort, kTcpPort);
        Stop();
        return false;
    }
    running_ = true;
    udpThread_ = std::thread(&PhoneServer::UdpLoop, this);
    acceptThread_ = std::thread(&PhoneServer::AcceptLoop, this);
    senderThread_ = std::thread(&PhoneServer::SenderLoop, this);
    Log("Aguardando o celular em %u/tcp e %u/udp", kTcpPort, kUdpPosePort);
    return true;
}

void PhoneServer::Stop() {
    running_ = false;
    queueCv_.notify_all();
    net::Close(udp_);
    net::Close(listen_);
    net::Close(streamSocket_.exchange(kInvalidSocket));
    udp_ = listen_ = kInvalidSocket;
    for (auto* t : {&udpThread_, &acceptThread_, &senderThread_})
        if (t->joinable()) t->join();
    std::lock_guard<std::mutex> lk(clientThreadsMutex_);
    for (auto& t : clientThreads_)
        if (t.joinable()) t.join();
    clientThreads_.clear();
    net::Shutdown();
}

void PhoneServer::StorePose(const PoseBody& p) {
    std::lock_guard<std::mutex> lk(poseMutex_);
    // Pacotes chegam por UDP e TCP; aceita só os mais novos (com tolerância a reinício do app).
    if (hasPose_ && p.sequence <= lastSeq_ && lastSeq_ - p.sequence < 1000) return;
    pose_ = p;
    lastSeq_ = p.sequence;
    hasPose_ = true;
    poseTime_ = std::chrono::steady_clock::now();
}

bool PhoneServer::LatestPose(PoseBody& out, double& ageSeconds) const {
    std::lock_guard<std::mutex> lk(poseMutex_);
    if (!hasPose_) return false;
    out = pose_;
    ageSeconds = std::chrono::duration<double>(std::chrono::steady_clock::now() - poseTime_).count();
    return true;
}

void PhoneServer::UdpLoop() {
    UdpPosePacket pkt;
    while (running_) {
        if (!net::WaitReadable(udp_, 200)) continue;
        int n = recvfrom(udp_, reinterpret_cast<char*>(&pkt), sizeof(pkt), 0, nullptr, nullptr);
        if (n != static_cast<int>(sizeof(pkt))) continue;
        if (pkt.magic != kMagic || pkt.version != kVersion || pkt.type != MSG_POSE) continue;
        StorePose(pkt.body);
    }
}

void PhoneServer::AcceptLoop() {
    while (running_) {
        if (!net::WaitReadable(listen_, 200)) continue;
        socket_t s = accept(listen_, nullptr, nullptr);
        if (s == kInvalidSocket) continue;
        int one = 1;
        setsockopt(s, IPPROTO_TCP, TCP_NODELAY, reinterpret_cast<const char*>(&one), sizeof(one));
        int sndbuf = 4 << 20;
        setsockopt(s, SOL_SOCKET, SO_SNDBUF, reinterpret_cast<const char*>(&sndbuf), sizeof(sndbuf));
        std::lock_guard<std::mutex> lk(clientThreadsMutex_);
        clientThreads_.emplace_back(&PhoneServer::ClientLoop, this, s);
    }
}

void PhoneServer::ClientLoop(socket_t s) {
    const std::string peer = net::PeerAddress(s);
    bool isStream = false;
    std::vector<uint8_t> payload;
    while (running_) {
        if (!net::WaitReadable(s, 500)) {
            if (!isStream && !running_) break;
            continue;
        }
        MsgHeader h;
        if (!net::RecvAll(s, &h, sizeof(h)) || h.magic != kMagic || h.length > (1u << 20)) break;
        payload.resize(h.length);
        if (h.length && !net::RecvAll(s, payload.data(), h.length)) break;

        switch (h.type) {
            case MSG_HELLO: {
                if (h.length < sizeof(Hello)) break;
                Hello hello;
                std::memcpy(&hello, payload.data(), sizeof(hello));
                if (hello.mode == MODE_STREAM) {
                    socket_t old = streamSocket_.exchange(s);
                    if (old != kInvalidSocket && old != s) net::Close(old);  // derruba o cliente antigo
                    isStream = true;
                    Log("Celular conectado (%s): %ux%u/olho %u Hz %u Mbps", peer.c_str(), hello.eyeWidth,
                        hello.eyeHeight, hello.fps, hello.bitrateMbps);
                    if (cb_.onStreamClient) cb_.onStreamClient(hello);
                }
                break;
            }
            case MSG_POSE:
                if (h.length == sizeof(PoseBody)) {
                    PoseBody p;
                    std::memcpy(&p, payload.data(), sizeof(p));
                    StorePose(p);
                }
                break;
            case MSG_REQUEST_IDR:
                if (cb_.onRequestIdr) cb_.onRequestIdr();
                break;
            case MSG_APP_LIST_REQ: {
                std::string json = cb_.appListJson ? cb_.appListJson() : "[]";
                std::lock_guard<std::mutex> lk(sendMutex_);
                SendMessage(s, MSG_APP_LIST, json.data(), static_cast<uint32_t>(json.size()));
                break;
            }
            case MSG_LAUNCH_APP:
                if (cb_.onLaunchApp) cb_.onLaunchApp(std::string(payload.begin(), payload.end()));
                break;
            default:
                break;
        }
    }
    socket_t expected = s;
    if (streamSocket_.compare_exchange_strong(expected, kInvalidSocket)) {
        Log("Celular desconectado (%s)", peer.c_str());
        if (cb_.onStreamClientLost) cb_.onStreamClientLost();
    }
    net::Close(s);
}

bool PhoneServer::SendMessage(socket_t s, uint8_t type, const void* payload, uint32_t len) {
    MsgHeader h{kMagic, type, 0, 0, len};
    return net::SendAll(s, &h, sizeof(h)) && (len == 0 || net::SendAll(s, payload, len));
}

void PhoneServer::SendConfig(const Config& c) {
    socket_t s = streamSocket_;
    if (s == kInvalidSocket) return;
    std::lock_guard<std::mutex> lk(sendMutex_);
    SendMessage(s, MSG_CONFIG, &c, sizeof(c));
}

void PhoneServer::SendHaptic(const Haptic& hp) {
    socket_t s = streamSocket_;
    if (s == kInvalidSocket) return;
    std::lock_guard<std::mutex> lk(sendMutex_);
    SendMessage(s, MSG_HAPTIC, &hp, sizeof(hp));
}

void PhoneServer::SendVideo(const VideoHeader& h, std::vector<uint8_t>&& data) {
    bool dropped = false;
    {
        std::lock_guard<std::mutex> lk(queueMutex_);
        if (h.keyframe) {
            queue_.clear();
        } else if (queue_.size() >= 2) {
            // Rede lenta: descarta para não acumular latência. Sem os P-frames descartados o decoder
            // corromperia a imagem, então pula tudo até o próximo keyframe e pede um ao encoder.
            queue_.clear();
            dropping_ = true;
            dropped = true;
        }
        if (!dropping_ || h.keyframe) queue_.push_back({h, std::move(data)});
        if (h.keyframe) dropping_ = false;
    }
    queueCv_.notify_one();
    if (dropped && cb_.onRequestIdr) cb_.onRequestIdr();
}

void PhoneServer::SenderLoop() {
    std::vector<uint8_t> buf;
    while (running_) {
        QueuedFrame f;
        {
            std::unique_lock<std::mutex> lk(queueMutex_);
            queueCv_.wait(lk, [&] { return !queue_.empty() || !running_; });
            if (!running_) break;
            f = std::move(queue_.front());
            queue_.pop_front();
        }
        socket_t s = streamSocket_;
        if (s == kInvalidSocket) continue;
        buf.resize(sizeof(VideoHeader) + f.data.size());
        std::memcpy(buf.data(), &f.header, sizeof(VideoHeader));
        std::memcpy(buf.data() + sizeof(VideoHeader), f.data.data(), f.data.size());
        std::lock_guard<std::mutex> lk(sendMutex_);
        SendMessage(s, MSG_VIDEO, buf.data(), static_cast<uint32_t>(buf.size()));
    }
}

}  // namespace phonesteam
