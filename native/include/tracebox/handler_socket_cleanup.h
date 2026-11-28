#ifndef TRACEBOX_HANDLER_SOCKET_CLEANUP_H_
#define TRACEBOX_HANDLER_SOCKET_CLEANUP_H_

#include <string_view>

#if defined(__linux__)
#include <sys/socket.h>
#include <sys/un.h>

#include <cstdio>
#endif

namespace tracebox {

constexpr char kHandlerSocketFileNameV1[] = "tracebox-handler.sock";
constexpr std::string_view kHandlerSocketSuffixV1 =
    "/tracebox/native-handler/tracebox-handler.sock";

#if defined(__linux__)
inline sockaddr_un HandlerSocketAddressForDirectoryV1(int directory_fd) {
  // Unix socket addresses have a shorter pathname limit than the filesystem.
  // Keep the socket file in the opened private directory and shorten
  // only the pathname passed to bind() and connect().
  sockaddr_un address{};
  address.sun_family = AF_UNIX;
  std::snprintf(address.sun_path, sizeof(address.sun_path),
                "/proc/self/fd/%d/%s", directory_fd, kHandlerSocketFileNameV1);
  return address;
}
#endif

enum class HandlerSocketProbeV1 {
  kListenerAlive,
  kConnectionRefused,
  kPathAbsent,
  kAmbiguous,
};

inline bool IsCanonicalHandlerSocketPathV1(std::string_view path) {
  if (path.empty() || path.front() != '/' ||
      path.size() < kHandlerSocketSuffixV1.size() ||
      path.substr(path.size() - kHandlerSocketSuffixV1.size()) !=
          kHandlerSocketSuffixV1 ||
      path.find('\\') != std::string_view::npos) {
    return false;
  }
  size_t component_start = 1;
  while (component_start <= path.size()) {
    const size_t separator = path.find('/', component_start);
    const size_t component_end =
        separator == std::string_view::npos ? path.size() : separator;
    const std::string_view component =
        path.substr(component_start, component_end - component_start);
    if (component.empty() || component == "." || component == "..") {
      return false;
    }
    if (separator == std::string_view::npos) {
      break;
    }
    component_start = separator + 1;
  }
  return true;
}

inline bool HandlerSocketProbePermitsCleanupV1(
    HandlerSocketProbeV1 probe) {
  return probe == HandlerSocketProbeV1::kConnectionRefused ||
         probe == HandlerSocketProbeV1::kPathAbsent;
}

}  // namespace tracebox

#endif  // TRACEBOX_HANDLER_SOCKET_CLEANUP_H_
