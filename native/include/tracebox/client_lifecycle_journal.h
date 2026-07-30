#ifndef TRACEBOX_CLIENT_LIFECYCLE_JOURNAL_H_
#define TRACEBOX_CLIENT_LIFECYCLE_JOURNAL_H_

#include <array>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <string>
#include <string_view>

namespace tracebox {

constexpr std::size_t kClientLifecycleRawIdentityBytesV1 = 32;
constexpr std::size_t kClientLifecycleJournalBytesV1 = 384;
constexpr std::string_view kClientLifecycleJournalPrefixV1 = "client-r";
constexpr std::string_view kClientLifecycleJournalSuffixV1 = ".tbclient";

struct ClientLifecycleJournalNameV1 {
  uint32_t process_role = 0;
  std::array<uint8_t, kClientLifecycleRawIdentityBytesV1> raw_artifact_id{};
};

inline std::string FormatClientLifecycleJournalNameV1(
    uint32_t process_role,
    const uint8_t raw_artifact_id[kClientLifecycleRawIdentityBytesV1]) {
  if (process_role == 0 || raw_artifact_id == nullptr) {
    return {};
  }
  bool any_identity_byte = false;
  for (std::size_t index = 0;
       index < kClientLifecycleRawIdentityBytesV1;
       ++index) {
    any_identity_byte = any_identity_byte || raw_artifact_id[index] != 0;
  }
  if (!any_identity_byte) {
    return {};
  }

  constexpr char kHex[] = "0123456789abcdef";
  std::string name(kClientLifecycleJournalPrefixV1);
  name += std::to_string(process_role);
  name.push_back('-');
  for (std::size_t index = 0;
       index < kClientLifecycleRawIdentityBytesV1;
       ++index) {
    name.push_back(kHex[raw_artifact_id[index] >> 4]);
    name.push_back(kHex[raw_artifact_id[index] & 0x0f]);
  }
  name += kClientLifecycleJournalSuffixV1;
  return name;
}

inline bool ParseClientLifecycleJournalNameV1(
    std::string_view name,
    ClientLifecycleJournalNameV1* parsed) {
  const bool has_prefix =
      name.size() >= kClientLifecycleJournalPrefixV1.size() &&
      name.substr(0, kClientLifecycleJournalPrefixV1.size()) ==
          kClientLifecycleJournalPrefixV1;
  const bool has_suffix =
      name.size() >= kClientLifecycleJournalSuffixV1.size() &&
      name.substr(name.size() - kClientLifecycleJournalSuffixV1.size()) ==
          kClientLifecycleJournalSuffixV1;
  if (parsed == nullptr ||
      !has_prefix ||
      !has_suffix) {
    return false;
  }
  const std::size_t role_start = kClientLifecycleJournalPrefixV1.size();
  const std::size_t role_end = name.find('-', role_start);
  if (role_end == std::string_view::npos ||
      role_end == role_start ||
      role_end - role_start > 10 ||
      name[role_start] == '0') {
    return false;
  }

  uint64_t role = 0;
  for (std::size_t index = role_start; index < role_end; ++index) {
    const char value = name[index];
    if (value < '0' || value > '9') {
      return false;
    }
    role = role * 10 + static_cast<uint64_t>(value - '0');
    if (role > std::numeric_limits<uint32_t>::max()) {
      return false;
    }
  }

  const std::size_t identity_start = role_end + 1;
  const std::size_t identity_characters =
      kClientLifecycleRawIdentityBytesV1 * 2;
  if (identity_start + identity_characters +
          kClientLifecycleJournalSuffixV1.size() !=
      name.size()) {
    return false;
  }

  ClientLifecycleJournalNameV1 candidate;
  candidate.process_role = static_cast<uint32_t>(role);
  bool any_identity_byte = false;
  for (std::size_t index = 0;
       index < kClientLifecycleRawIdentityBytesV1;
       ++index) {
    const auto nibble = [](char value) -> int {
      if (value >= '0' && value <= '9') {
        return value - '0';
      }
      if (value >= 'a' && value <= 'f') {
        return value - 'a' + 10;
      }
      return -1;
    };
    const int high = nibble(name[identity_start + index * 2]);
    const int low = nibble(name[identity_start + index * 2 + 1]);
    if (high < 0 || low < 0) {
      return false;
    }
    candidate.raw_artifact_id[index] =
        static_cast<uint8_t>((high << 4) | low);
    any_identity_byte =
        any_identity_byte || candidate.raw_artifact_id[index] != 0;
  }
  if (candidate.process_role == 0 || !any_identity_byte) {
    return false;
  }
  *parsed = candidate;
  return true;
}

}  // namespace tracebox

#endif  // TRACEBOX_CLIENT_LIFECYCLE_JOURNAL_H_
