#include "d3d11_capture.h"

#include <cstdlib>
#include <cstring>

#include "log.h"

#ifdef _WIN32
#include <d3d11.h>
#include <dxgi.h>
#include <wrl/client.h>

#include <unordered_map>

using Microsoft::WRL::ComPtr;

namespace phonesteam {

struct FrameCapture::Impl {
    ComPtr<ID3D11Device> device;
    ComPtr<ID3D11DeviceContext> context;
    std::unordered_map<uint64_t, ComPtr<ID3D11Texture2D>> shared;  // cache por handle
    ComPtr<ID3D11Texture2D> staging;
    D3D11_TEXTURE2D_DESC desc{};
    bool hasFrame = false;
};

FrameCapture::FrameCapture() : impl_(new Impl) {}
FrameCapture::~FrameCapture() { delete impl_; }

bool FrameCapture::Init() {
    ComPtr<IDXGIFactory1> factory;
    if (FAILED(CreateDXGIFactory1(IID_PPV_ARGS(&factory)))) return false;
    ComPtr<IDXGIAdapter1> adapter;
    if (FAILED(factory->EnumAdapters1(0, &adapter))) return false;
    DXGI_ADAPTER_DESC1 ad;
    adapter->GetDesc1(&ad);
    luid_ = (static_cast<uint64_t>(ad.AdapterLuid.HighPart) << 32) | ad.AdapterLuid.LowPart;
    D3D_FEATURE_LEVEL level;
    HRESULT hr = D3D11CreateDevice(adapter.Get(), D3D_DRIVER_TYPE_UNKNOWN, nullptr, 0, nullptr, 0, D3D11_SDK_VERSION,
                                   &impl_->device, &level, &impl_->context);
    if (FAILED(hr)) {
        Log("D3D11CreateDevice falhou: 0x%08x", static_cast<unsigned>(hr));
        return false;
    }
    char name[128];
    wcstombs(name, ad.Description, sizeof(name));
    Log("Captura D3D11 na GPU: %s", name);
    return true;
}

bool FrameCapture::Copy(uint64_t handle) {
    auto& tex = impl_->shared[handle];
    if (!tex) {
        HRESULT hr = impl_->device->OpenSharedResource(reinterpret_cast<HANDLE>(handle), IID_PPV_ARGS(&tex));
        if (FAILED(hr)) {
            impl_->shared.erase(handle);
            return false;
        }
    }
    D3D11_TEXTURE2D_DESC d;
    tex->GetDesc(&d);
    if (!impl_->staging || d.Width != impl_->desc.Width || d.Height != impl_->desc.Height || d.Format != impl_->desc.Format) {
        D3D11_TEXTURE2D_DESC s = d;
        s.Usage = D3D11_USAGE_STAGING;
        s.BindFlags = 0;
        s.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
        s.MiscFlags = 0;
        s.MipLevels = 1;
        s.ArraySize = 1;
        s.SampleDesc = {1, 0};
        impl_->staging.Reset();
        if (FAILED(impl_->device->CreateTexture2D(&s, nullptr, &impl_->staging))) return false;
        impl_->desc = d;
    }
    ComPtr<IDXGIKeyedMutex> mutex;
    tex.As(&mutex);
    if (mutex && mutex->AcquireSync(0, 10) != S_OK) return false;
    impl_->context->CopySubresourceRegion(impl_->staging.Get(), 0, 0, 0, 0, tex.Get(), 0, nullptr);
    if (mutex) mutex->ReleaseSync(0);
    impl_->context->Flush();
    impl_->hasFrame = true;
    return true;
}

bool FrameCapture::Read(std::vector<uint8_t>& pixels, int& width, int& height, int& stride, PixelFormat& fmt) {
    if (!impl_->hasFrame) return false;
    D3D11_MAPPED_SUBRESOURCE m;
    if (FAILED(impl_->context->Map(impl_->staging.Get(), 0, D3D11_MAP_READ, 0, &m))) return false;
    width = static_cast<int>(impl_->desc.Width);
    height = static_cast<int>(impl_->desc.Height);
    stride = width * 4;
    pixels.resize(static_cast<size_t>(stride) * height);
    for (int y = 0; y < height; ++y)
        std::memcpy(pixels.data() + static_cast<size_t>(y) * stride, static_cast<uint8_t*>(m.pData) + static_cast<size_t>(y) * m.RowPitch, stride);
    impl_->context->Unmap(impl_->staging.Get(), 0);
    switch (impl_->desc.Format) {
        case DXGI_FORMAT_R8G8B8A8_UNORM:
        case DXGI_FORMAT_R8G8B8A8_UNORM_SRGB:
        case DXGI_FORMAT_R8G8B8A8_TYPELESS:
            fmt = PixelFormat::RGBA;
            break;
        default:
            fmt = PixelFormat::BGRA;
    }
    return true;
}

}  // namespace phonesteam

#else  // Linux/macOS: o SteamVR não usa D3D11; o modo virtual display não é suportado aqui.

namespace phonesteam {
struct FrameCapture::Impl {};
FrameCapture::FrameCapture() = default;
FrameCapture::~FrameCapture() = default;
bool FrameCapture::Init() {
    Log("Captura de vídeo só é suportada no Windows (D3D11)");
    return false;
}
bool FrameCapture::Copy(uint64_t) { return false; }
bool FrameCapture::Read(std::vector<uint8_t>&, int&, int&, int&, PixelFormat&) { return false; }
}  // namespace phonesteam

#endif
