// Encoder H.264 de baixa latência via FFmpeg (NVENC → AMF → QSV → x264).
#pragma once
#include <cstdint>
#include <string>
#include <vector>

struct AVCodecContext;
struct AVFrame;
struct AVPacket;
struct SwsContext;

namespace phonesteam {

enum class PixelFormat { BGRA, RGBA };

class VideoEncoder {
public:
    ~VideoEncoder() { Close(); }
    bool Open(int width, int height, int fps, int bitrateMbps, const std::string& preferred);
    void Close();
    bool IsOpen() const { return ctx_ != nullptr; }
    void RequestIdr() { forceIdr_ = true; }
    /** Codifica um frame RGBA/BGRA e devolve o bitstream Annex-B (pode ser vazio). */
    bool Encode(const uint8_t* pixels, int stride, PixelFormat fmt, std::vector<uint8_t>& out, bool& keyframe);
    const std::string& Name() const { return name_; }

private:
    AVCodecContext* ctx_ = nullptr;
    AVFrame* frame_ = nullptr;
    AVPacket* pkt_ = nullptr;
    SwsContext* sws_ = nullptr;
    PixelFormat swsFmt_ = PixelFormat::BGRA;
    int64_t pts_ = 0;
    bool forceIdr_ = true;
    std::string name_;
};

}  // namespace phonesteam
