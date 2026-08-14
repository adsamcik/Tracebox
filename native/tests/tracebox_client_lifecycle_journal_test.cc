#include "tracebox/client_lifecycle_journal.h"

#include <array>
#include <cassert>
#include <cstdint>
#include <string>

int main() {
  assert(static_cast<uint16_t>(
             tracebox::ClientLifecycleStateV1::kRegistered) == 1);
  assert(static_cast<uint16_t>(
             tracebox::ClientLifecycleStateV1::kConsumed) == 2);
  assert(static_cast<uint16_t>(
             tracebox::ClientLifecycleStateV1::kDead) == 3);
  assert(static_cast<uint16_t>(
             tracebox::ClientLifecycleStateV1::kProtocolError) == 4);
  assert(static_cast<uint16_t>(
             tracebox::ClientLifecycleStateV1::kHandoffFailed) == 5);
  assert(tracebox::ClientLifecycleReceiveFailureStateV1(true, true) ==
         tracebox::ClientLifecycleStateV1::kDead);
  assert(tracebox::ClientLifecycleReceiveFailureStateV1(true, false) ==
         tracebox::ClientLifecycleStateV1::kProtocolError);
  assert(tracebox::ClientLifecycleReceiveFailureStateV1(false, true) ==
         tracebox::ClientLifecycleStateV1::kProtocolError);
  assert(tracebox::ClientLifecycleReceiveFailureStateV1(false, false) ==
         tracebox::ClientLifecycleStateV1::kProtocolError);

  std::array<uint8_t, tracebox::kClientLifecycleRawIdentityBytesV1> first{};
  std::array<uint8_t, tracebox::kClientLifecycleRawIdentityBytesV1> second{};
  first[0] = 0x01;
  first[31] = 0xfe;
  second = first;
  second[31] = 0xff;

  const std::string first_name =
      tracebox::FormatClientLifecycleJournalNameV1(7, first.data());
  const std::string second_name =
      tracebox::FormatClientLifecycleJournalNameV1(7, second.data());
  const std::string other_role_name =
      tracebox::FormatClientLifecycleJournalNameV1(8, first.data());
  assert(first_name ==
         "client-r7-"
         "01000000000000000000000000000000000000000000000000000000000000fe"
         ".tbclient");
  assert(first_name ==
         tracebox::FormatClientLifecycleJournalNameV1(7, first.data()));
  assert(first_name != second_name);
  assert(first_name != other_role_name);
  assert(tracebox::kClientLifecycleJournalBytesV1 == 384);
  assert(first_name.size() == 83);

  tracebox::ClientLifecycleJournalNameV1 parsed;
  assert(tracebox::ParseClientLifecycleJournalNameV1(first_name, &parsed));
  assert(parsed.process_role == 7);
  assert(parsed.raw_artifact_id == first);
  const std::string maximum_role_name =
      tracebox::FormatClientLifecycleJournalNameV1(UINT32_MAX, first.data());
  assert(tracebox::ParseClientLifecycleJournalNameV1(
      maximum_role_name, &parsed));
  assert(parsed.process_role == UINT32_MAX);
  assert(parsed.raw_artifact_id == first);

  std::array<uint8_t, tracebox::kClientLifecycleRawIdentityBytesV1> zero{};
  assert(tracebox::FormatClientLifecycleJournalNameV1(0, first.data()).empty());
  assert(tracebox::FormatClientLifecycleJournalNameV1(7, zero.data()).empty());
  assert(!tracebox::ParseClientLifecycleJournalNameV1(
      "client-r07-" + first_name.substr(10), &parsed));
  assert(!tracebox::ParseClientLifecycleJournalNameV1(
      "client-r4294967296-" + first_name.substr(10), &parsed));
  std::string uppercase = first_name;
  uppercase[uppercase.size() - tracebox::kClientLifecycleJournalSuffixV1.size() -
            1] = 'F';
  assert(!tracebox::ParseClientLifecycleJournalNameV1(uppercase, &parsed));
  assert(!tracebox::ParseClientLifecycleJournalNameV1(
      first_name.substr(0, first_name.size() - 1), &parsed));
  assert(!tracebox::ParseClientLifecycleJournalNameV1(
      first_name + ".extra", &parsed));
  assert(!tracebox::ParseClientLifecycleJournalNameV1(
      "client-r7-" + std::string(64, '0') + ".tbclient", &parsed));
  assert(!tracebox::ParseClientLifecycleJournalNameV1(first_name, nullptr));
  return 0;
}
