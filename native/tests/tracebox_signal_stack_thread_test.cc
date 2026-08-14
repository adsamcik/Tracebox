#include "tracebox/signal_stack.h"

#include <atomic>
#include <cstdlib>
#include <mutex>
#include <set>
#include <thread>
#include <vector>

namespace {

struct FakeThreadStack {
  int identifier;
  std::thread::id owner;
};

struct RetryRelease {
  int calls = 0;
};

std::atomic<int> g_next_identifier{1};
std::atomic<int> g_releases{0};
std::atomic<int> g_ready_workers{0};
std::atomic<bool> g_allow_worker_exit{false};
std::atomic<bool> g_duplicate_release{false};
std::mutex g_observed_mutex;
std::set<int> g_observed_identifiers;
std::set<std::thread::id> g_observed_owners;
thread_local tracebox::CurrentThreadResourceRegistrationV1
    g_thread_registration;

[[noreturn]] void Fail() {
  std::abort();
}

void Check(bool condition) {
  if (!condition) {
    Fail();
  }
}

bool ReleaseFakeStack(void* opaque) noexcept {
  auto* stack = static_cast<FakeThreadStack*>(opaque);
  if (stack->owner != std::this_thread::get_id()) {
    g_duplicate_release.store(true, std::memory_order_release);
  }
  {
    std::lock_guard<std::mutex> lock(g_observed_mutex);
    if (g_observed_identifiers.erase(stack->identifier) != 1) {
      g_duplicate_release.store(true, std::memory_order_release);
    }
  }
  g_releases.fetch_add(1, std::memory_order_acq_rel);
  delete stack;
  return true;
}

bool FailReleaseOnce(void* opaque) noexcept {
  auto* retry = static_cast<RetryRelease*>(opaque);
  ++retry->calls;
  if (retry->calls == 1) {
    return false;
  }
  delete retry;
  return true;
}

void RegisterOneThread() {
  auto* stack = new FakeThreadStack{
      g_next_identifier.fetch_add(1, std::memory_order_acq_rel),
      std::this_thread::get_id()};
  {
    std::lock_guard<std::mutex> lock(g_observed_mutex);
    Check(g_observed_identifiers.insert(stack->identifier).second);
    Check(g_observed_owners.insert(stack->owner).second);
  }
  Check(g_thread_registration.Adopt(stack, ReleaseFakeStack));
  Check(g_thread_registration.active());
  Check(g_thread_registration.resource() == stack);
  g_ready_workers.fetch_add(1, std::memory_order_acq_rel);
  while (!g_allow_worker_exit.load(std::memory_order_acquire)) {
    std::this_thread::yield();
  }
  // Deliberately rely on the thread_local destructor for cleanup.
}

}  // namespace

int main() {
  constexpr int kWorkerCount = 12;
  std::vector<std::thread> workers;
  workers.reserve(kWorkerCount);
  for (int index = 0; index < kWorkerCount; ++index) {
    workers.emplace_back(RegisterOneThread);
  }
  while (g_ready_workers.load(std::memory_order_acquire) != kWorkerCount) {
    std::this_thread::yield();
  }
  g_allow_worker_exit.store(true, std::memory_order_release);
  for (std::thread& worker : workers) {
    worker.join();
  }

  Check(g_releases.load(std::memory_order_acquire) == kWorkerCount);
  Check(!g_duplicate_release.load(std::memory_order_acquire));
  {
    std::lock_guard<std::mutex> lock(g_observed_mutex);
    Check(g_observed_identifiers.empty());
    Check(g_observed_owners.size() == kWorkerCount);
  }

  auto* main_stack = new FakeThreadStack{
      g_next_identifier.fetch_add(1, std::memory_order_acq_rel),
      std::this_thread::get_id()};
  {
    std::lock_guard<std::mutex> lock(g_observed_mutex);
    Check(g_observed_identifiers.insert(main_stack->identifier).second);
  }
  Check(g_thread_registration.Adopt(main_stack, ReleaseFakeStack));
  Check(g_thread_registration.Reset());
  Check(g_thread_registration.Reset());
  Check(g_releases.load(std::memory_order_acquire) == kWorkerCount + 1);
  Check(!g_duplicate_release.load(std::memory_order_acquire));

  auto* retry = new RetryRelease();
  Check(g_thread_registration.Adopt(retry, FailReleaseOnce));
  Check(!g_thread_registration.Reset());
  Check(g_thread_registration.active());
  Check(g_thread_registration.resource() == retry);
  Check(g_thread_registration.Reset());
  Check(!g_thread_registration.active());
  return 0;
}
