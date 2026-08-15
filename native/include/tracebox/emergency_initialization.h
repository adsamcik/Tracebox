#ifndef TRACEBOX_EMERGENCY_INITIALIZATION_H_
#define TRACEBOX_EMERGENCY_INITIALIZATION_H_

#include <algorithm>
#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <string_view>
#include <thread>
#include <utility>

#if defined(_WIN32)
#include <process.h>
#else
#include <unistd.h>
#endif

namespace tracebox {

inline uint64_t CurrentProcessIdentity() {
#if defined(_WIN32)
  return static_cast<uint64_t>(_getpid());
#else
  return static_cast<uint64_t>(getpid());
#endif
}

class EmergencyInitializationGate {
 public:
  static constexpr size_t kMaximumDirectoryBytes = 4096;

  template <typename Initializer>
  bool Initialize(std::string_view directory,
                  uint32_t process_role,
                  Initializer&& initializer) {
    while (lock_.test_and_set(std::memory_order_acquire)) {
      std::this_thread::yield();
    }
    const uint64_t process_identity = CurrentProcessIdentity();
    if (initialized_ && process_identity_ == process_identity) {
      const bool matches =
          directory_size_ == directory.size() &&
          std::equal(directory.begin(), directory.end(), directory_.begin()) &&
          process_role_ == process_role;
      lock_.clear(std::memory_order_release);
      return matches;
    }
    if (directory.size() > directory_.size() ||
        !std::forward<Initializer>(initializer)()) {
      lock_.clear(std::memory_order_release);
      return false;
    }
    std::copy(directory.begin(), directory.end(), directory_.begin());
    directory_size_ = directory.size();
    process_role_ = process_role;
    process_identity_ = process_identity;
    initialized_ = true;
    lock_.clear(std::memory_order_release);
    return true;
  }

 private:
  std::atomic_flag lock_ = ATOMIC_FLAG_INIT;
  std::array<char, kMaximumDirectoryBytes> directory_{};
  size_t directory_size_ = 0;
  uint32_t process_role_ = 0;
  uint64_t process_identity_ = 0;
  bool initialized_ = false;
};

}  // namespace tracebox

#endif  // TRACEBOX_EMERGENCY_INITIALIZATION_H_
