#ifndef TRACEBOX_CLIENT_REGISTRATION_H_
#define TRACEBOX_CLIENT_REGISTRATION_H_

#include <cstdint>

namespace tracebox {

// Registration request values are part of the local handler protocol. A caller
// must opt into fallback explicitly; a Crashpad-required caller is never
// silently downgraded.
enum class ClientRegistrationRequestV2 : uint32_t {
  kCrashpadRequired = 1,
  kEmergencyRustOnly = 2,
  kCrashpadOrEmergencyRust = 3,
};

// Values returned through JNI. Zero is reserved for every rejected, malformed,
// timed-out, or otherwise ambiguous registration.
enum class ClientConnectionModeV2 : int32_t {
  kRejected = 0,
  kCrashpad = 1,
  kEmergencyRust = 2,
};

constexpr bool IsClientRegistrationRequestV2(uint32_t value) {
  return value >=
             static_cast<uint32_t>(
                 ClientRegistrationRequestV2::kCrashpadRequired) &&
         value <=
             static_cast<uint32_t>(
                 ClientRegistrationRequestV2::kCrashpadOrEmergencyRust);
}

constexpr bool ClientRegistrationRawIdentityIsValidV2(
    ClientRegistrationRequestV2 request,
    bool raw_identity_is_zero) {
  return request == ClientRegistrationRequestV2::kEmergencyRustOnly
             ? raw_identity_is_zero
             : !raw_identity_is_zero;
}

// Handler role identifiers and every currently-live app participant role are
// exclusive because emergency and Rust slot paths are role-derived.
constexpr bool ClientRegistrationRoleIsAvailableV2(
    uint32_t requested_role,
    uint32_t handler_role,
    bool requested_role_is_live) {
  return requested_role != handler_role && !requested_role_is_live;
}

// Pure policy/lease decision used by the Android handler and host tests. A
// required Crashpad request fails closed when the one raw lease is unavailable;
// only an explicitly fallback-capable request may receive emergency/Rust mode.
constexpr ClientConnectionModeV2 DecideClientConnectionModeV2(
    ClientRegistrationRequestV2 request,
    bool crashpad_policy_permitted,
    bool emergency_or_rust_policy_permitted,
    bool crashpad_lease_available) {
  if (request != ClientRegistrationRequestV2::kEmergencyRustOnly &&
      crashpad_policy_permitted && crashpad_lease_available) {
    return ClientConnectionModeV2::kCrashpad;
  }
  if (request != ClientRegistrationRequestV2::kCrashpadRequired &&
      emergency_or_rust_policy_permitted) {
    return ClientConnectionModeV2::kEmergencyRust;
  }
  return ClientConnectionModeV2::kRejected;
}

}  // namespace tracebox

#endif  // TRACEBOX_CLIENT_REGISTRATION_H_
