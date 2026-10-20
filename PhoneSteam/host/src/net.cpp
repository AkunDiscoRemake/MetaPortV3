#include "net.h"

#ifndef _WIN32
#include <poll.h>
#endif

namespace phonesteam::net {

bool Init() {
#ifdef _WIN32
    WSADATA wsa;
    return WSAStartup(MAKEWORD(2, 2), &wsa) == 0;
#else
    return true;
#endif
}

void Shutdown() {
#ifdef _WIN32
    WSACleanup();
#endif
}

void Close(socket_t s) {
    if (s == kInvalidSocket) return;
#ifdef _WIN32
    closesocket(s);
#else
    close(s);
#endif
}

static void SetReuse(socket_t s) {
    int one = 1;
    setsockopt(s, SOL_SOCKET, SO_REUSEADDR, reinterpret_cast<const char*>(&one), sizeof(one));
}

socket_t ListenTcp(uint16_t port) {
    socket_t s = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (s == kInvalidSocket) return s;
    SetReuse(s);
    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = htonl(INADDR_ANY);
    addr.sin_port = htons(port);
    if (bind(s, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) != 0 || listen(s, 4) != 0) {
        Close(s);
        return kInvalidSocket;
    }
    return s;
}

socket_t BindUdp(uint16_t port) {
    socket_t s = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (s == kInvalidSocket) return s;
    SetReuse(s);
    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = htonl(INADDR_ANY);
    addr.sin_port = htons(port);
    if (bind(s, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) != 0) {
        Close(s);
        return kInvalidSocket;
    }
    return s;
}

bool WaitReadable(socket_t s, int timeoutMs) {
#ifdef _WIN32
    WSAPOLLFD p{s, POLLRDNORM, 0};
    return WSAPoll(&p, 1, timeoutMs) > 0;
#else
    pollfd p{s, POLLIN, 0};
    return poll(&p, 1, timeoutMs) > 0;
#endif
}

bool SendAll(socket_t s, const void* data, size_t len) {
    auto p = static_cast<const char*>(data);
    while (len > 0) {
#ifdef _WIN32
        int n = send(s, p, static_cast<int>(len), 0);
#else
        ssize_t n = send(s, p, len, MSG_NOSIGNAL);
#endif
        if (n <= 0) return false;
        p += n;
        len -= static_cast<size_t>(n);
    }
    return true;
}

bool RecvAll(socket_t s, void* data, size_t len) {
    auto p = static_cast<char*>(data);
    while (len > 0) {
#ifdef _WIN32
        int n = recv(s, p, static_cast<int>(len), 0);
#else
        ssize_t n = recv(s, p, len, 0);
#endif
        if (n <= 0) return false;
        p += n;
        len -= static_cast<size_t>(n);
    }
    return true;
}

std::string PeerAddress(socket_t s) {
    sockaddr_in addr{};
    socklen_t len = sizeof(addr);
    if (getpeername(s, reinterpret_cast<sockaddr*>(&addr), &len) != 0) return "?";
    char buf[64] = {};
    inet_ntop(AF_INET, &addr.sin_addr, buf, sizeof(buf));
    return buf;
}

}  // namespace phonesteam::net
