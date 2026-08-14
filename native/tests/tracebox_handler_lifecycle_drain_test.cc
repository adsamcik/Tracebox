#include "tracebox/handler_lifecycle_drain.h"

#include <atomic>
#include <cassert>
#include <chrono>
#include <thread>

namespace {

using namespace std::chrono_literals;

void ConsumedNonfatalWaitsForHandoff() {
  tracebox::HandlerLifecycleDrainBarrierV1 barrier(2);
  assert(barrier.OpenAdmission());
  assert(barrier.TryAdmit());

  std::atomic<bool> watcher_started{false};
  std::atomic<bool> allow_handoff{false};
  std::atomic<bool> terminal_forced{false};
  std::atomic<bool> handoff_forced{false};
  std::thread watcher([&] {
    watcher_started.store(true, std::memory_order_release);
    while (!allow_handoff.load(std::memory_order_acquire)) {
      std::this_thread::yield();
    }
    terminal_forced.store(true, std::memory_order_release);
    handoff_forced.store(true, std::memory_order_release);
    assert(barrier.CompleteTerminal());
  });

  while (!watcher_started.load(std::memory_order_acquire)) {
    std::this_thread::yield();
  }
  barrier.BeginDrain();
  assert(!barrier.WaitFor(1ms));
  allow_handoff.store(true, std::memory_order_release);
  assert(barrier.WaitFor(1s));
  watcher.join();
  assert(terminal_forced.load(std::memory_order_acquire));
  assert(handoff_forced.load(std::memory_order_acquire));
  assert(barrier.active_watchers() == 0);
}

void DeadClientWaitsForTerminalRecord() {
  tracebox::HandlerLifecycleDrainBarrierV1 barrier(1);
  assert(barrier.OpenAdmission());
  assert(barrier.TryAdmit());
  barrier.BeginDrain();
  assert(!barrier.admission_open());

  std::atomic<bool> dead_terminal_forced{false};
  std::thread watcher([&] {
    dead_terminal_forced.store(true, std::memory_order_release);
    assert(barrier.CompleteTerminal());
  });
  assert(barrier.WaitFor(1s));
  watcher.join();
  assert(dead_terminal_forced.load(std::memory_order_acquire));
}

void RolledBackAdmissionIsAccounted() {
  tracebox::HandlerLifecycleDrainBarrierV1 barrier(1);
  assert(barrier.OpenAdmission());
  assert(barrier.TryAdmit());
  barrier.BeginDrain();
  assert(!barrier.TryAdmit());
  assert(barrier.CancelAdmission());
  assert(barrier.WaitFor(1s));
  assert(!barrier.CancelAdmission());
}

void TimeoutNeverClaimsQuiescence() {
  tracebox::HandlerLifecycleDrainBarrierV1 barrier(1);
  assert(barrier.OpenAdmission());
  assert(barrier.TryAdmit());
  barrier.BeginDrain();
  assert(!barrier.WaitFor(1ms));
  assert(!barrier.TryAdmit());
  assert(barrier.active_watchers() == 1);

  assert(barrier.CompleteTerminal());
  assert(barrier.WaitFor(1s));
  assert(barrier.OpenAdmission());
}

void TerminalDurabilityFailureIsSticky() {
  tracebox::HandlerLifecycleDrainBarrierV1 barrier(1);
  assert(barrier.OpenAdmission());
  assert(barrier.TryAdmit());
  barrier.BeginDrain();
  assert(barrier.CompleteTerminal(false));
  assert(barrier.active_watchers() == 0);
  assert(!barrier.TryAdmit());
  assert(!barrier.WaitFor(1s));
  assert(!barrier.OpenAdmission());
}

void ConcurrentShutdownWaitersObserveOneBoundary() {
  tracebox::HandlerLifecycleDrainBarrierV1 barrier(2);
  assert(barrier.OpenAdmission());
  assert(barrier.TryAdmit());
  assert(barrier.TryAdmit());
  assert(!barrier.TryAdmit());

  std::atomic<int> waiters_started{0};
  std::atomic<int> waiters_completed{0};
  auto shutdown = [&] {
    barrier.BeginDrain();
    waiters_started.fetch_add(1, std::memory_order_acq_rel);
    if (barrier.WaitFor(1s)) {
      waiters_completed.fetch_add(1, std::memory_order_acq_rel);
    }
  };
  std::thread first(shutdown);
  std::thread second(shutdown);
  while (waiters_started.load(std::memory_order_acquire) != 2) {
    std::this_thread::yield();
  }
  assert(barrier.CompleteTerminal());
  assert(barrier.CompleteTerminal());
  first.join();
  second.join();
  assert(waiters_completed.load(std::memory_order_acquire) == 2);
  assert(barrier.active_watchers() == 0);
}

}  // namespace

int main() {
  ConsumedNonfatalWaitsForHandoff();
  DeadClientWaitsForTerminalRecord();
  RolledBackAdmissionIsAccounted();
  TimeoutNeverClaimsQuiescence();
  TerminalDurabilityFailureIsSticky();
  ConcurrentShutdownWaitersObserveOneBoundary();
  return 0;
}
