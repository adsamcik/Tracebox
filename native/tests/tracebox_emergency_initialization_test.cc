#include "tracebox/emergency.h"
#include "tracebox/emergency_initialization.h"

#include <algorithm>
#include <array>
#include <atomic>
#include <cassert>
#include <chrono>
#include <cstdint>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <string>
#include <thread>
#include <vector>

namespace {

constexpr size_t kRecordSize = TB_EMERGENCY_RECORD_SIZE;
tracebox::EmergencyInitializationGate g_initialization;

bool WriteBytes(const std::filesystem::path& path,
                const uint8_t* bytes,
                size_t size) {
  std::ofstream output(path, std::ios::binary | std::ios::trunc);
  output.write(reinterpret_cast<const char*>(bytes),
               static_cast<std::streamsize>(size));
  output.flush();
  return output.good();
}

std::array<uint8_t, kRecordSize> ReadSlot(const std::filesystem::path& path) {
  std::array<uint8_t, kRecordSize> bytes{};
  std::ifstream input(path, std::ios::binary);
  input.read(reinterpret_cast<char*>(bytes.data()),
             static_cast<std::streamsize>(bytes.size()));
  assert(input.gcount() == static_cast<std::streamsize>(bytes.size()));
  return bytes;
}

bool ResetSlot(const std::filesystem::path& path,
               std::atomic<uint32_t>* reset_count) {
  std::array<uint8_t, kRecordSize> empty{};
  reset_count->fetch_add(1, std::memory_order_relaxed);
  return WriteBytes(path, empty.data(), empty.size());
}

void AssertZeroSlot(const std::filesystem::path& path) {
  const auto bytes = ReadSlot(path);
  for (uint8_t byte : bytes) {
    assert(byte == 0);
  }
}

void WriteValidEmergencyRecord(const std::filesystem::path& path) {
  tb_emergency_record_v1 record;
  std::array<uint8_t, 32> process_id{};
  process_id.fill(0x5a);
  assert(tb_emergency_initialize_v1(&record, process_id.data(), 1, 0, 7, 6, 0,
                                    0, 0, 0, 2, 0, 1) == 0);
  assert(WriteBytes(path, record.bytes, sizeof(record.bytes)));
}

void AssertValidEmergencyRecord(const std::filesystem::path& path) {
  const auto bytes = ReadSlot(path);
  assert(std::equal(bytes.begin(), bytes.begin() + 8, "TBEMERG1"));
  assert(bytes[48] == 1);
  assert(bytes[120] == 1);
  assert(bytes[248] == 0x50);
  assert(bytes[255] == 0x54);
}

int RunChild(const std::filesystem::path& slot) {
  std::atomic<uint32_t> reset_count{0};
  const bool initialized = g_initialization.Initialize(
      slot.parent_path().string(), 2,
      [&] { return ResetSlot(slot, &reset_count); });
  assert(initialized);
  assert(reset_count.load(std::memory_order_relaxed) == 1);
  AssertZeroSlot(slot);
  return 0;
}

int RunChildProcess(const std::filesystem::path& executable,
                    const std::filesystem::path& slot) {
#if defined(_WIN32)
  return static_cast<int>(_spawnl(_P_WAIT, executable.string().c_str(),
                                  executable.string().c_str(),
                                  slot.string().c_str(), "--child", nullptr));
#else
  const std::string child_command =
      "\"" + executable.string() + "\" \"" + slot.string() + "\" --child";
  return std::system(child_command.c_str());
#endif
}

}  // namespace

int main(int argc, char* argv[]) {
  assert(argc >= 2);
  const std::filesystem::path slot = std::filesystem::absolute(argv[1]);
  if (argc == 3 && std::string(argv[2]) == "--child") {
    return RunChild(slot);
  }

  std::filesystem::create_directories(slot.parent_path());
  std::filesystem::remove(slot);

  std::atomic<uint32_t> reset_count{0};
  assert(g_initialization.Initialize(slot.parent_path().string(), 2, [&] {
    return ResetSlot(slot, &reset_count);
  }));
  assert(reset_count.load(std::memory_order_relaxed) == 1);
  AssertZeroSlot(slot);

  std::atomic<bool> invoke_second_initialization{false};
  std::atomic<bool> second_initialization_started{false};
  std::atomic<bool> second_initialization_returned{false};
  std::thread redundant_initializer([&] {
    while (!invoke_second_initialization.load(std::memory_order_acquire)) {
      std::this_thread::yield();
    }
    second_initialization_started.store(true, std::memory_order_release);
    assert(g_initialization.Initialize(slot.parent_path().string(), 2, [&] {
      std::this_thread::sleep_for(std::chrono::milliseconds(20));
      return ResetSlot(slot, &reset_count);
    }));
    second_initialization_returned.store(true, std::memory_order_release);
  });

  invoke_second_initialization.store(true, std::memory_order_release);
  while (!second_initialization_started.load(std::memory_order_acquire)) {
    std::this_thread::yield();
  }
  WriteValidEmergencyRecord(slot);
  redundant_initializer.join();
  assert(second_initialization_returned.load(std::memory_order_acquire));
  assert(reset_count.load(std::memory_order_relaxed) == 1);
  AssertValidEmergencyRecord(slot);

  std::vector<std::thread> redundant_callers;
  for (int index = 0; index < 8; ++index) {
    redundant_callers.emplace_back([&] {
      assert(g_initialization.Initialize(slot.parent_path().string(), 2, [&] {
        return ResetSlot(slot, &reset_count);
      }));
    });
  }
  for (std::thread& caller : redundant_callers) {
    caller.join();
  }
  assert(reset_count.load(std::memory_order_relaxed) == 1);
  AssertValidEmergencyRecord(slot);

  bool mismatched_initializer_ran = false;
  assert(!g_initialization.Initialize(slot.parent_path().string(), 3, [&] {
    mismatched_initializer_ran = true;
    return ResetSlot(slot, &reset_count);
  }));
  assert(!mismatched_initializer_ran);
  AssertValidEmergencyRecord(slot);

  const std::filesystem::path executable = std::filesystem::absolute(argv[0]);
  assert(RunChildProcess(executable, slot) == 0);
  AssertZeroSlot(slot);
  std::filesystem::remove(slot);
  return 0;
}
