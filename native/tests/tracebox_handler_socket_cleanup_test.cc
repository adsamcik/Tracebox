#include "tracebox/handler_socket_cleanup.h"

#include <cassert>

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

}  // namespace

int main() {
  TestCanonicalOwnedPathValidation();
  TestOnlyConclusiveDeadOrAbsentProbePermitsCleanup();
  return 0;
}
