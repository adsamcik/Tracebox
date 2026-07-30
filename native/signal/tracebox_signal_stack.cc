#include "tracebox/signal_stack.h"

#include <pthread.h>
#include <signal.h>
#include <sys/mman.h>

#include <cerrno>
#include <new>

namespace {

constexpr size_t kSignalStackBytes = 64 * 1024;

struct SignalStackAllocation {
  void* memory = nullptr;
  stack_t previous{};
};

pthread_key_t g_signal_stack_key;
pthread_once_t g_signal_stack_key_once = PTHREAD_ONCE_INIT;
int g_signal_stack_key_status = EAGAIN;

void DestroyThreadRegistration(void* opaque) {
  auto* registration =
      static_cast<tracebox::CurrentThreadResourceRegistrationV1*>(opaque);
  sigset_t blocked;
  sigfillset(&blocked);
  static_cast<void>(pthread_sigmask(SIG_BLOCK, &blocked, nullptr));
  if (!registration->Reset()) {
    // POSIX retries a non-null destructor value up to
    // PTHREAD_DESTRUCTOR_ITERATIONS times. A transient restore/unmap failure
    // therefore remains owned rather than becoming a double-unmap.
    static_cast<void>(pthread_setspecific(
        g_signal_stack_key, registration));
    return;
  }
  delete registration;
}

void InitializeSignalStackKey() {
  g_signal_stack_key_status =
      pthread_key_create(
          &g_signal_stack_key, DestroyThreadRegistration);
}

tracebox::CurrentThreadResourceRegistrationV1* CurrentRegistration(
    bool create) {
  if (pthread_once(
          &g_signal_stack_key_once, InitializeSignalStackKey) != 0 ||
      g_signal_stack_key_status != 0) {
    return nullptr;
  }
  auto* registration =
      static_cast<tracebox::CurrentThreadResourceRegistrationV1*>(
          pthread_getspecific(g_signal_stack_key));
  if (registration != nullptr || !create) {
    return registration;
  }
  registration =
      new (std::nothrow)
          tracebox::CurrentThreadResourceRegistrationV1();
  if (registration == nullptr ||
      pthread_setspecific(g_signal_stack_key, registration) != 0) {
    delete registration;
    return nullptr;
  }
  return registration;
}

bool IsOwnedStack(const SignalStackAllocation& allocation,
                  const stack_t& current) noexcept {
  return (current.ss_flags & SS_DISABLE) == 0 &&
         current.ss_sp == allocation.memory &&
         current.ss_size == kSignalStackBytes;
}

bool ReleaseSignalStack(void* opaque) noexcept {
  auto* allocation = static_cast<SignalStackAllocation*>(opaque);
  stack_t current{};
  if (sigaltstack(nullptr, &current) != 0) {
    return false;
  }
  const bool owned_stack_is_current = IsOwnedStack(*allocation, current);
  if (owned_stack_is_current) {
    if ((current.ss_flags & SS_ONSTACK) != 0 ||
        sigaltstack(&allocation->previous, nullptr) != 0) {
      return false;
    }
  }
  if (munmap(allocation->memory, kSignalStackBytes) != 0) {
    return false;
  }
  delete allocation;
  return true;
}

bool RegistrationStillOwnsCurrentStack() noexcept {
  auto* registration = CurrentRegistration(false);
  if (registration == nullptr || !registration->active()) {
    return false;
  }
  auto* allocation = static_cast<SignalStackAllocation*>(
      registration->resource());
  stack_t current{};
  return sigaltstack(nullptr, &current) == 0 &&
         IsOwnedStack(*allocation, current);
}

}  // namespace

extern "C" int tb_register_current_thread_signal_stack_v1(void) {
  if (RegistrationStillOwnsCurrentStack()) {
    return 0;
  }
  auto* registration = CurrentRegistration(true);
  if (registration == nullptr ||
      (registration->active() && !registration->Reset())) {
    return -1;
  }

  auto* allocation = new (std::nothrow) SignalStackAllocation();
  if (allocation == nullptr ||
      sigaltstack(nullptr, &allocation->previous) != 0) {
    delete allocation;
    return -1;
  }
  allocation->memory = mmap(nullptr,
                            kSignalStackBytes,
                            PROT_READ | PROT_WRITE,
                            MAP_PRIVATE | MAP_ANONYMOUS,
                            -1,
                            0);
  if (allocation->memory == MAP_FAILED) {
    delete allocation;
    return -1;
  }

  stack_t replacement{};
  replacement.ss_sp = allocation->memory;
  replacement.ss_size = kSignalStackBytes;
  if (sigaltstack(&replacement, nullptr) != 0) {
    munmap(allocation->memory, kSignalStackBytes);
    delete allocation;
    return -1;
  }
  if (!registration->Adopt(allocation, ReleaseSignalStack)) {
    static_cast<void>(sigaltstack(&allocation->previous, nullptr));
    munmap(allocation->memory, kSignalStackBytes);
    delete allocation;
    return -1;
  }
  return 0;
}

extern "C" int tb_unregister_current_thread_signal_stack_v1(void) {
  auto* registration = CurrentRegistration(false);
  if (registration == nullptr) {
    return 0;
  }
  if (!registration->Reset() ||
      pthread_setspecific(g_signal_stack_key, nullptr) != 0) {
    return -1;
  }
  delete registration;
  return 0;
}

extern "C" int tb_current_thread_signal_stack_registered_v1(void) {
  return RegistrationStillOwnsCurrentStack() ? 1 : 0;
}
