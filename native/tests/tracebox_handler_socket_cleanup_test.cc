#include "tracebox/handler_socket_cleanup.h"

#ifdef NDEBUG
#undef NDEBUG
#endif
#include <cassert>

#if defined(__linux__) && !defined(__ANDROID__)
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#include <cerrno>
#include <cstdlib>
#include <string>
#endif

namespace {

void TestCanonicalOwnedPathValidation() {
  assert(tracebox::IsCanonicalHandlerSocketPathV1(
      "/data/user/0/dev.tracker/no_backup/tracebox/native-handler/"
      "tracebox-handler.sock"));
  assert(!tracebox::IsCanonicalHandlerSocketPathV1(""));
  assert(!tracebox::IsCanonicalHandlerSocketPathV1(
      "data/user/0/dev.tracker/no_backup/tracebox/native-handler/"
      "tracebox-handler.sock"));
  assert(!tracebox::IsCanonicalHandlerSocketPathV1(
      "/data/user/0/dev.tracker/no_backup/tracebox/native-handler/"
      "different.sock"));
  assert(!tracebox::IsCanonicalHandlerSocketPathV1(
      "/data/user/0/dev.tracker/no_backup/../tracebox/native-handler/"
      "tracebox-handler.sock"));
  assert(!tracebox::IsCanonicalHandlerSocketPathV1(
      "/data/user/0/dev.tracker/no_backup//tracebox/native-handler/"
      "tracebox-handler.sock"));
  assert(!tracebox::IsCanonicalHandlerSocketPathV1(
      "/data/user/0/dev.tracker/no_backup\\tracebox/native-handler/"
      "tracebox-handler.sock"));
}

void TestOnlyConclusiveDeadOrAbsentProbePermitsCleanup() {
  using tracebox::HandlerSocketProbeV1;
  assert(tracebox::HandlerSocketProbePermitsCleanupV1(
      HandlerSocketProbeV1::kConnectionRefused));
  assert(tracebox::HandlerSocketProbePermitsCleanupV1(
      HandlerSocketProbeV1::kPathAbsent));
  assert(!tracebox::HandlerSocketProbePermitsCleanupV1(
      HandlerSocketProbeV1::kListenerAlive));
  assert(!tracebox::HandlerSocketProbePermitsCleanupV1(
      HandlerSocketProbeV1::kAmbiguous));
}

#if defined(__linux__) && !defined(__ANDROID__)
// Android verifies this path in the app process. The adb shell domain has
// different permissions for pathname sockets reached through /proc/self/fd.
void TestLongPathSocketUsesTheSameFileFromSeparateDescriptors() {
  const char* tmp = std::getenv("TMPDIR");
  std::string root = std::string(tmp && *tmp ? tmp : "/tmp") + "/tracebox-XXXXXX";
  assert(mkdtemp(root.data()) != nullptr);
  const std::string app_data = root + "/" + std::string(100, 'a');
  const std::string store = app_data + "/tracebox";
  const std::string parent = store + "/native-handler";
  const std::string pathname = parent + "/" + tracebox::kHandlerSocketFileNameV1;
  assert(tracebox::IsCanonicalHandlerSocketPathV1(pathname));
  assert(pathname.size() >= sizeof(sockaddr_un::sun_path));
  assert(mkdir(app_data.c_str(), 0700) == 0);
  assert(mkdir(store.c_str(), 0700) == 0);
  assert(mkdir(parent.c_str(), 0700) == 0);

  const int server_dir = open(parent.c_str(), O_RDONLY | O_DIRECTORY);
  const int client_dir = open(parent.c_str(), O_RDONLY | O_DIRECTORY);
  assert(server_dir >= 0 && client_dir >= 0 && server_dir != client_dir);
  const sockaddr_un server =
      tracebox::HandlerSocketAddressForDirectoryV1(server_dir);
  const sockaddr_un client =
      tracebox::HandlerSocketAddressForDirectoryV1(client_dir);
  const int listener = socket(AF_UNIX, SOCK_SEQPACKET, 0);
  const int peer = socket(AF_UNIX, SOCK_SEQPACKET, 0);
  assert(listener >= 0 && peer >= 0);
  assert(bind(listener, reinterpret_cast<const sockaddr*>(&server),
              sizeof(server)) == 0);
  assert(listen(listener, 1) == 0);
  struct stat status {};
  assert(lstat(pathname.c_str(), &status) == 0 && S_ISSOCK(status.st_mode));
  assert(connect(peer, reinterpret_cast<const sockaddr*>(&client),
                 sizeof(client)) == 0);
  const int accepted = accept(listener, nullptr, nullptr);
  assert(accepted >= 0);
  assert(close(accepted) == 0 && close(peer) == 0 && close(listener) == 0);

  const int probe = socket(AF_UNIX, SOCK_SEQPACKET, 0);
  assert(probe >= 0);
  assert(connect(probe, reinterpret_cast<const sockaddr*>(&server),
                 sizeof(server)) == -1);
  assert(errno == ECONNREFUSED);
  assert(close(probe) == 0 && close(client_dir) == 0 && close(server_dir) == 0);
  assert(unlink(pathname.c_str()) == 0);
  assert(rmdir(parent.c_str()) == 0 && rmdir(store.c_str()) == 0 &&
         rmdir(app_data.c_str()) == 0 && rmdir(root.c_str()) == 0);
}
#endif

}  // namespace

int main() {
  TestCanonicalOwnedPathValidation();
  TestOnlyConclusiveDeadOrAbsentProbePermitsCleanup();
#if defined(__linux__) && !defined(__ANDROID__)
  TestLongPathSocketUsesTheSameFileFromSeparateDescriptors();
#endif
  return 0;
}
