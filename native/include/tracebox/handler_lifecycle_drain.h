#ifndef TRACEBOX_HANDLER_LIFECYCLE_DRAIN_H_
#define TRACEBOX_HANDLER_LIFECYCLE_DRAIN_H_

#include <chrono>
#include <condition_variable>
#include <cstddef>
#include <mutex>

namespace tracebox {

// Accounts for every lifecycle watcher admitted by the handler. Shutdown first
// closes admission, wakes the watcher sockets, and then waits on this barrier.
// A watcher must call CompleteTerminal() only after its terminal journal and
// any raw handoff are durable, so a successful wait is a real storage boundary.
class HandlerLifecycleDrainBarrierV1 {
 public:
  explicit HandlerLifecycleDrainBarrierV1(std::size_t maximum_watchers)
      : maximum_watchers_(maximum_watchers) {}

  HandlerLifecycleDrainBarrierV1(
      const HandlerLifecycleDrainBarrierV1&) = delete;
  HandlerLifecycleDrainBarrierV1& operator=(
      const HandlerLifecycleDrainBarrierV1&) = delete;

  bool OpenAdmission() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (maximum_watchers_ == 0 || active_watchers_ != 0 ||
        terminal_failure_) {
      return false;
    }
    admission_open_ = true;
    return true;
  }

  bool TryAdmit() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!admission_open_ || terminal_failure_ ||
        active_watchers_ >= maximum_watchers_) {
      return false;
    }
    ++active_watchers_;
    return true;
  }

  // Rolls back an admission whose watcher thread was never started.
  bool CancelAdmission(bool storage_clean = true) {
    return ReleaseAdmission(storage_clean);
  }

  // Marks the only terminal boundary that shutdown is allowed to observe.
  bool CompleteTerminal(bool storage_durable = true) {
    return ReleaseAdmission(storage_durable);
  }

  void BeginDrain() {
    std::lock_guard<std::mutex> lock(mutex_);
    admission_open_ = false;
    if (active_watchers_ == 0) {
      drained_.notify_all();
    }
  }

  bool WaitFor(std::chrono::milliseconds timeout) {
    std::unique_lock<std::mutex> lock(mutex_);
    if (!drained_.wait_for(
            lock, timeout, [this] { return active_watchers_ == 0; })) {
      return false;
    }
    return !terminal_failure_;
  }

  bool admission_open() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return admission_open_;
  }

  std::size_t active_watchers() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return active_watchers_;
  }

 private:
  bool ReleaseAdmission(bool storage_durable) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (active_watchers_ == 0) {
      return false;
    }
    terminal_failure_ = terminal_failure_ || !storage_durable;
    --active_watchers_;
    if (active_watchers_ == 0) {
      drained_.notify_all();
    }
    return true;
  }

  const std::size_t maximum_watchers_;
  mutable std::mutex mutex_;
  std::condition_variable drained_;
  bool admission_open_ = false;
  bool terminal_failure_ = false;
  std::size_t active_watchers_ = 0;
};

}  // namespace tracebox

#endif  // TRACEBOX_HANDLER_LIFECYCLE_DRAIN_H_
