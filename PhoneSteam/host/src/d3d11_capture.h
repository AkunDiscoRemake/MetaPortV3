// Captura do backbuffer do compositor do SteamVR (textura D3D11 compartilhada) para a CPU.
#pragma once
#include <cstdint>
#include <vector>

#include "encoder.h"

namespace phonesteam {

class FrameCapture {
public:
    FrameCapture();
    ~FrameCapture();
    bool Init();
    /** LUID do adaptador usado (para o SteamVR renderizar na mesma GPU). */
    uint64_t AdapterLuid() const { return luid_; }
    /** Copia a textura compartilhada para a área de staging (rápido, chamado no Present). */
    bool Copy(uint64_t sharedHandle);
    /** Mapeia o último frame copiado (bloqueia até a GPU terminar a cópia). */
    bool Read(std::vector<uint8_t>& pixels, int& width, int& height, int& stride, PixelFormat& fmt);

private:
    struct Impl;
    Impl* impl_ = nullptr;
    uint64_t luid_ = 0;
};

}  // namespace phonesteam
