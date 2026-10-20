// Host de teste sem SteamVR: roda o PhoneServer real + encoder e transmite um padrão sintético.
// Uso: phonesteam_selftest [segundos]
#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <thread>

#include "../src/encoder.h"
#include "../src/phone_server.h"

using namespace phonesteam;

int main(int argc, char** argv) {
    const int seconds = argc > 1 ? std::atoi(argv[1]) : 10;
    PhoneServer server;
    std::atomic<bool> client{false};
    Hello hello{};
    VideoEncoder enc;
    PhoneServer::Callbacks cb;
    cb.onStreamClient = [&](const Hello& h) {
        hello = h;
        Config c{h.eyeWidth, h.eyeHeight, h.fps, 0, h.bitrateMbps};
        server.SendConfig(c);
        client = true;
    };
    cb.onRequestIdr = [&] { enc.RequestIdr(); };
    cb.appListJson = [] { return std::string(R"([{"key":"steam.app.620980","name":"Beat Saber"}])"); };
    cb.onLaunchApp = [](const std::string& k) { std::printf("LAUNCH %s\n", k.c_str()); std::fflush(stdout); };
    if (!server.Start(cb)) return 1;
    std::printf("READY\n");
    std::fflush(stdout);

    std::vector<uint8_t> px, bits;
    uint32_t frame = 0;
    const auto end = std::chrono::steady_clock::now() + std::chrono::seconds(seconds);
    while (std::chrono::steady_clock::now() < end) {
        std::this_thread::sleep_for(std::chrono::milliseconds(33));
        PoseBody pose;
        double age;
        if (server.LatestPose(pose, age) && frame % 15 == 0)
            std::printf("POSE seq=%u head=(%.2f %.2f %.2f %.2f) R.trigger=%.2f\n", pose.sequence, pose.hqx, pose.hqy,
                        pose.hqz, pose.hqw, pose.controllers[1].trigger);
        if (!client) continue;
        const int w = hello.eyeWidth * 2, h = hello.eyeHeight;
        if (!enc.IsOpen() && !enc.Open(w, h, 30, hello.bitrateMbps, "libx264")) return 2;
        px.assign(static_cast<size_t>(w) * h * 4, 0);
        for (int y = 0; y < h; ++y)
            for (int x = 0; x < w; ++x) {
                uint8_t* p = &px[(static_cast<size_t>(y) * w + x) * 4];
                p[0] = static_cast<uint8_t>(x + frame * 4);
                p[1] = static_cast<uint8_t>(y);
                p[2] = x < w / 2 ? 255 : 0;
                p[3] = 255;
            }
        bool key = false;
        if (!enc.Encode(px.data(), w * 4, PixelFormat::BGRA, bits, key) || bits.empty()) continue;
        VideoHeader vh{};
        vh.frameIndex = ++frame;
        vh.qw = 1;
        vh.keyframe = key;
        server.SendVideo(vh, std::move(bits));
        bits = {};
    }
    server.Stop();
    std::printf("DONE frames=%u\n", frame);
    return 0;
}
