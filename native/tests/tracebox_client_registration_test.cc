#include "tracebox/client_registration.h"

#include <cassert>
#include <cstdint>

int main() {
  using tracebox::ClientConnectionModeV2;
  using tracebox::ClientRegistrationRequestV2;

  assert(tracebox::IsClientRegistrationRequestV2(1));
  assert(tracebox::IsClientRegistrationRequestV2(2));
  assert(tracebox::IsClientRegistrationRequestV2(3));
  assert(!tracebox::IsClientRegistrationRequestV2(0));
  assert(!tracebox::IsClientRegistrationRequestV2(4));

  assert(tracebox::ClientRegistrationRawIdentityIsValidV2(
      ClientRegistrationRequestV2::kCrashpadRequired, false));
  assert(!tracebox::ClientRegistrationRawIdentityIsValidV2(
      ClientRegistrationRequestV2::kCrashpadRequired, true));
  assert(tracebox::ClientRegistrationRawIdentityIsValidV2(
      ClientRegistrationRequestV2::kEmergencyRustOnly, true));
  assert(!tracebox::ClientRegistrationRawIdentityIsValidV2(
      ClientRegistrationRequestV2::kEmergencyRustOnly, false));
  assert(tracebox::ClientRegistrationRawIdentityIsValidV2(
      ClientRegistrationRequestV2::kCrashpadOrEmergencyRust, false));

  assert(tracebox::ClientRegistrationRoleIsAvailableV2(1, 2, false));
  assert(!tracebox::ClientRegistrationRoleIsAvailableV2(2, 2, false));
  assert(!tracebox::ClientRegistrationRoleIsAvailableV2(3, 2, true));

  assert(tracebox::DecideClientConnectionModeV2(
             ClientRegistrationRequestV2::kCrashpadRequired,
             true,
             true,
             true) == ClientConnectionModeV2::kCrashpad);
  assert(tracebox::DecideClientConnectionModeV2(
             ClientRegistrationRequestV2::kCrashpadRequired,
             true,
             true,
             false) == ClientConnectionModeV2::kRejected);
  assert(tracebox::DecideClientConnectionModeV2(
             ClientRegistrationRequestV2::kEmergencyRustOnly,
             true,
             true,
             false) == ClientConnectionModeV2::kEmergencyRust);
  assert(tracebox::DecideClientConnectionModeV2(
             ClientRegistrationRequestV2::kEmergencyRustOnly,
             true,
             false,
             true) == ClientConnectionModeV2::kRejected);
  assert(tracebox::DecideClientConnectionModeV2(
             ClientRegistrationRequestV2::kCrashpadOrEmergencyRust,
             true,
             true,
             true) == ClientConnectionModeV2::kCrashpad);
  assert(tracebox::DecideClientConnectionModeV2(
             ClientRegistrationRequestV2::kCrashpadOrEmergencyRust,
             true,
             true,
             false) == ClientConnectionModeV2::kEmergencyRust);
  assert(tracebox::DecideClientConnectionModeV2(
             ClientRegistrationRequestV2::kCrashpadOrEmergencyRust,
             false,
             true,
             true) == ClientConnectionModeV2::kEmergencyRust);
  assert(tracebox::DecideClientConnectionModeV2(
             ClientRegistrationRequestV2::kCrashpadOrEmergencyRust,
             false,
             false,
             true) == ClientConnectionModeV2::kRejected);
  return 0;
}
