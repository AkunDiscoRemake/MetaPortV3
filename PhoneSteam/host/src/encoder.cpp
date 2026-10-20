#include "encoder.h"

#include "log.h"

#ifdef PHONESTEAM_WITH_FFMPEG
extern "C" {
#include <libavcodec/avcodec.h>
#include <libavutil/imgutils.h>
#include <libavutil/opt.h>
#include <libswscale/swscale.h>
}
#endif

namespace phonesteam {

#ifdef PHONESTEAM_WITH_FFMPEG

bool VideoEncoder::Open(int width, int height, int fps, int bitrateMbps, const std::string& preferred) {
    Close();
    std::vector<std::string> names;
    if (!preferred.empty() && preferred != "auto") names.push_back(preferred);
    for (const char* n : {"h264_nvenc", "h264_amf", "h264_qsv", "libx264"}) names.emplace_back(n);

    for (const auto& n : names) {
        const AVCodec* codec = avcodec_find_encoder_by_name(n.c_str());
        if (!codec) continue;
        AVCodecContext* c = avcodec_alloc_context3(codec);
        c->width = width;
        c->height = height;
        c->time_base = AVRational{1, fps};
        c->framerate = AVRational{fps, 1};
        c->pix_fmt = AV_PIX_FMT_YUV420P;
        c->bit_rate = static_cast<int64_t>(bitrateMbps) * 1000000;
        c->rc_max_rate = c->bit_rate;
        c->rc_buffer_size = static_cast<int>(c->bit_rate / fps * 2);  // ~2 frames de buffer
        c->gop_size = fps * 2;
        c->max_b_frames = 0;
        c->thread_count = 1;
        c->flags |= AV_CODEC_FLAG_LOW_DELAY;
        c->profile = AV_PROFILE_H264_HIGH;

        if (n == "h264_nvenc") {
            av_opt_set(c->priv_data, "preset", "p1", 0);
            av_opt_set(c->priv_data, "tune", "ull", 0);
            av_opt_set(c->priv_data, "rc", "cbr", 0);
            av_opt_set_int(c->priv_data, "zerolatency", 1, 0);
            av_opt_set_int(c->priv_data, "forced-idr", 1, 0);
            av_opt_set_int(c->priv_data, "delay", 0, 0);
        } else if (n == "h264_amf") {
            av_opt_set(c->priv_data, "usage", "ultralowlatency", 0);
            av_opt_set(c->priv_data, "quality", "speed", 0);
            av_opt_set(c->priv_data, "rc", "cbr", 0);
        } else if (n == "h264_qsv") {
            av_opt_set(c->priv_data, "preset", "veryfast", 0);
            av_opt_set_int(c->priv_data, "async_depth", 1, 0);
        } else if (n == "libx264") {
            av_opt_set(c->priv_data, "preset", "ultrafast", 0);
            av_opt_set(c->priv_data, "tune", "zerolatency", 0);
            av_opt_set_int(c->priv_data, "forced-idr", 1, 0);
        }

        if (avcodec_open2(c, codec, nullptr) == 0) {
            ctx_ = c;
            name_ = n;
            break;
        }
        avcodec_free_context(&c);
    }
    if (!ctx_) {
        Log("Nenhum encoder H.264 disponível no FFmpeg");
        return false;
    }
    frame_ = av_frame_alloc();
    frame_->format = ctx_->pix_fmt;
    frame_->width = width;
    frame_->height = height;
    av_frame_get_buffer(frame_, 32);
    pkt_ = av_packet_alloc();
    forceIdr_ = true;
    Log("Encoder %s aberto: %dx%d @ %d fps, %d Mbps", name_.c_str(), width, height, fps, bitrateMbps);
    return true;
}

void VideoEncoder::Close() {
    if (sws_) sws_freeContext(sws_);
    if (frame_) av_frame_free(&frame_);
    if (pkt_) av_packet_free(&pkt_);
    if (ctx_) avcodec_free_context(&ctx_);
    sws_ = nullptr;
}

bool VideoEncoder::Encode(const uint8_t* pixels, int stride, PixelFormat fmt, std::vector<uint8_t>& out, bool& keyframe) {
    out.clear();
    keyframe = false;
    if (!ctx_) return false;
    if (!sws_ || swsFmt_ != fmt) {
        if (sws_) sws_freeContext(sws_);
        AVPixelFormat src = fmt == PixelFormat::BGRA ? AV_PIX_FMT_BGRA : AV_PIX_FMT_RGBA;
        sws_ = sws_getContext(ctx_->width, ctx_->height, src, ctx_->width, ctx_->height, AV_PIX_FMT_YUV420P,
                              SWS_POINT, nullptr, nullptr, nullptr);
        swsFmt_ = fmt;
    }
    av_frame_make_writable(frame_);
    const uint8_t* srcData[1] = {pixels};
    int srcStride[1] = {stride};
    sws_scale(sws_, srcData, srcStride, 0, ctx_->height, frame_->data, frame_->linesize);
    frame_->pts = pts_++;
    if (forceIdr_) {
        frame_->pict_type = AV_PICTURE_TYPE_I;
        frame_->flags |= AV_FRAME_FLAG_KEY;
        forceIdr_ = false;
    } else {
        frame_->pict_type = AV_PICTURE_TYPE_NONE;
        frame_->flags &= ~AV_FRAME_FLAG_KEY;
    }
    if (avcodec_send_frame(ctx_, frame_) < 0) return false;
    while (avcodec_receive_packet(ctx_, pkt_) == 0) {
        out.insert(out.end(), pkt_->data, pkt_->data + pkt_->size);
        if (pkt_->flags & AV_PKT_FLAG_KEY) keyframe = true;
        av_packet_unref(pkt_);
    }
    return true;
}

#else  // sem FFmpeg: compila, mas não transmite vídeo (útil para testar pose/controles)

bool VideoEncoder::Open(int, int, int, int, const std::string&) {
    Log("Compilado sem FFmpeg: streaming de vídeo desativado");
    return false;
}
void VideoEncoder::Close() {}
bool VideoEncoder::Encode(const uint8_t*, int, PixelFormat, std::vector<uint8_t>& out, bool& keyframe) {
    out.clear();
    keyframe = false;
    return false;
}

#endif

}  // namespace phonesteam
