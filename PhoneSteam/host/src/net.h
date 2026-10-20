// Sockets multiplataforma mínimos (Winsock / POSIX).
#pragma once
#include <cstddef>
#include <cstdint>
#include <string>

#ifdef _WIN32
#include <winsock2.h>
#include <ws2tcpip.h>
using socket_t = SOCKET;
constexpr socket_t kInvalidSocket = INVALID_SOCKET;
#else
#include <arpa/inet.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <sys/socket.h>
#include <unistd.h>
using socket_t = int;
constexpr socket_t kInvalidSocket = -1;
#endif

namespace phonesteam::net {

bool Init();
void Shutdown();
void Close(socket_t s);
socket_t ListenTcp(uint16_t port);
socket_t BindUdp(uint16_t port);
/** Espera até `timeoutMs` por dados/conexão. */
bool WaitReadable(socket_t s, int timeoutMs);
bool SendAll(socket_t s, const void* data, size_t len);
bool RecvAll(socket_t s, void* data, size_t len);
std::string PeerAddress(socket_t s);

}  // namespace phonesteam::net
