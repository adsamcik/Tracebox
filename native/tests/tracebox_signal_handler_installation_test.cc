#include "tracebox/signal_stack.h"

#include <atomic>
#include <cstdlib>
#include <mutex>
#include <thread>
#include <vector>

namespace {

tracebox::ProcessSignalHandlerInstallationV1 g_handler_installation;
std::mutex g_handler_mutex;
std::atomic<int> g_install_calls{0};
std::atomic<int> g_restore_calls{0};
std::atomic<int> g_thread_registrations{0};
std::atomic<int> g_thread_releases{0};
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

bool ReleaseThreadRegistration(void* opaque) noexcept {
  delete static_cast<int*>(opaque);
  g_thread_releases.fetch_add(1, std::memory_order_acq_rel);
  return true;
}

void RegisterWorkerAndObserveProcessHandler() {
  auto* resource =
      new int(g_thread_registrations.fetch_add(1, std::memory_order_acq_rel));
  Check(g_thread_registration.Adopt(resource, ReleaseThreadRegistration));
  {
    std::lock_guard<std::mutex> lock(g_handler_mutex);
    Check(g_handler_installation.EnsureInstalled([] {
      g_install_calls.fetch_add(1, std::memory_order_acq_rel);
      return true;
    }));
  }
  Check(g_handler_installation.installed());
  // Worker cleanup must not restore the process-wide handlers.
}

}  // namespace

int main() {
  constexpr int kWorkerCount = 8;
  std::vector<std::thread> workers;
  workers.reserve(kWorkerCount);
  for (int index = 0; index < kWorkerCount; ++index) {
    workers.emplace_back(RegisterWorkerAndObserveProcessHandler);
  }
  for (std::thread& worker : workers) {
    worker.join();
  }

  Check(g_thread_registrations.load(std::memory_order_acquire) ==
        kWorkerCount);
  Check(g_thread_releases.load(std::memory_order_acquire) == kWorkerCount);
  Check(g_install_calls.load(std::memory_order_acquire) == 1);
  Check(g_restore_calls.load(std::memory_order_acquire) == 0);
  Check(g_handler_installation.installed());

  {
    std::lock_guard<std::mutex> lock(g_handler_mutex);
    Check(g_handler_installation.Restore([] {
      g_restore_calls.fetch_add(1, std::memory_order_acq_rel);
      return true;
    }));
    Check(g_handler_installation.Restore([] {
      g_restore_calls.fetch_add(1, std::memory_order_acq_rel);
      return true;
    }));
  }
  Check(!g_handler_installation.installed());
  Check(g_restore_calls.load(std::memory_order_acquire) == 1);
  return 0;
}
