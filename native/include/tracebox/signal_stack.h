#ifndef TRACEBOX_SIGNAL_STACK_H_
#define TRACEBOX_SIGNAL_STACK_H_

#ifdef __cplusplus
extern "C" {
#endif

#if defined(_WIN32)
#define TB_SIGNAL_STACK_API __declspec(dllexport)
#elif defined(__GNUC__)
#define TB_SIGNAL_STACK_API __attribute__((visibility("default")))
#else
#define TB_SIGNAL_STACK_API
#endif

/*
 * Registers a Tracebox-owned alternate signal stack for the calling thread.
 *
 * Registration is idempotent, preserves a pre-existing alternate stack, and
 * is automatically released when the thread exits. Host-created native
 * threads must call this before they can rely on Tracebox's stack-overflow
 * emergency fallback. Explicitly unregister before unloading the library from
 * a still-running thread. This function allocates and is not async-signal-safe.
 */
TB_SIGNAL_STACK_API int tb_register_current_thread_signal_stack_v1(void);

/*
 * Restores the calling thread's pre-existing alternate stack and releases the
 * Tracebox allocation. This is idempotent and must not be called from a signal
 * handler. A failed release remains registered so a later call can retry.
 */
TB_SIGNAL_STACK_API int tb_unregister_current_thread_signal_stack_v1(void);

/* Returns 1 only while Tracebox's alternate stack is active on this thread. */
TB_SIGNAL_STACK_API int tb_current_thread_signal_stack_registered_v1(void);

#ifdef __cplusplus
}  // extern "C"

#include <utility>

namespace tracebox {

using ThreadResourceReleaseV1 = bool (*)(void*) noexcept;

/*
 * Owns one opaque per-thread resource. The embedding translation unit declares
 * a thread_local instance; Reset() and the thread-local destructor share the
 * same idempotent release path.
 */
class CurrentThreadResourceRegistrationV1 {
 public:
  CurrentThreadResourceRegistrationV1() = default;
  ~CurrentThreadResourceRegistrationV1() {
    static_cast<void>(Reset());
  }

  CurrentThreadResourceRegistrationV1(
      const CurrentThreadResourceRegistrationV1&) = delete;
  CurrentThreadResourceRegistrationV1& operator=(
      const CurrentThreadResourceRegistrationV1&) = delete;

  bool Adopt(void* resource, ThreadResourceReleaseV1 release) noexcept {
    if (resource == nullptr || release == nullptr || resource_ != nullptr) {
      return false;
    }
    resource_ = resource;
    release_ = release;
    return true;
  }

  bool Reset() noexcept {
    if (resource_ == nullptr) {
      return true;
    }
    if (!release_(resource_)) {
      return false;
    }
    resource_ = nullptr;
    release_ = nullptr;
    return true;
  }

  bool active() const noexcept {
    return resource_ != nullptr;
  }

  void* resource() const noexcept {
    return resource_;
  }

 private:
  void* resource_ = nullptr;
  ThreadResourceReleaseV1 release_ = nullptr;
};

/*
 * Tracks process-wide signal-handler installation independently from the
 * per-thread alternate-stack registrations above. Callers serialize access.
 */
class ProcessSignalHandlerInstallationV1 {
 public:
  template <typename Installer>
  bool EnsureInstalled(Installer&& installer) {
    if (installed_) {
      return true;
    }
    if (!std::forward<Installer>(installer)()) {
      return false;
    }
    installed_ = true;
    return true;
  }

  template <typename Restorer>
  bool Restore(Restorer&& restorer) {
    if (!installed_) {
      return true;
    }
    if (!std::forward<Restorer>(restorer)()) {
      return false;
    }
    installed_ = false;
    return true;
  }

  bool installed() const noexcept {
    return installed_;
  }

 private:
  bool installed_ = false;
};

}  // namespace tracebox
#endif

#undef TB_SIGNAL_STACK_API

#endif  // TRACEBOX_SIGNAL_STACK_H_
