#include <jni.h>

#include <dirent.h>
#include <fcntl.h>
#include <poll.h>
#include <pthread.h>
#include <sched.h>
#include <signal.h>
#include <sys/file.h>
#include <sys/prctl.h>
#include <sys/resource.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/un.h>
#include <time.h>
#include <ucontext.h>
#include <unistd.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <cerrno>
#include <chrono>
#include <climits>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <new>
#include <string>
#include <string_view>
#include <vector>

#include "base/files/file_path.h"
#include "client/crashpad_client.h"
#include "handler/handler_main.h"
#include "snapshot/sanitized/sanitization_information.h"
#include "tracebox/client_lifecycle_journal.h"
#include "tracebox/client_registration.h"
#include "tracebox/emergency.h"
#include "tracebox/handler_lifecycle_drain.h"
#include "tracebox/handler_socket_cleanup.h"
#include "tracebox/policy_transition.h"
#include "tracebox/rust_bridge.h"
#include "tracebox/signal_stack.h"
#include "util/file/file_io.h"
#include "util/linux/exception_handler_client.h"
#include "util/linux/exception_handler_protocol.h"
#include "util/linux/exception_information.h"
#include "util/misc/address_types.h"
#include "util/misc/capture_context.h"
#include "util/misc/from_pointer_cast.h"

namespace {

constexpr int kControlBacklog = 8;
constexpr size_t kMaximumClients = 16;
constexpr int kRegistrationDeadlineMillis = 2'000;
constexpr int kNonfatalDeadlineMillis = 2'000;
constexpr uint32_t kDefaultHandlerDrainTimeoutMillis = 3'000;
constexpr uint32_t kMaximumHandlerDrainTimeoutMillis = 5'000;
constexpr int kStaleHandlerSocketProbeMillis = 250;
constexpr uint32_t kConsumedHandoffWaitMillis = 1'000;
constexpr uint32_t kDeadClientHandoffWaitMillis = 2'250;
constexpr uint32_t kShutdownHandoffWaitMillis = 2'250;
constexpr off_t kCrashpadMetadataBytes = 32;
constexpr long kHandoffPollNanoseconds = 10'000'000;
constexpr long kLifecycleActivationPollNanoseconds = 1'000'000;
// The public deadline includes cancellation, descriptor teardown, and JNI return.
constexpr uint64_t kDeadlineCompletionReserveNanoseconds = UINT64_C(100'000'000);
constexpr long kConnectDelayNanoseconds = 50'000'000;
constexpr size_t kProcessIdentityBytes = 32;
constexpr size_t kMaximumIdentityJournalBytes = 64 * 1024;
constexpr size_t kIdentityJournalPayloadBytes = 132;
constexpr uint64_t kMaximumNativeRawStagingBytes =
    UINT64_C(16) * 1024 * 1024;
constexpr size_t kMaximumClientJournalFiles = 64;
constexpr uint64_t kMaximumClientJournalBytes =
    kMaximumClientJournalFiles * UINT64_C(384);
constexpr uint64_t kCrashSummaryCategory = UINT64_C(1);
constexpr uint64_t kEmergencyCategory = UINT64_C(2);
constexpr uint64_t kRustPanicCategory = UINT64_C(32);
constexpr uint64_t kAnrCategory = UINT64_C(64);
constexpr uint32_t kRegistrationMagic = UINT32_C(0x54425247);
constexpr uint16_t kRegistrationVersion = 2;
constexpr uint16_t kRegistrationRequestType = 1;
constexpr uint16_t kRegistrationReplyType = 2;
constexpr uint32_t kClientJournalMagic = UINT32_C(0x5442434a);
constexpr uint16_t kClientJournalVersion = 1;
constexpr uint32_t kIdentityJournalMagic = UINT32_C(0x5442494a);
constexpr uint16_t kIdentityJournalVersion = 1;
constexpr uint16_t kIdentityAllocationEntry = 1;
constexpr uint16_t kSummaryDerivationEntry = 2;
constexpr uint32_t kSummaryIdentityKind = 7;
constexpr std::array<int, 6> kHandledSignals{
    SIGABRT, SIGBUS, SIGFPE, SIGILL, SIGSEGV, SIGTRAP};

std::atomic<bool> g_handler_alive{false};
std::atomic<bool> g_handler_draining{false};
std::atomic<int32_t> g_handler_pid{-1};
std::atomic<bool> g_crashpad_socket_fenced{true};
std::atomic<int> g_control_socket{-1};
std::atomic<int> g_control_directory_fd{-1};
std::atomic<int> g_shared_client_socket{-1};
std::atomic<int> g_handler_server_socket{-1};
std::atomic<int> g_registration_socket{-1};
std::atomic<bool> g_policy_participant_alive{false};
int g_emergency_fd = -1;
std::atomic<int> g_rust_panic_fd{-1};
uint32_t g_process_role = 0;
std::array<uint8_t, kProcessIdentityBytes> g_process_id{};
std::array<uint8_t, kProcessIdentityBytes> g_armed_raw_artifact_id{};
std::atomic<bool> g_raw_artifact_armed{false};
std::array<char, 4096> g_emergency_directory{};
size_t g_emergency_directory_size = 0;
std::array<char, 256> g_pending_directory{};
std::array<char, 4096> g_client_journal_directory{};
std::array<char, 4096> g_handler_handoff_directory{};
std::array<char, 4096> g_control_socket_path{};
std::atomic<bool> g_control_socket_path_active{false};
uint64_t g_sequence = 0;
std::atomic<uint64_t> g_registration_sequence{0};
// A single outstanding Crashpad report is deliberately leased at a time. Crashpad's
// shared-client database does not provide a trustworthy report-to-client identifier, so
// admitting concurrent armed registrations would make raw-artifact association ambiguous.
// Secondary processes fail closed with kUnavailable until the current lease is consumed or
// disconnected.
std::atomic<bool> g_capture_lease_held{false};
std::atomic<uint64_t> g_policy_epoch{0};
std::atomic<uint64_t> g_policy_deny_mask{UINT64_MAX};
std::atomic<bool> g_policy_disabled{true};
std::atomic<uint64_t> g_policy_generation{0};
std::atomic<uint32_t> g_capture_operations{0};
__thread volatile sig_atomic_t g_in_signal = 0;
tracebox::ProcessSignalHandlerInstallationV1 g_signal_handler_installation;
std::array<struct sigaction, kHandledSignals.size()> g_previous_actions{};
std::array<struct sigaction, kHandledSignals.size()> g_default_actions{};
pthread_mutex_t g_lifecycle_mutex = PTHREAD_MUTEX_INITIALIZER;
pthread_mutex_t g_policy_mutex = PTHREAD_MUTEX_INITIALIZER;
pthread_mutex_t g_client_sockets_mutex = PTHREAD_MUTEX_INITIALIZER;
pthread_cond_t g_client_sockets_changed = PTHREAD_COND_INITIALIZER;
pthread_mutex_t g_handler_shutdown_mutex = PTHREAD_MUTEX_INITIALIZER;
pthread_mutex_t g_policy_transition_mutex = PTHREAD_MUTEX_INITIALIZER;
pthread_mutex_t g_policy_command_mutex = PTHREAD_MUTEX_INITIALIZER;
pthread_mutex_t g_policy_result_mutex = PTHREAD_MUTEX_INITIALIZER;
pthread_cond_t g_policy_result_changed = PTHREAD_COND_INITIALIZER;
std::array<int, kMaximumClients> g_client_lifecycle_sockets = [] {
  std::array<int, kMaximumClients> sockets{};
  sockets.fill(-1);
  return sockets;
}();
std::array<uint32_t, kMaximumClients> g_client_process_roles{};
std::array<uint64_t, kMaximumClients> g_client_ack_epochs{};
std::array<uint32_t, kMaximumClients> g_client_ack_operations{};
std::array<uint32_t, kMaximumClients> g_client_ack_statuses{};

tracebox::HandlerLifecycleDrainBarrierV1& ClientLifecycleDrain() {
  // Crashpad forbids exit-time destructors. The capture-only handler is
  // process-scoped, so this synchronization object intentionally lives until
  // process exit.
  static auto* const drain =
      new tracebox::HandlerLifecycleDrainBarrierV1(kMaximumClients);
  return *drain;
}

using PolicyState = tracebox::PolicyStateV1;
using PreparedPolicy = tracebox::PolicyTransitionV1;

PreparedPolicy g_handler_prepared_policy;
PreparedPolicy g_client_prepared_policy;
std::atomic<bool> g_handler_transition_active{false};
uint64_t g_policy_result_epoch = 0;
uint32_t g_policy_result_operation = 0;
int32_t g_policy_result_status = 2;
bool g_policy_result_ready = false;

enum class RegistrationOutcome : int32_t {
  kSuccess = 0,
  kDeadlineExceeded = 1,
  kUnavailable = 2,
  kProtocolError = 3,
  kSystemError = 4,
};

#pragma pack(push, 1)
struct RegistrationRequest {
  uint32_t magic;
  uint16_t version;
  uint16_t message_type;
  uint32_t message_size;
  int32_t client_pid;
  uint32_t process_role;
  uint64_t policy_epoch;
  uint8_t process_id[kProcessIdentityBytes];
  uint8_t raw_artifact_id[kProcessIdentityBytes];
  uint32_t requested_mode;
};

struct RegistrationReply {
  uint32_t magic;
  uint16_t version;
  uint16_t message_type;
  uint32_t message_size;
  int32_t status;
  int32_t handler_pid;
  uint32_t handler_role;
  uint32_t client_role;
  uint64_t handler_policy_epoch;
  uint64_t requested_policy_epoch;
  uint8_t handler_process_id[kProcessIdentityBytes];
  uint8_t client_process_id[kProcessIdentityBytes];
  uint8_t raw_artifact_id[kProcessIdentityBytes];
  uint32_t granted_mode;
};

struct ClientJournalRecord {
  uint32_t magic;
  uint16_t version;
  uint16_t state;
  uint32_t record_size;
  int32_t client_pid;
  uint32_t client_uid;
  uint32_t process_role;
  uint32_t reserved;
  uint64_t policy_epoch;
  uint64_t monotonic_time_ns;
  uint64_t registration_sequence;
  int64_t pending_sequence;
  uint8_t process_id[kProcessIdentityBytes];
  uint8_t raw_artifact_id[kProcessIdentityBytes];
  uint8_t padding[64];
  uint32_t checksum;
};

struct IdentityJournalEntry {
  uint32_t magic;
  uint16_t version;
  uint16_t entry_type;
  uint32_t record_size;
  uint32_t identity_kind;
  uint32_t payload_size;
  uint32_t reserved;
  uint64_t sequence;
  uint8_t payload[kIdentityJournalPayloadBytes];
  uint32_t checksum;
};
#pragma pack(pop)

static_assert(sizeof(RegistrationRequest) == 96);
static_assert(sizeof(RegistrationReply) == 144);
static_assert(sizeof(ClientJournalRecord) == 192);
static_assert(sizeof(ClientJournalRecord) * 2 ==
              tracebox::kClientLifecycleJournalBytesV1);
static_assert(tracebox::kClientLifecycleJournalBytesV1 <=
              kMaximumClientJournalBytes);
static_assert(sizeof(IdentityJournalEntry) == 168);
static_assert(std::atomic<uint64_t>::is_always_lock_free);
static_assert(std::atomic<uint32_t>::is_always_lock_free);

std::string CopyString(JNIEnv* env, jstring value) {
  const char* chars = env->GetStringUTFChars(value, nullptr);
  if (chars == nullptr) {
    return {};
  }
  std::string result(chars);
  env->ReleaseStringUTFChars(value, chars);
  return result;
}

std::string ParentDirectory(const std::string& path) {
  const size_t separator = path.find_last_of('/');
  return separator == std::string::npos ? std::string() : path.substr(0, separator);
}

template <size_t Size>
bool CopyByteArray(JNIEnv* env,
                   jbyteArray value,
                   std::array<uint8_t, Size>* destination) {
  if (value == nullptr ||
      env->GetArrayLength(value) != static_cast<jsize>(Size)) {
    return false;
  }
  env->GetByteArrayRegion(
      value, 0, static_cast<jsize>(Size),
      reinterpret_cast<jbyte*>(destination->data()));
  return !env->ExceptionCheck();
}

jbyteArray NewByteArray(JNIEnv* env, const uint8_t* bytes, size_t size) {
  if (size > static_cast<size_t>(INT_MAX)) {
    return nullptr;
  }
  jbyteArray result = env->NewByteArray(static_cast<jsize>(size));
  if (result == nullptr) {
    return nullptr;
  }
  env->SetByteArrayRegion(
      result, 0, static_cast<jsize>(size),
      reinterpret_cast<const jbyte*>(bytes));
  return env->ExceptionCheck() ? nullptr : result;
}

template <size_t Size>
bool IsAllZero(const std::array<uint8_t, Size>& value) {
  uint8_t aggregate = 0;
  for (uint8_t byte : value) {
    aggregate |= byte;
  }
  return aggregate == 0;
}

bool PwriteAll(int fd, const void* bytes, size_t size, off_t offset) {
  const auto* source = static_cast<const uint8_t*>(bytes);
  size_t written = 0;
  while (written < size) {
    const ssize_t result =
        pwrite(fd, source + written, size - written,
               offset + static_cast<off_t>(written));
    if (result > 0) {
      written += static_cast<size_t>(result);
      continue;
    }
    if (result < 0 && errno == EINTR) {
      continue;
    }
    return false;
  }
  return true;
}

bool PreadAll(int fd, void* bytes, size_t size, off_t offset) {
  auto* destination = static_cast<uint8_t*>(bytes);
  size_t read_count = 0;
  while (read_count < size) {
    const ssize_t result =
        pread(fd, destination + read_count, size - read_count,
              offset + static_cast<off_t>(read_count));
    if (result > 0) {
      read_count += static_cast<size_t>(result);
      continue;
    }
    if (result < 0 && errno == EINTR) {
      continue;
    }
    return false;
  }
  return true;
}

bool SyncParentDirectory(const std::string& path) {
  const std::string parent = ParentDirectory(path);
  if (parent.empty()) {
    return false;
  }
  const int directory_fd =
      open(parent.c_str(), O_RDONLY | O_DIRECTORY | O_CLOEXEC);
  if (directory_fd < 0) {
    return false;
  }
  const bool synced = fsync(directory_fd) == 0;
  close(directory_fd);
  return synced;
}

uint64_t MonotonicNanoseconds() {
  timespec now{};
  if (clock_gettime(CLOCK_MONOTONIC, &now) != 0) {
    return 0;
  }
  return static_cast<uint64_t>(now.tv_sec) * 1'000'000'000ULL +
         static_cast<uint64_t>(now.tv_nsec);
}

struct CapturePermit {
  uint64_t policy_epoch = 0;
  bool active = false;
};

void EndCapture(CapturePermit* permit) {
  if (permit->active) {
    permit->active = false;
    g_capture_operations.fetch_sub(1, std::memory_order_release);
  }
}

bool BeginCapture(uint64_t category_mask, CapturePermit* permit) {
  const uint64_t generation =
      g_policy_generation.load(std::memory_order_acquire);
  if ((generation & UINT64_C(1)) != 0) {
    return false;
  }
  g_capture_operations.fetch_add(1, std::memory_order_acq_rel);
  if (g_policy_generation.load(std::memory_order_acquire) != generation) {
    g_capture_operations.fetch_sub(1, std::memory_order_release);
    return false;
  }
  const bool disabled = g_policy_disabled.load(std::memory_order_acquire);
  const uint64_t deny_mask =
      g_policy_deny_mask.load(std::memory_order_acquire);
  if (disabled || (deny_mask & category_mask) != 0) {
    g_capture_operations.fetch_sub(1, std::memory_order_release);
    return false;
  }
  permit->policy_epoch = g_policy_epoch.load(std::memory_order_acquire);
  permit->active = true;
  return true;
}

bool ShutdownHandlerTransport(
    uint32_t timeout_millis = kDefaultHandlerDrainTimeoutMillis);
void FenceCrashpadClient();
void FenceDisconnectedPolicyParticipant();

bool UpdatePolicyState(uint64_t epoch,
                       bool disabled,
                       uint64_t deny_mask,
                       bool close_denied_native_transport,
                       bool allow_epoch_rollback = false) {
  if (pthread_mutex_lock(&g_policy_mutex) != 0) {
    return false;
  }
  if (!allow_epoch_rollback &&
      epoch < g_policy_epoch.load(std::memory_order_acquire)) {
    pthread_mutex_unlock(&g_policy_mutex);
    return false;
  }

  g_policy_generation.fetch_add(1, std::memory_order_acq_rel);
  if (close_denied_native_transport &&
      (disabled || (deny_mask & kCrashSummaryCategory) != 0)) {
    FenceCrashpadClient();
  }
  while (g_capture_operations.load(std::memory_order_acquire) != 0) {
    sched_yield();
  }
  const int rust_panic_fd =
      g_rust_panic_fd.load(std::memory_order_acquire);
  if (rust_panic_fd >= 0 &&
      tb_android_configure_panic_slot_v1(
          rust_panic_fd,
          epoch,
          g_process_role,
          !disabled && (deny_mask & kRustPanicCategory) == 0 ? 1 : 0) != 0) {
    g_policy_generation.fetch_add(1, std::memory_order_release);
    pthread_mutex_unlock(&g_policy_mutex);
    return false;
  }
  g_policy_epoch.store(epoch, std::memory_order_release);
  g_policy_deny_mask.store(deny_mask, std::memory_order_release);
  g_policy_disabled.store(disabled, std::memory_order_release);
  g_policy_generation.fetch_add(1, std::memory_order_release);
  pthread_mutex_unlock(&g_policy_mutex);
  return true;
}

uint64_t DeadlineAfterMilliseconds(int milliseconds) {
  const uint64_t now = MonotonicNanoseconds();
  if (now == 0 || milliseconds <= 0) {
    return 0;
  }
  return now + static_cast<uint64_t>(milliseconds) * UINT64_C(1'000'000);
}

uint64_t BlockingDeadline(uint64_t overall_deadline) {
  return overall_deadline > kDeadlineCompletionReserveNanoseconds
             ? overall_deadline - kDeadlineCompletionReserveNanoseconds
             : overall_deadline;
}

int RemainingPollMilliseconds(uint64_t deadline) {
  const uint64_t now = MonotonicNanoseconds();
  if (now == 0 || now >= deadline) {
    return 0;
  }
  const uint64_t remaining_ns = deadline - now;
  const uint64_t whole_ms = remaining_ns / UINT64_C(1'000'000);
  return whole_ms > static_cast<uint64_t>(INT_MAX)
             ? INT_MAX
             : static_cast<int>(whole_ms);
}

RegistrationOutcome WaitForSocket(int socket_fd,
                                  short events,
                                  uint64_t deadline) {
  while (true) {
    const int timeout_ms = RemainingPollMilliseconds(deadline);
    if (timeout_ms == 0) {
      return RegistrationOutcome::kDeadlineExceeded;
    }
    pollfd descriptor{socket_fd, events, 0};
    const int result = poll(&descriptor, 1, timeout_ms);
    if (result > 0) {
      if ((descriptor.revents & events) != 0) {
        return RegistrationOutcome::kSuccess;
      }
      return RegistrationOutcome::kUnavailable;
    }
    if (result == 0) {
      return RegistrationOutcome::kDeadlineExceeded;
    }
    if (errno != EINTR) {
      return RegistrationOutcome::kSystemError;
    }
  }
}

constexpr char kHandlerSocketFileName[] = "tracebox-handler.sock";

enum class HandlerSocketEntry {
  kAbsent,
  kOwnedSocket,
  kInvalid,
};

bool LockDirectory(int directory_fd, bool nonblocking) {
  const int operation = LOCK_EX | (nonblocking ? LOCK_NB : 0);
  while (flock(directory_fd, operation) != 0) {
    if (errno != EINTR || nonblocking) {
      return false;
    }
  }
  return true;
}

bool OpenLockedHandlerSocketParent(const std::string& socket_path,
                                   bool nonblocking,
                                   int* directory_fd) {
  if (!tracebox::IsCanonicalHandlerSocketPathV1(socket_path) ||
      socket_path.size() >= sizeof(sockaddr_un::sun_path)) {
    return false;
  }
  const std::string parent = ParentDirectory(socket_path);
  std::array<char, PATH_MAX> resolved_parent{};
  if (parent.empty() ||
      realpath(parent.c_str(), resolved_parent.data()) == nullptr ||
      !tracebox::IsCanonicalHandlerSocketPathV1(
          std::string(resolved_parent.data()) + "/" +
          kHandlerSocketFileName)) {
    return false;
  }
  const int opened =
      open(parent.c_str(),
           O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
  struct stat parent_status {};
  if (opened < 0 ||
      fstat(opened, &parent_status) != 0 ||
      !S_ISDIR(parent_status.st_mode) ||
      parent_status.st_uid != geteuid() ||
      (parent_status.st_mode & (S_IWGRP | S_IWOTH)) != 0 ||
      !LockDirectory(opened, nonblocking)) {
    if (opened >= 0) {
      close(opened);
    }
    return false;
  }
  *directory_fd = opened;
  return true;
}

HandlerSocketEntry ReadHandlerSocketEntry(int directory_fd,
                                          struct stat* status) {
  std::memset(status, 0, sizeof(*status));
  if (fstatat(directory_fd,
              kHandlerSocketFileName,
              status,
              AT_SYMLINK_NOFOLLOW) != 0) {
    return errno == ENOENT ? HandlerSocketEntry::kAbsent
                           : HandlerSocketEntry::kInvalid;
  }
  return S_ISSOCK(status->st_mode) &&
                 status->st_uid == geteuid() &&
                 status->st_nlink == 1 &&
                 (status->st_mode & (S_IRWXG | S_IRWXO)) == 0
             ? HandlerSocketEntry::kOwnedSocket
             : HandlerSocketEntry::kInvalid;
}

bool SameHandlerSocketEntry(const struct stat& left,
                            const struct stat& right) {
  return left.st_dev == right.st_dev &&
         left.st_ino == right.st_ino &&
         left.st_uid == right.st_uid &&
         left.st_mode == right.st_mode &&
         left.st_nlink == right.st_nlink;
}

tracebox::HandlerSocketProbeV1 ProbeHandlerSocket(
    const std::string& socket_path,
    int timeout_millis) {
  const int probe =
      socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
  if (probe < 0) {
    return tracebox::HandlerSocketProbeV1::kAmbiguous;
  }
  sockaddr_un address{};
  address.sun_family = AF_UNIX;
  std::memcpy(
      address.sun_path, socket_path.c_str(), socket_path.size() + 1);
  int connect_error = 0;
  if (connect(probe,
              reinterpret_cast<const sockaddr*>(&address),
              sizeof(address)) == 0) {
    close(probe);
    return tracebox::HandlerSocketProbeV1::kListenerAlive;
  }
  connect_error = errno;
  if (connect_error == EINPROGRESS) {
    const uint64_t deadline = DeadlineAfterMilliseconds(timeout_millis);
    pollfd descriptor{probe, POLLOUT, 0};
    int result;
    do {
      result = deadline == 0
                   ? -1
                   : poll(&descriptor,
                          1,
                          RemainingPollMilliseconds(deadline));
    } while (result < 0 && errno == EINTR &&
             RemainingPollMilliseconds(deadline) > 0);
    if (result > 0) {
      socklen_t error_size = sizeof(connect_error);
      if (getsockopt(probe,
                     SOL_SOCKET,
                     SO_ERROR,
                     &connect_error,
                     &error_size) != 0) {
        connect_error = errno;
      }
    } else {
      connect_error = 0;
      close(probe);
      return tracebox::HandlerSocketProbeV1::kAmbiguous;
    }
  }
  close(probe);
  if (connect_error == 0) {
    return tracebox::HandlerSocketProbeV1::kListenerAlive;
  }
  if (connect_error == ECONNREFUSED) {
    return tracebox::HandlerSocketProbeV1::kConnectionRefused;
  }
  if (connect_error == ENOENT) {
    return tracebox::HandlerSocketProbeV1::kPathAbsent;
  }
  return tracebox::HandlerSocketProbeV1::kAmbiguous;
}

bool RemoveStaleHandlerSocketLocked(const std::string& socket_path,
                                    int directory_fd) {
  struct stat initial {};
  const HandlerSocketEntry initial_entry =
      ReadHandlerSocketEntry(directory_fd, &initial);
  if (initial_entry == HandlerSocketEntry::kAbsent) {
    return true;
  }
  if (initial_entry != HandlerSocketEntry::kOwnedSocket) {
    return false;
  }
  const tracebox::HandlerSocketProbeV1 probe =
      ProbeHandlerSocket(socket_path, kStaleHandlerSocketProbeMillis);
  if (!tracebox::HandlerSocketProbePermitsCleanupV1(probe)) {
    return false;
  }
  struct stat current {};
  const HandlerSocketEntry current_entry =
      ReadHandlerSocketEntry(directory_fd, &current);
  if (probe == tracebox::HandlerSocketProbeV1::kPathAbsent) {
    return current_entry == HandlerSocketEntry::kAbsent;
  }
  if (current_entry != HandlerSocketEntry::kOwnedSocket ||
      !SameHandlerSocketEntry(initial, current) ||
      unlinkat(directory_fd, kHandlerSocketFileName, 0) != 0) {
    return false;
  }
  return fsync(directory_fd) == 0;
}

bool CleanupStaleHandlerSocket(const std::string& socket_path) {
  int directory_fd = -1;
  if (!OpenLockedHandlerSocketParent(
          socket_path, true, &directory_fd)) {
    return false;
  }
  const bool cleaned =
      RemoveStaleHandlerSocketLocked(socket_path, directory_fd);
  static_cast<void>(flock(directory_fd, LOCK_UN));
  close(directory_fd);
  return cleaned;
}

RegistrationOutcome WaitBeforeConnectRetry(uint64_t deadline) {
  const uint64_t now = MonotonicNanoseconds();
  if (now == 0) {
    return RegistrationOutcome::kSystemError;
  }
  if (now >= deadline) {
    return RegistrationOutcome::kDeadlineExceeded;
  }
  uint64_t remaining = deadline - now;
  if (remaining > static_cast<uint64_t>(kConnectDelayNanoseconds)) {
    remaining = static_cast<uint64_t>(kConnectDelayNanoseconds);
  }
  timespec delay{
      static_cast<time_t>(remaining / UINT64_C(1'000'000'000)),
      static_cast<long>(remaining % UINT64_C(1'000'000'000))};
  while (nanosleep(&delay, &delay) != 0) {
    if (errno != EINTR) {
      return RegistrationOutcome::kSystemError;
    }
  }
  return RegistrationOutcome::kSuccess;
}

size_t SignalIndex(int signal_number) {
  for (size_t index = 0; index < kHandledSignals.size(); ++index) {
    if (kHandledSignals[index] == signal_number) {
      return index;
    }
  }
  return kHandledSignals.size();
}

void ExtractControlAddresses(void* context,
                             uint64_t* instruction_address,
                             uint64_t* link_address) {
  *instruction_address = 0;
  *link_address = 0;
  if (context == nullptr) {
    return;
  }
  auto* native_context = static_cast<ucontext_t*>(context);
#if defined(__aarch64__)
  *instruction_address = native_context->uc_mcontext.pc;
  *link_address = native_context->uc_mcontext.regs[30];
#elif defined(__x86_64__)
  *instruction_address =
      static_cast<uint64_t>(native_context->uc_mcontext.gregs[REG_RIP]);
#endif
}

bool WriteEmergency(int signal_number, int signal_code, void* context, uint64_t flags) {
  CapturePermit permit;
  if (!BeginCapture(kEmergencyCategory, &permit)) {
    return false;
  }
  const int emergency_fd = g_emergency_fd;
  if (emergency_fd < 0) {
    EndCapture(&permit);
    return false;
  }

  uint64_t instruction_address;
  uint64_t link_address;
  ExtractControlAddresses(context, &instruction_address, &link_address);

  tb_emergency_record_v1 record;
  const uint64_t sequence = __atomic_add_fetch(&g_sequence, 1, __ATOMIC_RELAXED);
  if (tb_emergency_initialize_v1(&record,
                                 g_process_id.data(),
                                 sequence,
                                 permit.policy_epoch,
                                 MonotonicNanoseconds(),
                                 signal_number,
                                 signal_code,
                                 0,
                                 instruction_address,
                                 link_address,
                                 g_process_role,
                                 0,
                                 flags) != 0) {
    EndCapture(&permit);
    return false;
  }

  const bool written =
      pwrite(emergency_fd, record.bytes, sizeof(record.bytes), 0) ==
      static_cast<ssize_t>(sizeof(record.bytes));
  EndCapture(&permit);
  return written;
}

[[noreturn]] void ReraisePrevious(int signal_number) {
  const size_t index = SignalIndex(signal_number);
  const struct sigaction* action =
      index < kHandledSignals.size() ? &g_previous_actions[index] : nullptr;
  const struct sigaction* default_action =
      index < kHandledSignals.size() ? &g_default_actions[index] : nullptr;
  if (action != nullptr && action->sa_handler != SIG_IGN) {
    sigaction(signal_number, action, nullptr);
  } else if (default_action != nullptr) {
    sigaction(signal_number, default_action, nullptr);
  }
  sigset_t unblocked;
  sigemptyset(&unblocked);
  sigaddset(&unblocked, signal_number);
  sigprocmask(SIG_UNBLOCK, &unblocked, nullptr);
  syscall(SYS_tgkill, getpid(), gettid(), signal_number);
  if (default_action != nullptr) {
    sigaction(signal_number, default_action, nullptr);
    syscall(SYS_tgkill, getpid(), gettid(), signal_number);
  }
  _exit(128 + signal_number);
}

void EmergencySignalHandler(int signal_number, siginfo_t* signal_info, void* context) {
  if (g_in_signal != 0) {
    ReraisePrevious(signal_number);
  }
  g_in_signal = 1;
  const int signal_code = signal_info == nullptr ? 0 : signal_info->si_code;
  WriteEmergency(signal_number, signal_code, context, UINT64_C(1));
  ReraisePrevious(signal_number);
}

bool EmergencyLastChance(int signal_number, siginfo_t* signal_info, ucontext_t* context) {
  if (g_in_signal != 0) {
    return false;
  }
  g_in_signal = 1;
  const int signal_code = signal_info == nullptr ? 0 : signal_info->si_code;
  WriteEmergency(signal_number, signal_code, context, UINT64_C(3));
  return false;
}

bool RegisterCurrentThreadSignalStack() {
  if (tb_register_current_thread_signal_stack_v1() != 0) {
    return false;
  }
  g_in_signal = 0;
  return true;
}

bool UnregisterCurrentThreadSignalStack() {
  if (tb_unregister_current_thread_signal_stack_v1() != 0) {
    return false;
  }
  g_in_signal = 0;
  return true;
}

bool InstallEmergencyHandlersLocked() {
  return g_signal_handler_installation.EnsureInstalled([] {
    for (size_t index = 0; index < kHandledSignals.size(); ++index) {
      struct sigaction default_action {};
      sigemptyset(&default_action.sa_mask);
      default_action.sa_handler = SIG_DFL;
      g_default_actions[index] = default_action;

      struct sigaction action {};
      sigemptyset(&action.sa_mask);
      action.sa_sigaction = EmergencySignalHandler;
      action.sa_flags = SA_SIGINFO | SA_ONSTACK;
      if (sigaction(
              kHandledSignals[index], &action, &g_previous_actions[index]) !=
          0) {
        for (size_t restore = 0; restore < index; ++restore) {
          static_cast<void>(sigaction(
              kHandledSignals[restore], &g_previous_actions[restore], nullptr));
        }
        return false;
      }
    }
    return true;
  });
}

bool RestoreEmergencyHandlersLocked() {
  return g_signal_handler_installation.Restore([] {
    bool restored = true;
    for (size_t index = 0; index < kHandledSignals.size(); ++index) {
      restored =
          sigaction(
              kHandledSignals[index], &g_previous_actions[index], nullptr) ==
              0 &&
          restored;
    }
    return restored;
  });
}

using RegisteredThreadEntry = void* (*)(void*);

struct RegisteredThreadStart {
  pthread_mutex_t mutex;
  pthread_cond_t condition;
  RegisteredThreadEntry entry;
  void* argument;
  bool ready = false;
  bool registered = false;
};

void* RunRegisteredThread(void* opaque) {
  auto* start = static_cast<RegisteredThreadStart*>(opaque);
  const RegisteredThreadEntry entry = start->entry;
  void* const argument = start->argument;
  const bool registered = RegisterCurrentThreadSignalStack();
  if (pthread_mutex_lock(&start->mutex) != 0) {
    return nullptr;
  }
  start->registered = registered;
  start->ready = true;
  pthread_cond_signal(&start->condition);
  pthread_mutex_unlock(&start->mutex);
  if (!registered) {
    return nullptr;
  }
  // The pthread-key destructor owns cleanup so the alternate stack remains
  // active through the complete entry point and until pthread exit.
  return entry(argument);
}

int CreateRegisteredThread(pthread_t* thread,
                           RegisteredThreadEntry entry,
                           void* argument) {
  if (thread == nullptr || entry == nullptr) {
    return EINVAL;
  }
  RegisteredThreadStart start{};
  start.entry = entry;
  start.argument = argument;
  int result = pthread_mutex_init(&start.mutex, nullptr);
  if (result != 0) {
    return result;
  }
  result = pthread_cond_init(&start.condition, nullptr);
  if (result != 0) {
    pthread_mutex_destroy(&start.mutex);
    return result;
  }
  result = pthread_mutex_lock(&start.mutex);
  if (result != 0) {
    pthread_cond_destroy(&start.condition);
    pthread_mutex_destroy(&start.mutex);
    return result;
  }
  const int create_result =
      pthread_create(thread, nullptr, RunRegisteredThread, &start);
  if (create_result != 0) {
    pthread_mutex_unlock(&start.mutex);
    pthread_cond_destroy(&start.condition);
    pthread_mutex_destroy(&start.mutex);
    return create_result;
  }
  while (!start.ready && result == 0) {
    result = pthread_cond_wait(&start.condition, &start.mutex);
  }
  const bool registered = result == 0 && start.registered;
  pthread_mutex_unlock(&start.mutex);
  pthread_cond_destroy(&start.condition);
  pthread_mutex_destroy(&start.mutex);
  if (!registered) {
    static_cast<void>(pthread_join(*thread, nullptr));
    return EAGAIN;
  }
  return 0;
}

#pragma pack(push, 1)
struct RegistrationConsumed {
  uint32_t magic;
  uint16_t version;
  uint16_t message_type;
  uint32_t message_size;
  uint8_t raw_artifact_id[kProcessIdentityBytes];
  uint32_t reserved;
};

struct PolicyControlMessage {
  uint32_t magic;
  uint16_t version;
  uint16_t message_type;
  uint32_t message_size;
  uint64_t policy_epoch;
  uint64_t deny_mask;
  uint32_t disabled;
  uint32_t operation;
  int32_t status;
  uint32_t timeout_millis;
  uint32_t reserved;
};
#pragma pack(pop)

static_assert(sizeof(RegistrationConsumed) == 48);
static_assert(sizeof(PolicyControlMessage) == 48);

constexpr uint16_t kRegistrationConsumedType = 3;
constexpr uint16_t kPolicyRequestType = 4;
constexpr uint16_t kPolicyTargetType = 5;
constexpr uint16_t kPolicyAckType = 6;
constexpr uint16_t kPolicyResultType = 7;
constexpr uint32_t kPolicyPrepareOperation = 1;
constexpr uint32_t kPolicyCommitOperation = 2;
constexpr uint32_t kPolicyAbortOperation = 3;
static_assert(kPolicyPrepareOperation ==
              tracebox::kPolicyPrepareOperationV1);
static_assert(kPolicyCommitOperation ==
              tracebox::kPolicyCommitOperationV1);
static_assert(kPolicyAbortOperation ==
              tracebox::kPolicyAbortOperationV1);
constexpr int32_t kPolicySuccess = 0;
constexpr int32_t kPolicyPartial = 1;
constexpr int32_t kPolicyProtocol = 2;
constexpr uint32_t kMaximumPolicyTimeoutMillis = 10'000;
constexpr uint32_t kAutomaticAbortTimeoutMillis = 250;
constexpr uint16_t kClientStateRegistered = static_cast<uint16_t>(
    tracebox::ClientLifecycleStateV1::kRegistered);
constexpr uint16_t kClientStateConsumed = static_cast<uint16_t>(
    tracebox::ClientLifecycleStateV1::kConsumed);
constexpr uint16_t kClientStateDead = static_cast<uint16_t>(
    tracebox::ClientLifecycleStateV1::kDead);
constexpr uint16_t kClientStateProtocolError = static_cast<uint16_t>(
    tracebox::ClientLifecycleStateV1::kProtocolError);
constexpr uint16_t kClientStateHandoffFailed = static_cast<uint16_t>(
    tracebox::ClientLifecycleStateV1::kHandoffFailed);

bool CaptureStagingCanGrantLease();
enum class HandoffOutcome {
  kNoReport,
  kMoved,
  kFailedOrAmbiguous,
};
HandoffOutcome MoveOnlyPendingReportToHandoff(
    const uint8_t raw_artifact_id[kProcessIdentityBytes],
    uint32_t wait_millis);

bool ReadPeerCredentials(int socket_fd, ucred* credentials) {
  socklen_t credentials_size = sizeof(*credentials);
  return getsockopt(socket_fd,
                    SOL_SOCKET,
                    SO_PEERCRED,
                    credentials,
                    &credentials_size) == 0 &&
         credentials_size == sizeof(*credentials) &&
         credentials->uid == getuid();
}

RegistrationOutcome SendPacket(int socket_fd,
                               const void* bytes,
                               size_t size,
                               uint64_t deadline) {
  while (true) {
    const ssize_t sent =
        send(socket_fd, bytes, size, MSG_NOSIGNAL | MSG_DONTWAIT);
    if (sent == static_cast<ssize_t>(size)) {
      return RegistrationOutcome::kSuccess;
    }
    if (sent >= 0) {
      return RegistrationOutcome::kProtocolError;
    }
    if (errno == EINTR) {
      continue;
    }
    if (errno != EAGAIN && errno != EWOULDBLOCK) {
      return RegistrationOutcome::kUnavailable;
    }
    const RegistrationOutcome wait =
        WaitForSocket(socket_fd, POLLOUT, deadline);
    if (wait != RegistrationOutcome::kSuccess) {
      return wait;
    }
  }
}

RegistrationOutcome ReceivePacket(int socket_fd,
                                  void* bytes,
                                  size_t size,
                                  uint64_t deadline,
                                  bool* peer_closed = nullptr) {
  if (peer_closed != nullptr) {
    *peer_closed = false;
  }
  while (true) {
    const ssize_t received =
        recv(socket_fd, bytes, size, MSG_DONTWAIT | MSG_TRUNC);
    if (received == static_cast<ssize_t>(size)) {
      return RegistrationOutcome::kSuccess;
    }
    if (received == 0) {
      if (peer_closed != nullptr) {
        *peer_closed = true;
      }
      return RegistrationOutcome::kUnavailable;
    }
    if (received >= 0) {
      return RegistrationOutcome::kProtocolError;
    }
    if (errno == EINTR) {
      continue;
    }
    if (errno != EAGAIN && errno != EWOULDBLOCK) {
      return RegistrationOutcome::kUnavailable;
    }
    const RegistrationOutcome wait =
        WaitForSocket(socket_fd, POLLIN, deadline);
    if (wait != RegistrationOutcome::kSuccess) {
      return wait;
    }
  }
}

bool ValidRequest(const RegistrationRequest& request,
                  const ucred& credentials) {
  if (request.magic != kRegistrationMagic ||
      request.version != kRegistrationVersion ||
      request.message_type != kRegistrationRequestType ||
      request.message_size != sizeof(request) ||
      request.client_pid != credentials.pid ||
      !tracebox::IsClientRegistrationRequestV2(request.requested_mode)) {
    return false;
  }
  std::array<uint8_t, kProcessIdentityBytes> process_id{};
  std::array<uint8_t, kProcessIdentityBytes> raw_artifact_id{};
  std::memcpy(process_id.data(), request.process_id, process_id.size());
  std::memcpy(
      raw_artifact_id.data(), request.raw_artifact_id, raw_artifact_id.size());
  return !IsAllZero(process_id) &&
         tracebox::ClientRegistrationRawIdentityIsValidV2(
             static_cast<tracebox::ClientRegistrationRequestV2>(
                 request.requested_mode),
             IsAllZero(raw_artifact_id));
}

void InitializeClientJournalRecord(ClientJournalRecord* record,
                                   uint16_t state,
                                   const RegistrationRequest& request,
                                   const ucred& credentials,
                                   uint64_t registration_sequence,
                                   int64_t pending_sequence) {
  std::memset(record, 0, sizeof(*record));
  record->magic = kClientJournalMagic;
  record->version = kClientJournalVersion;
  record->state = state;
  record->record_size = sizeof(*record);
  record->client_pid = credentials.pid;
  record->client_uid = credentials.uid;
  record->process_role = request.process_role;
  record->policy_epoch = request.policy_epoch;
  record->monotonic_time_ns = MonotonicNanoseconds();
  record->registration_sequence = registration_sequence;
  record->pending_sequence = pending_sequence;
  std::memcpy(
      record->process_id, request.process_id, sizeof(record->process_id));
  std::memcpy(record->raw_artifact_id,
              request.raw_artifact_id,
              sizeof(record->raw_artifact_id));
  record->checksum =
      tb_crc32c_v1(reinterpret_cast<const uint8_t*>(record),
                   sizeof(*record) - sizeof(record->checksum));
}

int OpenClientJournal(const RegistrationRequest& request,
                      const ucred& credentials,
                      ClientJournalRecord* registered) {
  const std::string journal_name =
      tracebox::FormatClientLifecycleJournalNameV1(
          request.process_role, request.raw_artifact_id);
  if (journal_name.empty()) {
    return -1;
  }
  const uint64_t sequence =
      g_registration_sequence.fetch_add(1, std::memory_order_relaxed) + 1;
  if (!CaptureStagingCanGrantLease()) {
    return -1;
  }
  InitializeClientJournalRecord(
      registered,
      kClientStateRegistered,
      request,
      credentials,
      sequence,
      0);
  const std::string path =
      std::string(g_client_journal_directory.data()) + "/" + journal_name;
  const int journal_fd =
      open(path.c_str(),
           O_CREAT | O_EXCL | O_RDWR | O_CLOEXEC | O_DSYNC,
           0600);
  if (journal_fd < 0 ||
      ftruncate(
          journal_fd, static_cast<off_t>(sizeof(ClientJournalRecord) * 2)) !=
          0 ||
      !PwriteAll(journal_fd, registered, sizeof(*registered), 0) ||
      fdatasync(journal_fd) != 0 ||
      !SyncParentDirectory(path)) {
    if (journal_fd >= 0) {
      close(journal_fd);
      unlink(path.c_str());
    }
    return -1;
  }
  return journal_fd;
}

bool FinishClientJournal(int journal_fd,
                         const ClientJournalRecord& registered,
                         uint16_t terminal_state) {
  ClientJournalRecord terminal = registered;
  terminal.state = terminal_state;
  terminal.monotonic_time_ns = MonotonicNanoseconds();
  terminal.checksum = 0;
  terminal.checksum =
      tb_crc32c_v1(reinterpret_cast<const uint8_t*>(&terminal),
                   sizeof(terminal) - sizeof(terminal.checksum));
  return PwriteAll(
             journal_fd, &terminal, sizeof(terminal), sizeof(terminal)) &&
         fdatasync(journal_fd) == 0;
}

bool TrackClientLifecycleSocket(int socket_fd, uint32_t process_role) {
  if (pthread_mutex_lock(&g_client_sockets_mutex) != 0) {
    return false;
  }
  bool role_is_live = false;
  for (size_t index = 0;
       index < g_client_lifecycle_sockets.size();
       ++index) {
    if (g_client_lifecycle_sockets[index] >= 0 &&
        g_client_process_roles[index] == process_role) {
      role_is_live = true;
      break;
    }
  }
  bool tracked = false;
  if (tracebox::ClientRegistrationRoleIsAvailableV2(
          process_role, g_process_role, role_is_live)) {
    for (size_t index = 0;
         index < g_client_lifecycle_sockets.size();
         ++index) {
      if (g_client_lifecycle_sockets[index] < 0) {
        if (ClientLifecycleDrain().TryAdmit()) {
          g_client_lifecycle_sockets[index] = socket_fd;
          g_client_process_roles[index] = process_role;
          g_client_ack_epochs[index] = 0;
          g_client_ack_operations[index] = 0;
          g_client_ack_statuses[index] = kPolicyProtocol;
          tracked = true;
        }
        break;
      }
    }
  }
  pthread_mutex_unlock(&g_client_sockets_mutex);
  return tracked;
}

void ClearClientLifecycleSlot(size_t index) {
  g_client_lifecycle_sockets[index] = -1;
  g_client_process_roles[index] = 0;
  g_client_ack_epochs[index] = 0;
  g_client_ack_operations[index] = 0;
  g_client_ack_statuses[index] = kPolicyProtocol;
}

bool ReleaseClientLifecycleSocket(int socket_fd,
                                  bool terminal,
                                  bool storage_durable) {
  if (pthread_mutex_lock(&g_client_sockets_mutex) != 0) {
    return false;
  }
  bool owned = false;
  bool accounted = false;
  for (size_t index = 0;
       index < g_client_lifecycle_sockets.size();
       ++index) {
    if (g_client_lifecycle_sockets[index] == socket_fd) {
      if (terminal) {
        shutdown(socket_fd, SHUT_RDWR);
        close(socket_fd);
      }
      ClearClientLifecycleSlot(index);
      owned = true;
      accounted = terminal
                      ? ClientLifecycleDrain().CompleteTerminal(
                            storage_durable)
                      : ClientLifecycleDrain().CancelAdmission(
                            storage_durable);
      break;
    }
  }
  pthread_cond_broadcast(&g_client_sockets_changed);
  pthread_mutex_unlock(&g_client_sockets_mutex);
  return owned && accounted;
}

bool CancelClientLifecycleAdmission(int socket_fd,
                                    bool storage_clean = true) {
  return ReleaseClientLifecycleSocket(
      socket_fd, false, storage_clean);
}

bool CompleteClientLifecycleTerminal(int socket_fd,
                                     bool storage_durable) {
  return ReleaseClientLifecycleSocket(
      socket_fd, true, storage_durable);
}

void FenceClientLifecycleSockets() {
  if (pthread_mutex_lock(&g_client_sockets_mutex) != 0) {
    return;
  }
  for (size_t index = 0;
       index < g_client_lifecycle_sockets.size();
       ++index) {
    const int entry = g_client_lifecycle_sockets[index];
    if (entry >= 0) {
      shutdown(entry, SHUT_RDWR);
    }
  }
  pthread_mutex_unlock(&g_client_sockets_mutex);
}

bool OpenClientLifecycleAdmission() {
  if (pthread_mutex_lock(&g_client_sockets_mutex) != 0) {
    return false;
  }
  bool empty = true;
  for (const int socket_fd : g_client_lifecycle_sockets) {
    if (socket_fd >= 0) {
      empty = false;
      break;
    }
  }
  const bool opened = empty && ClientLifecycleDrain().OpenAdmission();
  pthread_mutex_unlock(&g_client_sockets_mutex);
  return opened;
}

bool BeginClientLifecycleDrain() {
  if (pthread_mutex_lock(&g_client_sockets_mutex) != 0) {
    ClientLifecycleDrain().BeginDrain();
    return false;
  }
  ClientLifecycleDrain().BeginDrain();
  for (const int socket_fd : g_client_lifecycle_sockets) {
    if (socket_fd >= 0) {
      shutdown(socket_fd, SHUT_RDWR);
    }
  }
  pthread_mutex_unlock(&g_client_sockets_mutex);
  return true;
}

struct ClientLifecycle {
  int socket_fd;
  int journal_fd;
  ClientJournalRecord registered;
  tracebox::ClientConnectionModeV2 mode;
  std::atomic<bool> registration_complete{false};
};

timespec RealtimeDeadline(uint32_t timeout_millis) {
  timespec deadline{};
  clock_gettime(CLOCK_REALTIME, &deadline);
  const uint64_t nanoseconds =
      static_cast<uint64_t>(deadline.tv_nsec) +
      static_cast<uint64_t>(timeout_millis) * UINT64_C(1'000'000);
  deadline.tv_sec += static_cast<time_t>(
      nanoseconds / UINT64_C(1'000'000'000));
  deadline.tv_nsec =
      static_cast<long>(nanoseconds % UINT64_C(1'000'000'000));
  return deadline;
}

PolicyState CurrentPolicyState() {
  return PolicyState{
      g_policy_epoch.load(std::memory_order_acquire),
      g_policy_deny_mask.load(std::memory_order_acquire),
      g_policy_disabled.load(std::memory_order_acquire),
  };
}

PolicyState RequestedPolicyState(const PolicyControlMessage& message) {
  return PolicyState{
      message.policy_epoch,
      message.deny_mask,
      message.disabled != 0,
  };
}

void PopulateTargetMessage(const PolicyState& state,
                           PolicyControlMessage* target) {
  target->deny_mask = state.deny_mask;
  target->disabled = state.disabled ? 1 : 0;
}

bool UpdateClientAcknowledgement(int socket_fd,
                                 const PolicyControlMessage& message) {
  if (pthread_mutex_lock(&g_client_sockets_mutex) != 0) {
    return false;
  }
  bool updated = false;
  for (size_t index = 0;
       index < g_client_lifecycle_sockets.size();
       ++index) {
    if (g_client_lifecycle_sockets[index] == socket_fd) {
      g_client_ack_epochs[index] = message.policy_epoch;
      g_client_ack_operations[index] = message.operation;
      g_client_ack_statuses[index] =
          message.status < kPolicySuccess ||
                  message.status > kPolicyProtocol
              ? kPolicyProtocol
              : static_cast<uint32_t>(message.status);
      updated = true;
      break;
    }
  }
  pthread_cond_broadcast(&g_client_sockets_changed);
  pthread_mutex_unlock(&g_client_sockets_mutex);
  return updated;
}

int BroadcastPolicyAndWait(const PolicyControlMessage& target,
                           bool fence_unacknowledged = false) {
  std::array<int, kMaximumClients> participants{};
  participants.fill(-1);
  size_t participant_count = 0;
  bool partial = false;
  const uint64_t deadline =
      BlockingDeadline(DeadlineAfterMilliseconds(target.timeout_millis));
  if (deadline == 0 ||
      pthread_mutex_lock(&g_client_sockets_mutex) != 0) {
    return kPolicyProtocol;
  }
  for (size_t index = 0;
       index < g_client_lifecycle_sockets.size();
       ++index) {
    const int socket_fd = g_client_lifecycle_sockets[index];
    if (socket_fd < 0) {
      continue;
    }
    participants[participant_count++] = socket_fd;
    g_client_ack_epochs[index] = 0;
    g_client_ack_operations[index] = 0;
    g_client_ack_statuses[index] = kPolicyProtocol;
    if (SendPacket(socket_fd, &target, sizeof(target), deadline) !=
        RegistrationOutcome::kSuccess) {
      shutdown(socket_fd, SHUT_RDWR);
      partial = true;
    }
  }

  const timespec wait_deadline =
      RealtimeDeadline(target.timeout_millis);
  while (true) {
    bool all_resolved = true;
    for (size_t participant_index = 0;
         participant_index < participant_count;
         ++participant_index) {
      const int participant = participants[participant_index];
      bool found = false;
      bool acknowledged = false;
      for (size_t slot = 0;
           slot < g_client_lifecycle_sockets.size();
           ++slot) {
        if (g_client_lifecycle_sockets[slot] != participant) {
          continue;
        }
        found = true;
        acknowledged =
            g_client_ack_epochs[slot] == target.policy_epoch &&
            g_client_ack_operations[slot] == target.operation;
        if (acknowledged &&
            g_client_ack_statuses[slot] != kPolicySuccess) {
          partial = true;
        }
        break;
      }
      if (!found) {
        partial = true;
      } else if (!acknowledged) {
        all_resolved = false;
      }
    }
    if (all_resolved) {
      break;
    }
    const int wait_result = pthread_cond_timedwait(
        &g_client_sockets_changed,
        &g_client_sockets_mutex,
        &wait_deadline);
    if (wait_result == ETIMEDOUT) {
      partial = true;
      break;
    }
    if (wait_result != 0) {
      pthread_mutex_unlock(&g_client_sockets_mutex);
      return kPolicyProtocol;
    }
  }
  if (fence_unacknowledged) {
    for (size_t participant_index = 0;
         participant_index < participant_count;
         ++participant_index) {
      const int participant = participants[participant_index];
      bool acknowledged = false;
      for (size_t slot = 0;
           slot < g_client_lifecycle_sockets.size();
           ++slot) {
        if (g_client_lifecycle_sockets[slot] == participant) {
          acknowledged =
              g_client_ack_epochs[slot] == target.policy_epoch &&
              g_client_ack_operations[slot] == target.operation &&
              g_client_ack_statuses[slot] == kPolicySuccess;
          break;
        }
      }
      if (!acknowledged) {
        shutdown(participant, SHUT_RDWR);
      }
    }
  }
  pthread_mutex_unlock(&g_client_sockets_mutex);
  return partial ? kPolicyPartial : kPolicySuccess;
}

int ApplyHandlerPolicyOperation(const PolicyControlMessage& request,
                                PolicyControlMessage* target) {
  *target = request;
  target->message_type = kPolicyTargetType;
  target->status = kPolicySuccess;
  target->reserved = 0;
  if (request.operation == kPolicyPrepareOperation) {
    const PolicyState current = CurrentPolicyState();
    const PolicyState requested = RequestedPolicyState(request);
    const tracebox::PolicyDecisionV1 decision =
        tracebox::DecidePolicyPrepareV1(
            g_handler_prepared_policy, current, requested);
    if (decision == tracebox::PolicyDecisionV1::kAlreadyApplied) {
      return kPolicySuccess;
    }
    if (decision != tracebox::PolicyDecisionV1::kApply) {
      return kPolicyProtocol;
    }
    g_handler_prepared_policy.previous = current;
    g_handler_prepared_policy.target = requested;
    g_handler_prepared_policy.active = true;
    g_handler_prepared_policy.finalized_operation = 0;
    g_handler_transition_active.store(true, std::memory_order_release);
    if (!UpdatePolicyState(
            request.policy_epoch, true, UINT64_MAX, false)) {
      g_handler_prepared_policy.active = false;
      g_handler_transition_active.store(false, std::memory_order_release);
      return kPolicyProtocol;
    }
  } else if (request.operation == kPolicyCommitOperation) {
    const PolicyState current = CurrentPolicyState();
    const tracebox::PolicyDecisionV1 decision =
        tracebox::DecideHandlerPolicyCommitV1(
            g_handler_prepared_policy, current, request.policy_epoch);
    if (decision == tracebox::PolicyDecisionV1::kAlreadyApplied) {
      if (g_handler_prepared_policy.finalized_operation !=
              kPolicyCommitOperation ||
          g_handler_prepared_policy.target.epoch !=
              request.policy_epoch) {
        g_handler_prepared_policy.previous = current;
        g_handler_prepared_policy.target = current;
        g_handler_prepared_policy.finalized_operation =
            kPolicyCommitOperation;
      }
      PopulateTargetMessage(g_handler_prepared_policy.target, target);
      return kPolicySuccess;
    }
    if (decision != tracebox::PolicyDecisionV1::kApply) {
      return kPolicyProtocol;
    }
    const PolicyState prepared = g_handler_prepared_policy.target;
    PopulateTargetMessage(prepared, target);
    if (!UpdatePolicyState(prepared.epoch,
                           prepared.disabled,
                           prepared.deny_mask,
                           false)) {
      return kPolicyProtocol;
    }
  } else if (request.operation == kPolicyAbortOperation) {
    const tracebox::PolicyDecisionV1 decision =
        tracebox::DecidePolicyAbortV1(
            g_handler_prepared_policy,
            CurrentPolicyState(),
            request.policy_epoch);
    if (decision == tracebox::PolicyDecisionV1::kAlreadyApplied) {
      PopulateTargetMessage(g_handler_prepared_policy.previous, target);
      return kPolicySuccess;
    }
    if (decision == tracebox::PolicyDecisionV1::kNoOp) {
      return kPolicySuccess;
    }
    if (decision != tracebox::PolicyDecisionV1::kApply) {
      return kPolicyProtocol;
    }
    const PolicyState previous = g_handler_prepared_policy.previous;
    PopulateTargetMessage(previous, target);
    if (!UpdatePolicyState(previous.epoch,
                           previous.disabled,
                           previous.deny_mask,
                           false,
                           true)) {
      return kPolicyProtocol;
    }
  } else {
    return kPolicyProtocol;
  }
  return kPolicySuccess;
}

void FinalizeHandlerPolicyOperation(uint32_t operation) {
  g_handler_prepared_policy.active = false;
  g_handler_prepared_policy.finalized_operation = operation;
  g_handler_transition_active.store(false, std::memory_order_release);
}

struct PolicyCoordination {
  int requester_socket;
  PolicyControlMessage request;
};

void* CoordinatePolicy(void* argument) {
  auto* coordination = static_cast<PolicyCoordination*>(argument);
  PolicyControlMessage result = coordination->request;
  result.message_type = kPolicyResultType;
  result.status = kPolicyProtocol;
  if (pthread_mutex_lock(&g_policy_transition_mutex) == 0) {
    PolicyControlMessage target{};
    const int applied =
        ApplyHandlerPolicyOperation(coordination->request, &target);
    const bool final_operation =
        coordination->request.operation == kPolicyCommitOperation ||
        coordination->request.operation == kPolicyAbortOperation;
    result.status = applied == kPolicySuccess
                        ? BroadcastPolicyAndWait(target, final_operation)
                        : applied;
    if (applied == kPolicySuccess && final_operation) {
      if (result.status == kPolicyProtocol) {
        FenceClientLifecycleSockets();
      }
      // COMMIT and ABORT are authoritative decisions. Missing acknowledgements
      // fence the affected clients, but never make an already-applied decision
      // reversible or leave the handler unable to admit a recovery client.
      FinalizeHandlerPolicyOperation(coordination->request.operation);
    }
    if (coordination->request.operation == kPolicyPrepareOperation &&
        applied == kPolicySuccess &&
        result.status != kPolicySuccess &&
        g_handler_prepared_policy.active) {
      PolicyControlMessage abort_request = coordination->request;
      abort_request.operation = kPolicyAbortOperation;
      abort_request.timeout_millis = kAutomaticAbortTimeoutMillis;
      PolicyControlMessage abort_target{};
      if (ApplyHandlerPolicyOperation(abort_request, &abort_target) ==
          kPolicySuccess) {
        const int aborted =
            BroadcastPolicyAndWait(abort_target, true);
        if (aborted == kPolicyProtocol) {
          FenceClientLifecycleSockets();
        }
        FinalizeHandlerPolicyOperation(kPolicyAbortOperation);
      }
    }
    pthread_mutex_unlock(&g_policy_transition_mutex);
  }
  const uint64_t deadline = BlockingDeadline(
      DeadlineAfterMilliseconds(coordination->request.timeout_millis));
  if (deadline != 0) {
    static_cast<void>(SendPacket(coordination->requester_socket,
                                 &result,
                                 sizeof(result),
                                 deadline));
  }
  close(coordination->requester_socket);
  delete coordination;
  return nullptr;
}

bool StartPolicyCoordination(int socket_fd,
                             const PolicyControlMessage& request) {
  if (request.magic != kRegistrationMagic ||
      request.version != kRegistrationVersion ||
      request.message_type != kPolicyRequestType ||
      request.message_size != sizeof(request) ||
      request.policy_epoch == 0 ||
      request.disabled > 1 ||
      request.status != 0 ||
      request.reserved != 0 ||
      request.timeout_millis == 0 ||
      request.timeout_millis > kMaximumPolicyTimeoutMillis ||
      request.operation < kPolicyPrepareOperation ||
      request.operation > kPolicyAbortOperation) {
    return false;
  }
  const int requester_socket =
      fcntl(socket_fd, F_DUPFD_CLOEXEC, 0);
  if (requester_socket < 0) {
    return false;
  }
  auto* coordination =
      new (std::nothrow) PolicyCoordination{requester_socket, request};
  if (coordination == nullptr) {
    close(requester_socket);
    return false;
  }
  pthread_t thread;
  if (CreateRegisteredThread(&thread, CoordinatePolicy, coordination) != 0) {
    close(requester_socket);
    delete coordination;
    return false;
  }
  pthread_detach(thread);
  return true;
}

void* ClientLifecycleWatcher(void* argument) {
  auto* lifecycle = static_cast<ClientLifecycle*>(argument);
  // The registration thread retains descriptor access until its reply attempt
  // has completed. Shutdown may wake this admitted watcher in parallel, but it
  // must not close the descriptor early and make the integer reusable under
  // SendRegistrationReply().
  while (!lifecycle->registration_complete.load(
      std::memory_order_acquire)) {
    const timespec delay{0, kLifecycleActivationPollNanoseconds};
    timespec remaining = delay;
    while (nanosleep(&remaining, &remaining) != 0 && errno == EINTR) {
    }
  }
  uint16_t terminal_state = kClientStateDead;
  while (true) {
    pollfd descriptor{
        lifecycle->socket_fd, POLLIN | POLLHUP | POLLERR, 0};
    int result;
    do {
      result = poll(&descriptor, 1, -1);
    } while (result < 0 && errno == EINTR);
    if (result <= 0) {
      break;
    }
    if ((descriptor.revents & POLLIN) == 0 &&
        (descriptor.revents & (POLLHUP | POLLERR)) != 0) {
      break;
    }
    if ((descriptor.revents & POLLIN) == 0) {
      continue;
    }
    PolicyControlMessage message{};
    const uint64_t deadline =
        BlockingDeadline(DeadlineAfterMilliseconds(kRegistrationDeadlineMillis));
    bool peer_closed = false;
    const RegistrationOutcome receive =
        deadline == 0
            ? RegistrationOutcome::kDeadlineExceeded
            : ReceivePacket(lifecycle->socket_fd,
                            &message,
                            sizeof(message),
                            deadline,
                            &peer_closed);
    if (receive != RegistrationOutcome::kSuccess) {
      const bool disconnect_event =
          (descriptor.revents & (POLLHUP | POLLERR)) != 0;
      terminal_state = static_cast<uint16_t>(
          tracebox::ClientLifecycleReceiveFailureStateV1(
              peer_closed, disconnect_event));
      break;
    }
    if (message.magic != kRegistrationMagic ||
        message.version != kRegistrationVersion ||
        message.message_size != sizeof(message)) {
      terminal_state = kClientStateProtocolError;
      break;
    }
    if (message.message_type == kRegistrationConsumedType) {
      const auto* consumed =
          reinterpret_cast<const RegistrationConsumed*>(&message);
      if (lifecycle->mode ==
              tracebox::ClientConnectionModeV2::kCrashpad &&
          consumed->reserved == 0 &&
          std::memcmp(consumed->raw_artifact_id,
                      lifecycle->registered.raw_artifact_id,
                      sizeof(consumed->raw_artifact_id)) == 0) {
        terminal_state = kClientStateConsumed;
      } else {
        terminal_state = kClientStateProtocolError;
      }
      break;
    }
    if (message.message_type == kPolicyAckType) {
      if (!UpdateClientAcknowledgement(
              lifecycle->socket_fd, message)) {
        terminal_state = kClientStateProtocolError;
        break;
      }
      continue;
    }
    if (message.message_type == kPolicyRequestType) {
      if (!StartPolicyCoordination(
              lifecycle->socket_fd, message)) {
        terminal_state = kClientStateProtocolError;
        break;
      }
      continue;
    }
    terminal_state = kClientStateProtocolError;
    break;
  }
  if (lifecycle->mode ==
          tracebox::ClientConnectionModeV2::kCrashpad &&
      (terminal_state == kClientStateConsumed ||
       terminal_state == kClientStateDead)) {
    const uint32_t handoff_wait_millis =
        terminal_state == kClientStateConsumed
            ? kConsumedHandoffWaitMillis
            : (g_handler_draining.load(std::memory_order_acquire)
                   ? kShutdownHandoffWaitMillis
                   : kDeadClientHandoffWaitMillis);
    const HandoffOutcome handoff =
        MoveOnlyPendingReportToHandoff(
            lifecycle->registered.raw_artifact_id,
            handoff_wait_millis);
    if (handoff == HandoffOutcome::kMoved) {
      terminal_state = kClientStateConsumed;
    } else if (handoff == HandoffOutcome::kFailedOrAmbiguous) {
      terminal_state = kClientStateHandoffFailed;
    } else if (terminal_state == kClientStateConsumed) {
      terminal_state = kClientStateProtocolError;
    }
  }
  bool terminal_storage_durable = true;
  if (lifecycle->journal_fd >= 0) {
    terminal_storage_durable = FinishClientJournal(
        lifecycle->journal_fd, lifecycle->registered, terminal_state);
    close(lifecycle->journal_fd);
  }
  if (lifecycle->mode ==
      tracebox::ClientConnectionModeV2::kCrashpad) {
    g_capture_lease_held.store(false, std::memory_order_release);
  }
  static_cast<void>(
      CompleteClientLifecycleTerminal(
          lifecycle->socket_fd, terminal_storage_durable));
  delete lifecycle;
  return nullptr;
}

bool StartAdmittedClientLifecycleWatcher(
    int socket_fd,
    int journal_fd,
    const ClientJournalRecord& registered,
    tracebox::ClientConnectionModeV2 mode,
    ClientLifecycle** started_lifecycle) {
  if (started_lifecycle == nullptr) {
    return false;
  }
  *started_lifecycle = nullptr;
  if (mode == tracebox::ClientConnectionModeV2::kRejected ||
      (mode == tracebox::ClientConnectionModeV2::kCrashpad) !=
          (journal_fd >= 0)) {
    return false;
  }
  auto* lifecycle =
      new (std::nothrow) ClientLifecycle{
          socket_fd, journal_fd, registered, mode};
  if (lifecycle == nullptr) {
    return false;
  }
  pthread_t thread;
  if (CreateRegisteredThread(&thread, ClientLifecycleWatcher, lifecycle) !=
      0) {
    delete lifecycle;
    return false;
  }
  pthread_detach(thread);
  *started_lifecycle = lifecycle;
  return true;
}

RegistrationOutcome SendRegistrationReply(int socket_fd,
                                          RegistrationOutcome status,
                                          uint64_t deadline,
                                          bool include_handler_socket,
                                          tracebox::ClientConnectionModeV2
                                              granted_mode,
                                          const RegistrationRequest* request) {
  RegistrationReply reply{};
  reply.magic = kRegistrationMagic;
  reply.version = kRegistrationVersion;
  reply.message_type = kRegistrationReplyType;
  reply.message_size = sizeof(reply);
  reply.status = static_cast<int32_t>(status);
  reply.handler_pid = getpid();
  reply.handler_role = g_process_role;
  reply.handler_policy_epoch =
      g_policy_epoch.load(std::memory_order_acquire);
  std::memcpy(reply.handler_process_id,
              g_process_id.data(),
              g_process_id.size());
  reply.granted_mode = static_cast<uint32_t>(granted_mode);
  if (request != nullptr) {
    reply.client_role = request->process_role;
    reply.requested_policy_epoch = request->policy_epoch;
    std::memcpy(reply.client_process_id,
                request->process_id,
                sizeof(reply.client_process_id));
    std::memcpy(reply.raw_artifact_id,
                request->raw_artifact_id,
                sizeof(reply.raw_artifact_id));
  }

  iovec io{&reply, sizeof(reply)};
  std::array<char, CMSG_SPACE(sizeof(int))> control{};
  msghdr message{};
  message.msg_iov = &io;
  message.msg_iovlen = 1;
  const int shared_socket =
      g_shared_client_socket.load(std::memory_order_acquire);
  if (include_handler_socket &&
      granted_mode == tracebox::ClientConnectionModeV2::kCrashpad &&
      shared_socket >= 0) {
    message.msg_control = control.data();
    message.msg_controllen = control.size();
    cmsghdr* header = CMSG_FIRSTHDR(&message);
    header->cmsg_level = SOL_SOCKET;
    header->cmsg_type = SCM_RIGHTS;
    header->cmsg_len = CMSG_LEN(sizeof(int));
    std::memcpy(CMSG_DATA(header), &shared_socket, sizeof(shared_socket));
  }
  while (true) {
    const ssize_t sent =
        sendmsg(socket_fd, &message, MSG_NOSIGNAL | MSG_DONTWAIT);
    if (sent == static_cast<ssize_t>(sizeof(reply))) {
      return status == RegistrationOutcome::kSuccess
                 ? RegistrationOutcome::kSuccess
                 : status;
    }
    if (sent >= 0) {
      return RegistrationOutcome::kProtocolError;
    }
    if (errno == EINTR) {
      continue;
    }
    if (errno != EAGAIN && errno != EWOULDBLOCK) {
      return RegistrationOutcome::kUnavailable;
    }
    const RegistrationOutcome wait =
        WaitForSocket(socket_fd, POLLOUT, deadline);
    if (wait != RegistrationOutcome::kSuccess) {
      return wait;
    }
  }
}

bool HandleRegistration(int socket_fd) {
  const uint64_t deadline =
      BlockingDeadline(DeadlineAfterMilliseconds(kRegistrationDeadlineMillis));
  if (deadline == 0) {
    return false;
  }
  ucred credentials{};
  RegistrationRequest request{};
  RegistrationOutcome status = RegistrationOutcome::kProtocolError;
  bool crashpad_policy_permitted = false;
  bool fallback_policy_permitted = false;
  if (ReadPeerCredentials(socket_fd, &credentials) &&
      ReceivePacket(socket_fd, &request, sizeof(request), deadline) ==
          RegistrationOutcome::kSuccess &&
      ValidRequest(request, credentials)) {
    const uint64_t current_epoch =
        g_policy_epoch.load(std::memory_order_acquire);
    const bool transition_active =
        g_handler_transition_active.load(std::memory_order_acquire);
    const bool disabled =
        g_policy_disabled.load(std::memory_order_acquire);
    const uint64_t deny_mask =
        g_policy_deny_mask.load(std::memory_order_acquire);
    crashpad_policy_permitted =
        !transition_active && !disabled &&
        (deny_mask & kCrashSummaryCategory) == 0;
    fallback_policy_permitted =
        !transition_active && !disabled &&
        (deny_mask & (kEmergencyCategory | kRustPanicCategory)) !=
            (kEmergencyCategory | kRustPanicCategory);
    status = request.policy_epoch == current_epoch &&
                     (crashpad_policy_permitted ||
                      fallback_policy_permitted)
                 ? RegistrationOutcome::kSuccess
                 : RegistrationOutcome::kUnavailable;
  }
  if (status != RegistrationOutcome::kSuccess) {
    static_cast<void>(SendRegistrationReply(
        socket_fd,
        status,
        deadline,
        false,
        tracebox::ClientConnectionModeV2::kRejected,
        &request));
    return false;
  }
  // Admission is reserved before any lifecycle journal or raw handoff state is
  // created. Shutdown closes this gate under the same socket census lock, so
  // every accepted registration either becomes an accounted watcher or rolls
  // its admission back before the handler socket marker can disappear.
  if (!TrackClientLifecycleSocket(socket_fd, request.process_role)) {
    static_cast<void>(SendRegistrationReply(
        socket_fd,
        RegistrationOutcome::kUnavailable,
        deadline,
        false,
        tracebox::ClientConnectionModeV2::kRejected,
        &request));
    return false;
  }

  const auto requested_mode =
      static_cast<tracebox::ClientRegistrationRequestV2>(
          request.requested_mode);
  bool lease_acquired = false;
  if (requested_mode !=
          tracebox::ClientRegistrationRequestV2::kEmergencyRustOnly &&
      crashpad_policy_permitted) {
    bool expected_unarmed = false;
    lease_acquired = g_capture_lease_held.compare_exchange_strong(
        expected_unarmed, true, std::memory_order_acq_rel);
  }
  tracebox::ClientConnectionModeV2 granted_mode =
      tracebox::DecideClientConnectionModeV2(
          requested_mode,
          crashpad_policy_permitted,
          fallback_policy_permitted,
          lease_acquired);

  ClientJournalRecord registered{};
  int journal_fd = -1;
  if (granted_mode == tracebox::ClientConnectionModeV2::kCrashpad) {
    journal_fd = OpenClientJournal(request, credentials, &registered);
    if (journal_fd < 0) {
      g_capture_lease_held.store(false, std::memory_order_release);
      granted_mode =
          requested_mode ==
                      tracebox::ClientRegistrationRequestV2::
                          kCrashpadOrEmergencyRust &&
                  fallback_policy_permitted
              ? tracebox::ClientConnectionModeV2::kEmergencyRust
              : tracebox::ClientConnectionModeV2::kRejected;
    }
  }
  if (granted_mode == tracebox::ClientConnectionModeV2::kRejected) {
    if (lease_acquired) {
      g_capture_lease_held.store(false, std::memory_order_release);
    }
    static_cast<void>(CancelClientLifecycleAdmission(socket_fd));
    static_cast<void>(SendRegistrationReply(
        socket_fd,
        lease_acquired ? RegistrationOutcome::kSystemError
                       : RegistrationOutcome::kUnavailable,
        deadline,
        false,
        tracebox::ClientConnectionModeV2::kRejected,
        &request));
    return false;
  }

  ClientLifecycle* lifecycle = nullptr;
  if (!StartAdmittedClientLifecycleWatcher(
          socket_fd,
          journal_fd,
          registered,
          granted_mode,
          &lifecycle)) {
    bool failed_journal_storage_durable = true;
    if (journal_fd >= 0) {
      failed_journal_storage_durable = FinishClientJournal(
          journal_fd, registered, kClientStateProtocolError);
      close(journal_fd);
      g_capture_lease_held.store(false, std::memory_order_release);
      journal_fd = -1;
    }
    if (!failed_journal_storage_durable) {
      static_cast<void>(
          CancelClientLifecycleAdmission(socket_fd, false));
      static_cast<void>(SendRegistrationReply(
          socket_fd,
          RegistrationOutcome::kSystemError,
          deadline,
          false,
          tracebox::ClientConnectionModeV2::kRejected,
          &request));
      return false;
    }
    if (requested_mode ==
            tracebox::ClientRegistrationRequestV2::
                kCrashpadOrEmergencyRust &&
        fallback_policy_permitted &&
        granted_mode == tracebox::ClientConnectionModeV2::kCrashpad &&
        StartAdmittedClientLifecycleWatcher(
            socket_fd,
            -1,
            ClientJournalRecord{},
            tracebox::ClientConnectionModeV2::kEmergencyRust,
            &lifecycle)) {
      granted_mode =
          tracebox::ClientConnectionModeV2::kEmergencyRust;
    } else {
      static_cast<void>(CancelClientLifecycleAdmission(socket_fd));
      static_cast<void>(SendRegistrationReply(
          socket_fd,
          RegistrationOutcome::kUnavailable,
          deadline,
          false,
          tracebox::ClientConnectionModeV2::kRejected,
          &request));
      return false;
    }
  }

  const RegistrationOutcome reply = SendRegistrationReply(
      socket_fd,
      RegistrationOutcome::kSuccess,
      deadline,
      granted_mode ==
          tracebox::ClientConnectionModeV2::kCrashpad,
      granted_mode,
      &request);
  if (reply != RegistrationOutcome::kSuccess) {
    shutdown(socket_fd, SHUT_RDWR);
  }
  lifecycle->registration_complete.store(true, std::memory_order_release);
  return true;
}

void* ControlServer(void*) {
  while (g_handler_alive.load(std::memory_order_acquire)) {
    const int control_socket =
        g_control_socket.load(std::memory_order_acquire);
    if (control_socket < 0) {
      break;
    }
    const int client =
        accept4(control_socket, nullptr, nullptr, SOCK_CLOEXEC | SOCK_NONBLOCK);
    if (client < 0) {
      if (errno == EINTR) {
        continue;
      }
      break;
    }
    if (!HandleRegistration(client)) {
      close(client);
    }
  }
  return nullptr;
}

bool StartControlServer(const std::string& socket_path) {
  if (socket_path.size() >= g_control_socket_path.size()) {
    return false;
  }
  int directory_fd = -1;
  if (!OpenLockedHandlerSocketParent(
          socket_path, true, &directory_fd) ||
      !RemoveStaleHandlerSocketLocked(socket_path, directory_fd)) {
    if (directory_fd >= 0) {
      static_cast<void>(flock(directory_fd, LOCK_UN));
      close(directory_fd);
    }
    return false;
  }
  const int socket_fd = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0);
  if (socket_fd < 0) {
    static_cast<void>(flock(directory_fd, LOCK_UN));
    close(directory_fd);
    return false;
  }
  sockaddr_un address{};
  address.sun_family = AF_UNIX;
  std::memcpy(address.sun_path, socket_path.c_str(), socket_path.size() + 1);
  if (bind(socket_fd,
           reinterpret_cast<const sockaddr*>(&address),
           sizeof(address)) != 0 ||
      chmod(socket_path.c_str(), 0600) != 0 ||
      listen(socket_fd, kControlBacklog) != 0 ||
      fsync(directory_fd) != 0) {
    close(socket_fd);
    static_cast<void>(
        unlinkat(directory_fd, kHandlerSocketFileName, 0));
    static_cast<void>(fsync(directory_fd));
    static_cast<void>(flock(directory_fd, LOCK_UN));
    close(directory_fd);
    return false;
  }
  if (g_control_directory_fd.load(std::memory_order_acquire) >= 0 ||
      !OpenClientLifecycleAdmission()) {
    close(socket_fd);
    static_cast<void>(
        unlinkat(directory_fd, kHandlerSocketFileName, 0));
    static_cast<void>(fsync(directory_fd));
    static_cast<void>(flock(directory_fd, LOCK_UN));
    close(directory_fd);
    return false;
  }
  std::memset(
      g_control_socket_path.data(), 0, g_control_socket_path.size());
  std::memcpy(g_control_socket_path.data(),
              socket_path.data(),
              socket_path.size());
  g_control_socket_path_active.store(true, std::memory_order_release);
  // Retain the parent-directory lock for the full handler lifetime. A socket
  // path is the interprocess drain marker; stale cleanup must not unlink it
  // while an admitted watcher can still produce a terminal journal or handoff.
  g_control_directory_fd.store(directory_fd, std::memory_order_release);
  g_control_socket.store(socket_fd, std::memory_order_release);
  pthread_t thread;
  if (CreateRegisteredThread(&thread, ControlServer, nullptr) != 0) {
    const int owned_socket =
        g_control_socket.exchange(-1, std::memory_order_acq_rel);
    if (owned_socket >= 0) {
      close(owned_socket);
    }
    static_cast<void>(BeginClientLifecycleDrain());
    static_cast<void>(
        unlinkat(directory_fd, kHandlerSocketFileName, 0));
    static_cast<void>(fsync(directory_fd));
    g_control_socket_path_active.store(false, std::memory_order_release);
    std::memset(
        g_control_socket_path.data(), 0, g_control_socket_path.size());
    g_control_directory_fd.store(-1, std::memory_order_release);
    static_cast<void>(flock(directory_fd, LOCK_UN));
    close(directory_fd);
    return false;
  }
  pthread_detach(thread);
  return true;
}

RegistrationOutcome SendRegistrationRequest(
    int socket_fd,
    const RegistrationRequest& request,
    uint64_t deadline) {
  return SendPacket(socket_fd, &request, sizeof(request), deadline);
}

RegistrationOutcome ReceiveRegistrationReply(int socket_fd,
                                             int* handler_socket,
                                             pid_t* handler_pid,
                                             tracebox::ClientConnectionModeV2*
                                                 granted_mode,
                                             const RegistrationRequest& request,
                                             uint64_t deadline) {
  ucred credentials{};
  if (!ReadPeerCredentials(socket_fd, &credentials)) {
    return RegistrationOutcome::kProtocolError;
  }
  RegistrationReply reply{};
  iovec io{&reply, sizeof(reply)};
  std::array<char, CMSG_SPACE(sizeof(int))> control{};
  msghdr message{};
  message.msg_iov = &io;
  message.msg_iovlen = 1;
  message.msg_control = control.data();
  message.msg_controllen = control.size();
  while (true) {
    const ssize_t received = recvmsg(
        socket_fd, &message, MSG_DONTWAIT | MSG_TRUNC);
    if (received == static_cast<ssize_t>(sizeof(reply))) {
      break;
    }
    if (received >= 0) {
      return RegistrationOutcome::kProtocolError;
    }
    if (errno == EINTR) {
      continue;
    }
    if (errno != EAGAIN && errno != EWOULDBLOCK) {
      return RegistrationOutcome::kUnavailable;
    }
    const RegistrationOutcome wait =
        WaitForSocket(socket_fd, POLLIN, deadline);
    if (wait != RegistrationOutcome::kSuccess) {
      return wait;
    }
  }
  const cmsghdr* header = CMSG_FIRSTHDR(&message);
  int received_handler_socket = -1;
  if (header != nullptr &&
      header->cmsg_level == SOL_SOCKET &&
      header->cmsg_type == SCM_RIGHTS &&
      header->cmsg_len >= CMSG_LEN(sizeof(received_handler_socket))) {
    std::memcpy(&received_handler_socket,
                CMSG_DATA(header),
                sizeof(received_handler_socket));
  }
  std::array<uint8_t, kProcessIdentityBytes> handler_process_id{};
  std::memcpy(handler_process_id.data(),
              reply.handler_process_id,
              handler_process_id.size());
  const bool valid_status =
      reply.status >=
          static_cast<int32_t>(RegistrationOutcome::kSuccess) &&
      reply.status <=
          static_cast<int32_t>(RegistrationOutcome::kSystemError);
  if (reply.magic != kRegistrationMagic ||
      reply.version != kRegistrationVersion ||
      reply.message_type != kRegistrationReplyType ||
      reply.message_size != sizeof(reply) ||
      reply.handler_pid != credentials.pid ||
      reply.handler_role != 2 ||
      IsAllZero(handler_process_id) ||
      reply.granted_mode >
          static_cast<uint32_t>(
              tracebox::ClientConnectionModeV2::kEmergencyRust) ||
      !valid_status ||
      (message.msg_flags & (MSG_CTRUNC | MSG_TRUNC)) != 0) {
    if (received_handler_socket >= 0) {
      close(received_handler_socket);
    }
    return RegistrationOutcome::kProtocolError;
  }
  if (reply.status != static_cast<int32_t>(RegistrationOutcome::kSuccess)) {
    if (received_handler_socket >= 0) {
      close(received_handler_socket);
    }
    if (reply.granted_mode != static_cast<uint32_t>(
                                  tracebox::ClientConnectionModeV2::
                                      kRejected)) {
      return RegistrationOutcome::kProtocolError;
    }
    return static_cast<RegistrationOutcome>(reply.status);
  }
  const auto mode =
      static_cast<tracebox::ClientConnectionModeV2>(
          reply.granted_mode);
  const auto requested =
      static_cast<tracebox::ClientRegistrationRequestV2>(
          request.requested_mode);
  const bool request_allows_mode =
      (mode == tracebox::ClientConnectionModeV2::kCrashpad &&
       requested !=
           tracebox::ClientRegistrationRequestV2::kEmergencyRustOnly) ||
      (mode == tracebox::ClientConnectionModeV2::kEmergencyRust &&
       requested !=
           tracebox::ClientRegistrationRequestV2::kCrashpadRequired);
  const bool descriptor_matches_mode =
      (mode == tracebox::ClientConnectionModeV2::kCrashpad &&
       header != nullptr &&
       header->cmsg_len == CMSG_LEN(sizeof(received_handler_socket)) &&
       received_handler_socket >= 0) ||
      (mode == tracebox::ClientConnectionModeV2::kEmergencyRust &&
       header == nullptr && received_handler_socket < 0);
  if (reply.handler_policy_epoch != request.policy_epoch ||
      reply.requested_policy_epoch != request.policy_epoch ||
      reply.client_role != request.process_role ||
      std::memcmp(reply.client_process_id,
                  request.process_id,
                  sizeof(reply.client_process_id)) != 0 ||
      std::memcmp(reply.raw_artifact_id,
                  request.raw_artifact_id,
                  sizeof(reply.raw_artifact_id)) != 0 ||
      !request_allows_mode ||
      !descriptor_matches_mode) {
    if (received_handler_socket >= 0) {
      close(received_handler_socket);
    }
    return RegistrationOutcome::kProtocolError;
  }
  if (mode == tracebox::ClientConnectionModeV2::kCrashpad) {
    *handler_socket = received_handler_socket;
  }
  *handler_pid = reply.handler_pid;
  *granted_mode = mode;
  return RegistrationOutcome::kSuccess;
}

int ApplyClientPolicyTarget(const PolicyControlMessage& target) {
  if (target.operation == kPolicyPrepareOperation) {
    const PolicyState current = CurrentPolicyState();
    const PolicyState requested = RequestedPolicyState(target);
    const tracebox::PolicyDecisionV1 decision =
        tracebox::DecidePolicyPrepareV1(
            g_client_prepared_policy, current, requested);
    if (decision == tracebox::PolicyDecisionV1::kAlreadyApplied) {
      return kPolicySuccess;
    }
    if (decision != tracebox::PolicyDecisionV1::kApply) {
      return kPolicyProtocol;
    }
    g_client_prepared_policy.previous = current;
    g_client_prepared_policy.target = requested;
    g_client_prepared_policy.active = true;
    g_client_prepared_policy.finalized_operation = 0;
    // PREPARE must fence new captures without tearing down Crashpad's shared
    // handler socket. Closing that transport makes the handler exit before
    // this client can acknowledge the transaction.
    if (!UpdatePolicyState(
            target.policy_epoch, true, UINT64_MAX, false)) {
      g_client_prepared_policy.active = false;
      return kPolicyProtocol;
    }
    return kPolicySuccess;
  }
  if (target.operation == kPolicyCommitOperation) {
    const PolicyState current = CurrentPolicyState();
    const PolicyState requested = RequestedPolicyState(target);
    const tracebox::PolicyDecisionV1 decision =
        tracebox::DecideClientPolicyCommitV1(
            g_client_prepared_policy, current, requested);
    if (decision == tracebox::PolicyDecisionV1::kAlreadyApplied) {
      if (g_client_prepared_policy.finalized_operation !=
              kPolicyCommitOperation ||
          !tracebox::SamePolicyTargetV1(
              g_client_prepared_policy, requested)) {
        g_client_prepared_policy.previous = current;
        g_client_prepared_policy.target = current;
        g_client_prepared_policy.finalized_operation =
            kPolicyCommitOperation;
      }
      return kPolicySuccess;
    }
    if (decision != tracebox::PolicyDecisionV1::kApply) {
      return kPolicyProtocol;
    }
    const PolicyState prepared = g_client_prepared_policy.target;
    if (!UpdatePolicyState(prepared.epoch,
                           prepared.disabled,
                           prepared.deny_mask,
                           false)) {
      return kPolicyProtocol;
    }
    g_client_prepared_policy.active = false;
    g_client_prepared_policy.finalized_operation = kPolicyCommitOperation;
    return kPolicySuccess;
  }
  if (target.operation == kPolicyAbortOperation) {
    const PolicyState current = CurrentPolicyState();
    const tracebox::PolicyDecisionV1 decision =
        tracebox::DecidePolicyAbortV1(
            g_client_prepared_policy, current, target.policy_epoch);
    if (decision == tracebox::PolicyDecisionV1::kAlreadyApplied) {
      return kPolicySuccess;
    }
    if (decision == tracebox::PolicyDecisionV1::kNoOp) {
      g_client_prepared_policy.previous = current;
      g_client_prepared_policy.target = RequestedPolicyState(target);
      g_client_prepared_policy.finalized_operation = kPolicyAbortOperation;
      return kPolicySuccess;
    }
    if (decision != tracebox::PolicyDecisionV1::kApply) {
      return kPolicyProtocol;
    }
    const PolicyState previous = g_client_prepared_policy.previous;
    if (!UpdatePolicyState(previous.epoch,
                           previous.disabled,
                           previous.deny_mask,
                           false,
                           true)) {
      return kPolicyProtocol;
    }
    g_client_prepared_policy.active = false;
    g_client_prepared_policy.finalized_operation = kPolicyAbortOperation;
    return kPolicySuccess;
  }
  return kPolicyProtocol;
}

bool HandleClientPolicyMessage(int socket_fd,
                               const PolicyControlMessage& message) {
  if (message.magic != kRegistrationMagic ||
      message.version != kRegistrationVersion ||
      message.message_size != sizeof(message) ||
      message.reserved != 0) {
    return false;
  }
  if (message.message_type == kPolicyTargetType) {
    if (message.policy_epoch == 0 || message.disabled > 1 ||
        message.status != kPolicySuccess ||
        message.timeout_millis == 0 ||
        message.timeout_millis > kMaximumPolicyTimeoutMillis ||
        message.operation < kPolicyPrepareOperation ||
        message.operation > kPolicyAbortOperation) {
      return false;
    }
    PolicyControlMessage acknowledgement = message;
    acknowledgement.message_type = kPolicyAckType;
    acknowledgement.status = ApplyClientPolicyTarget(message);
    const uint64_t deadline = BlockingDeadline(
        DeadlineAfterMilliseconds(message.timeout_millis));
    return deadline != 0 &&
           SendPacket(socket_fd,
                      &acknowledgement,
                      sizeof(acknowledgement),
                      deadline) == RegistrationOutcome::kSuccess;
  }
  if (message.message_type == kPolicyResultType) {
    if (message.policy_epoch == 0 ||
        message.operation < kPolicyPrepareOperation ||
        message.operation > kPolicyAbortOperation ||
        message.status < kPolicySuccess ||
        message.status > kPolicyProtocol) {
      return false;
    }
    if (pthread_mutex_lock(&g_policy_result_mutex) != 0) {
      return false;
    }
    g_policy_result_epoch = message.policy_epoch;
    g_policy_result_operation = message.operation;
    g_policy_result_status =
        message.status < kPolicySuccess || message.status > kPolicyProtocol
            ? kPolicyProtocol
            : message.status;
    g_policy_result_ready = true;
    pthread_cond_broadcast(&g_policy_result_changed);
    pthread_mutex_unlock(&g_policy_result_mutex);
    return true;
  }
  return false;
}

void* HandlerDeathWatcher(void* argument) {
  const int socket_fd = static_cast<int>(reinterpret_cast<intptr_t>(argument));
  while (true) {
    pollfd descriptor{socket_fd, POLLIN | POLLHUP | POLLERR, 0};
    int result;
    do {
      result = poll(&descriptor, 1, -1);
    } while (result < 0 && errno == EINTR);
    if (result <= 0) {
      break;
    }
    if ((descriptor.revents & POLLIN) == 0 &&
        (descriptor.revents & (POLLHUP | POLLERR)) != 0) {
      break;
    }
    if ((descriptor.revents & POLLIN) == 0) {
      continue;
    }
    PolicyControlMessage message{};
    const uint64_t deadline =
        BlockingDeadline(DeadlineAfterMilliseconds(kRegistrationDeadlineMillis));
    if (deadline == 0 ||
        ReceivePacket(
            socket_fd, &message, sizeof(message), deadline) !=
            RegistrationOutcome::kSuccess ||
        !HandleClientPolicyMessage(socket_fd, message)) {
      break;
    }
  }
  int expected = socket_fd;
  if (g_registration_socket.compare_exchange_strong(
          expected, -1, std::memory_order_acq_rel)) {
    FenceDisconnectedPolicyParticipant();
    g_policy_participant_alive.store(false, std::memory_order_release);
    close(socket_fd);
    if (pthread_mutex_lock(&g_policy_result_mutex) == 0) {
      g_policy_result_status = kPolicyProtocol;
      g_policy_result_ready = true;
      pthread_cond_broadcast(&g_policy_result_changed);
      pthread_mutex_unlock(&g_policy_result_mutex);
    }
  }
  return nullptr;
}

bool StartDeathWatcher(int socket_fd) {
  pthread_t thread;
  if (CreateRegisteredThread(
          &thread,
          HandlerDeathWatcher,
          reinterpret_cast<void*>(static_cast<intptr_t>(socket_fd))) != 0) {
    return false;
  }
  pthread_detach(thread);
  return true;
}

RegistrationOutcome ConnectControlSocket(const std::string& socket_path,
                                         const RegistrationRequest& request,
                                         int* handler_socket,
                                         pid_t* handler_pid,
                                         tracebox::ClientConnectionModeV2*
                                             granted_mode,
                                         int* control_connection) {
  const uint64_t overall_deadline =
      DeadlineAfterMilliseconds(kRegistrationDeadlineMillis);
  if (overall_deadline == 0) {
    return RegistrationOutcome::kSystemError;
  }
  const uint64_t deadline = BlockingDeadline(overall_deadline);
  while (RemainingPollMilliseconds(deadline) > 0) {
    const int socket_fd =
        socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
    if (socket_fd < 0) {
      return RegistrationOutcome::kSystemError;
    }
    sockaddr_un address{};
    address.sun_family = AF_UNIX;
    std::memcpy(address.sun_path, socket_path.c_str(), socket_path.size() + 1);
    bool connected =
        connect(socket_fd,
                reinterpret_cast<const sockaddr*>(&address),
                sizeof(address)) == 0;
    int connect_error = connected ? 0 : errno;
    if (!connected && connect_error == EINPROGRESS) {
      const RegistrationOutcome wait =
          WaitForSocket(socket_fd, POLLOUT, deadline);
      if (wait == RegistrationOutcome::kSuccess) {
        socklen_t error_size = sizeof(connect_error);
        if (getsockopt(socket_fd,
                       SOL_SOCKET,
                       SO_ERROR,
                       &connect_error,
                       &error_size) != 0) {
          connect_error = errno;
        }
        connected = connect_error == 0;
      } else {
        close(socket_fd);
        return wait;
      }
    }
    if (connected) {
      RegistrationOutcome outcome =
          SendRegistrationRequest(socket_fd, request, deadline);
      if (outcome == RegistrationOutcome::kSuccess) {
        outcome = ReceiveRegistrationReply(
            socket_fd,
            handler_socket,
            handler_pid,
            granted_mode,
            request,
            deadline);
      }
      if (outcome == RegistrationOutcome::kSuccess) {
        *control_connection = socket_fd;
      } else {
        close(socket_fd);
      }
      return outcome;
    }
    close(socket_fd);
    if (connect_error != ENOENT && connect_error != ECONNREFUSED &&
        connect_error != EAGAIN) {
      return RegistrationOutcome::kUnavailable;
    }
    const RegistrationOutcome retry = WaitBeforeConnectRetry(deadline);
    if (retry != RegistrationOutcome::kSuccess) {
      return retry;
    }
  }
  return RegistrationOutcome::kDeadlineExceeded;
}

bool DirectoryWithinBounds(const char* path,
                           std::string_view required_suffix,
                           size_t maximum_files,
                           uint64_t maximum_total_bytes,
                           uint64_t maximum_file_bytes,
                           size_t* file_count) {
  const int directory_fd =
      open(path, O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
  if (directory_fd < 0) {
    return false;
  }
  const int scan_fd = dup(directory_fd);
  DIR* directory = scan_fd < 0 ? nullptr : fdopendir(scan_fd);
  size_t count = 0;
  uint64_t bytes = 0;
  bool valid = directory != nullptr;
  if (directory != nullptr) {
    while (dirent* entry = readdir(directory)) {
      const std::string_view name(entry->d_name);
      if (name == "." || name == "..") {
        continue;
      }
      if (name.size() < required_suffix.size() ||
          name.substr(name.size() - required_suffix.size()) !=
              required_suffix ||
          name.find('/') != std::string_view::npos) {
        valid = false;
        break;
      }
      const int file_fd = openat(
          directory_fd,
          entry->d_name,
          O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
      struct stat status {};
      const bool regular =
          file_fd >= 0 && fstat(file_fd, &status) == 0 &&
          S_ISREG(status.st_mode) && status.st_nlink == 1 &&
          status.st_size >= 0;
      if (file_fd >= 0) {
        close(file_fd);
      }
      if (!regular ||
          static_cast<uint64_t>(status.st_size) > maximum_file_bytes ||
          count >= maximum_files ||
          static_cast<uint64_t>(status.st_size) >
              maximum_total_bytes - bytes) {
        valid = false;
        break;
      }
      ++count;
      bytes += static_cast<uint64_t>(status.st_size);
    }
    closedir(directory);
  } else if (scan_fd >= 0) {
    close(scan_fd);
  }
  close(directory_fd);
  if (valid && file_count != nullptr) {
    *file_count = count;
  }
  return valid;
}

bool CaptureStagingCanGrantLease() {
  size_t pending = 0;
  size_t handoff = 0;
  size_t journals = 0;
  return DirectoryWithinBounds(
             g_pending_directory.data(),
             ".dmp",
             1,
             kMaximumNativeRawStagingBytes,
             kMaximumNativeRawStagingBytes,
             &pending) &&
         pending == 0 &&
         DirectoryWithinBounds(
             g_handler_handoff_directory.data(),
             ".dmp",
             1,
             kMaximumNativeRawStagingBytes,
             kMaximumNativeRawStagingBytes,
             &handoff) &&
         handoff == 0 &&
         DirectoryWithinBounds(
             g_client_journal_directory.data(),
             ".tbclient",
             kMaximumClientJournalFiles,
             kMaximumClientJournalBytes,
             sizeof(ClientJournalRecord) * 2,
             &journals) &&
         journals < kMaximumClientJournalFiles;
}

std::string HexIdentity(const uint8_t bytes[kProcessIdentityBytes]) {
  constexpr char kHex[] = "0123456789abcdef";
  std::string encoded(kProcessIdentityBytes * 2, '0');
  for (size_t index = 0; index < kProcessIdentityBytes; ++index) {
    encoded[index * 2] = kHex[bytes[index] >> 4];
    encoded[index * 2 + 1] = kHex[bytes[index] & 0x0f];
  }
  return encoded;
}

bool IsCanonicalCrashpadReportName(std::string_view name,
                                   std::string_view suffix) {
  if (!name.ends_with(suffix) || name.size() != 36 + suffix.size()) {
    return false;
  }
  const std::string_view stem = name.substr(0, 36);
  for (size_t index = 0; index < stem.size(); ++index) {
    if (index == 8 || index == 13 || index == 18 || index == 23) {
      if (stem[index] != '-') {
        return false;
      }
    } else if (!((stem[index] >= '0' && stem[index] <= '9') ||
                 (stem[index] >= 'a' && stem[index] <= 'f'))) {
      return false;
    }
  }
  return true;
}

HandoffOutcome MoveOnlyPendingReportToHandoff(
    const uint8_t raw_artifact_id[kProcessIdentityBytes],
    uint32_t wait_millis) {
  const int pending_directory =
      open(g_pending_directory.data(), O_RDONLY | O_DIRECTORY | O_CLOEXEC);
  const int handoff_directory =
      open(g_handler_handoff_directory.data(),
           O_RDONLY | O_DIRECTORY | O_CLOEXEC);
  if (pending_directory < 0 || handoff_directory < 0) {
    if (pending_directory >= 0) close(pending_directory);
    if (handoff_directory >= 0) close(handoff_directory);
    return HandoffOutcome::kFailedOrAmbiguous;
  }

  const uint64_t deadline = DeadlineAfterMilliseconds(wait_millis);
  while (true) {
    const int scan_fd = dup(pending_directory);
    DIR* directory = scan_fd < 0 ? nullptr : fdopendir(scan_fd);
    std::string report_stem;
    std::string pending_name;
    std::string metadata_name;
    std::vector<std::string> entries;
    bool invalid = directory == nullptr;
    if (directory != nullptr) {
      while (dirent* entry = readdir(directory)) {
        const std::string_view name(entry->d_name);
        if (name == "." || name == "..") {
          continue;
        }
        if (name.find('/') != std::string_view::npos ||
            entries.size() == 3) {
          invalid = true;
          break;
        }
        entries.emplace_back(entry->d_name);
      }
      closedir(directory);
    } else if (scan_fd >= 0) {
      close(scan_fd);
    }

    size_t dump_count = 0;
    size_t metadata_count = 0;
    size_t lock_count = 0;
    for (const std::string& entry : entries) {
      std::string_view suffix;
      if (IsCanonicalCrashpadReportName(entry, ".dmp")) {
        suffix = ".dmp";
        pending_name = entry;
        ++dump_count;
      } else if (IsCanonicalCrashpadReportName(entry, ".meta")) {
        suffix = ".meta";
        metadata_name = entry;
        ++metadata_count;
      } else if (IsCanonicalCrashpadReportName(entry, ".lock")) {
        suffix = ".lock";
        ++lock_count;
      } else {
        invalid = true;
        break;
      }
      const std::string stem =
          entry.substr(0, entry.size() - suffix.size());
      if (report_stem.empty()) {
        report_stem = stem;
      } else if (report_stem != stem) {
        invalid = true;
        break;
      }
    }
    if (invalid || dump_count > 1 || metadata_count > 1 || lock_count > 1) {
      close(pending_directory);
      close(handoff_directory);
      return HandoffOutcome::kFailedOrAmbiguous;
    }
    if (dump_count == 1) {
      struct stat metadata_status {};
      const bool metadata_ready =
          metadata_count == 1 &&
          fstatat(pending_directory,
                  metadata_name.c_str(),
                  &metadata_status,
                  AT_SYMLINK_NOFOLLOW) == 0 &&
          S_ISREG(metadata_status.st_mode) &&
          metadata_status.st_nlink == 1 &&
          metadata_status.st_size == kCrashpadMetadataBytes;
      if (!metadata_ready || lock_count != 0) {
        if (deadline == 0 || RemainingPollMilliseconds(deadline) == 0) {
          close(pending_directory);
          close(handoff_directory);
          return HandoffOutcome::kFailedOrAmbiguous;
        }
        const timespec delay{0, kHandoffPollNanoseconds};
        timespec remaining = delay;
        while (nanosleep(&remaining, &remaining) != 0 && errno == EINTR) {
        }
        continue;
      }
      const int report_fd =
          openat(pending_directory,
                 pending_name.c_str(),
                 O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
      struct stat report_status {};
      const bool report_ready =
          report_fd >= 0 &&
          fstat(report_fd, &report_status) == 0 &&
          S_ISREG(report_status.st_mode) &&
          report_status.st_nlink == 1 &&
          report_status.st_size > 0 &&
          static_cast<uint64_t>(report_status.st_size) <=
              kMaximumNativeRawStagingBytes &&
          fsync(report_fd) == 0;
      if (report_fd >= 0) {
        close(report_fd);
      }
      if (!report_ready) {
        close(pending_directory);
        close(handoff_directory);
        return HandoffOutcome::kFailedOrAmbiguous;
      }
      const std::string destination =
          HexIdentity(raw_artifact_id) + ".dmp";
      bool moved = false;
#if defined(SYS_renameat2)
      moved =
          syscall(SYS_renameat2,
                  pending_directory,
                  pending_name.c_str(),
                  handoff_directory,
                  destination.c_str(),
                  1 /* RENAME_NOREPLACE */) == 0;
#endif
      if (moved) {
        // Persist the destination before destroying the only remaining
        // Crashpad database ownership record for this report.
        moved = fsync(handoff_directory) == 0;
      }
      if (moved) {
        moved =
            unlinkat(pending_directory, metadata_name.c_str(), 0) == 0 &&
            fsync(pending_directory) == 0;
      }
      close(pending_directory);
      close(handoff_directory);
      return moved ? HandoffOutcome::kMoved
                   : HandoffOutcome::kFailedOrAmbiguous;
    }

    if (!entries.empty() &&
        !(dump_count == 0 &&
          metadata_count == 1 &&
          lock_count <= 1)) {
      close(pending_directory);
      close(handoff_directory);
      return HandoffOutcome::kFailedOrAmbiguous;
    }
    if (deadline == 0 || RemainingPollMilliseconds(deadline) == 0) {
      close(pending_directory);
      close(handoff_directory);
      return entries.empty() ? HandoffOutcome::kNoReport
                             : HandoffOutcome::kFailedOrAmbiguous;
    }
    const timespec delay{0, kHandoffPollNanoseconds};
    timespec remaining = delay;
    while (nanosleep(&remaining, &remaining) != 0 && errno == EINTR) {
    }
  }
}

void FenceCrashpadClient() {
  if (!g_crashpad_socket_fenced.exchange(
          true, std::memory_order_acq_rel)) {
    int handler_socket = -1;
    if (crashpad::CrashpadClient::GetHandlerSocket(
            &handler_socket, nullptr)) {
      // Crashpad retains ownership; shutdown fences use without making the
      // retained descriptor number available for unrelated reuse.
      shutdown(handler_socket, SHUT_RDWR);
    }
  }
  g_handler_alive.store(false, std::memory_order_release);
  g_handler_pid.store(-1, std::memory_order_release);
  g_raw_artifact_armed.store(false, std::memory_order_release);
}

void FenceDisconnectedPolicyParticipant() {
  if (pthread_mutex_lock(&g_policy_mutex) != 0) {
    // pthread mutex failure is unrecoverable, but the independent atomic gates
    // still deny new emergency capture and disable the Rust slot fail-closed.
    g_policy_disabled.store(true, std::memory_order_release);
    g_policy_deny_mask.store(UINT64_MAX, std::memory_order_release);
    static_cast<void>(tb_android_configure_panic_slot_v1(
        g_rust_panic_fd.load(std::memory_order_acquire),
        g_policy_epoch.load(std::memory_order_acquire),
        g_process_role,
        0));
    FenceCrashpadClient();
    return;
  }

  const PolicyState fenced =
      tracebox::PolicyParticipantDisconnectFenceV1(CurrentPolicyState());
  // Odd generations prevent a new emergency writer from crossing the fence.
  // Writers admitted before EOF may finish; every later BeginCapture observes
  // either the odd generation or the disabled state.
  g_policy_generation.fetch_add(1, std::memory_order_acq_rel);
  g_policy_disabled.store(fenced.disabled, std::memory_order_release);
  g_policy_deny_mask.store(fenced.deny_mask, std::memory_order_release);
  static_cast<void>(tb_android_configure_panic_slot_v1(
      g_rust_panic_fd.load(std::memory_order_acquire),
      fenced.epoch,
      g_process_role,
      0));
  FenceCrashpadClient();
  g_policy_generation.fetch_add(1, std::memory_order_release);
  pthread_mutex_unlock(&g_policy_mutex);
}

bool RetireDrainedHandlerSocketMarker() {
  if (!g_control_socket_path_active.load(std::memory_order_acquire)) {
    return g_control_directory_fd.load(std::memory_order_acquire) < 0;
  }
  const int directory_fd =
      g_control_directory_fd.load(std::memory_order_acquire);
  if (directory_fd < 0 ||
      std::string(g_control_socket_path.data()).empty()) {
    return false;
  }

  int unlink_result;
  do {
    unlink_result =
        unlinkat(directory_fd, kHandlerSocketFileName, 0);
  } while (unlink_result != 0 && errno == EINTR);
  if ((unlink_result != 0 && errno != ENOENT) ||
      fsync(directory_fd) != 0) {
    return false;
  }

  g_control_socket_path_active.store(false, std::memory_order_release);
  std::memset(
      g_control_socket_path.data(), 0, g_control_socket_path.size());
  const int owned_directory =
      g_control_directory_fd.exchange(-1, std::memory_order_acq_rel);
  if (owned_directory != directory_fd) {
    return false;
  }
  static_cast<void>(flock(directory_fd, LOCK_UN));
  close(directory_fd);
  return true;
}

bool ShutdownHandlerTransport(uint32_t timeout_millis) {
  if (timeout_millis == 0) {
    return false;
  }
  timeout_millis =
      std::min(timeout_millis, kMaximumHandlerDrainTimeoutMillis);
  const uint64_t deadline =
      DeadlineAfterMilliseconds(static_cast<int>(timeout_millis));
  const timespec lock_deadline = RealtimeDeadline(timeout_millis);
  if (deadline == 0 ||
      pthread_mutex_timedlock(
          &g_handler_shutdown_mutex, &lock_deadline) != 0) {
    return false;
  }

  FenceDisconnectedPolicyParticipant();
  const int registration =
      g_registration_socket.exchange(-1, std::memory_order_acq_rel);
  g_policy_participant_alive.store(false, std::memory_order_release);
  if (registration >= 0) {
    shutdown(registration, SHUT_RDWR);
    close(registration);
  }
  const int control = g_control_socket.exchange(-1, std::memory_order_acq_rel);
  if (control >= 0) {
    shutdown(control, SHUT_RDWR);
    close(control);
  }
  g_handler_draining.store(true, std::memory_order_release);
  const bool drain_started = BeginClientLifecycleDrain();
  const int remaining_millis = RemainingPollMilliseconds(deadline);
  const bool watchers_drained =
      drain_started &&
      ClientLifecycleDrain().WaitFor(
          std::chrono::milliseconds(remaining_millis));

  // Stop Crashpad even on a bounded drain timeout. The retained filesystem
  // socket marker and directory lock continue to advertise that a watcher may
  // still complete a terminal journal or handoff; RunHandler can then return
  // and retry the drain from its finally path.
  const int shared =
      g_shared_client_socket.exchange(-1, std::memory_order_acq_rel);
  if (shared >= 0) {
    shutdown(shared, SHUT_RDWR);
    close(shared);
  }
  const int server =
      g_handler_server_socket.exchange(-1, std::memory_order_acq_rel);
  if (server >= 0) {
    shutdown(server, SHUT_RDWR);
    close(server);
  }
  const bool marker_retired =
      watchers_drained && RetireDrainedHandlerSocketMarker();
  pthread_mutex_unlock(&g_handler_shutdown_mutex);
  return watchers_drained && marker_retired;
}

bool MarkRawArtifactConsumed() {
  if (!g_raw_artifact_armed.exchange(false, std::memory_order_acq_rel)) {
    return false;
  }
  const int socket_fd =
      g_registration_socket.load(std::memory_order_acquire);
  if (socket_fd < 0) {
    return false;
  }
  RegistrationConsumed consumed{};
  consumed.magic = kRegistrationMagic;
  consumed.version = kRegistrationVersion;
  consumed.message_type = kRegistrationConsumedType;
  consumed.message_size = sizeof(consumed);
  std::memcpy(consumed.raw_artifact_id,
              g_armed_raw_artifact_id.data(),
              g_armed_raw_artifact_id.size());
  const uint64_t deadline =
      BlockingDeadline(DeadlineAfterMilliseconds(kRegistrationDeadlineMillis));
  const bool sent =
      deadline != 0 &&
      SendPacket(socket_fd, &consumed, sizeof(consumed), deadline) ==
          RegistrationOutcome::kSuccess;
  // The report lease is terminal even when the consumed acknowledgement is
  // lost. Closing the lifecycle socket makes the handler take the bounded
  // dead-client handoff path instead of leaving an un-rearmable registration.
  int expected = socket_fd;
  if (g_registration_socket.compare_exchange_strong(
          expected, -1, std::memory_order_acq_rel)) {
    FenceDisconnectedPolicyParticipant();
    g_policy_participant_alive.store(false, std::memory_order_release);
    shutdown(socket_fd, SHUT_RDWR);
    close(socket_fd);
  } else {
    FenceCrashpadClient();
  }
  return sent;
}

struct DumpRequest {
  pthread_mutex_t mutex;
  pthread_cond_t condition;
  std::atomic<int> references{2};
  bool done = false;
  bool success = false;
};

DumpRequest* CreateDumpRequest() {
  auto* request = new (std::nothrow) DumpRequest();
  if (request == nullptr) {
    return nullptr;
  }
  if (pthread_mutex_init(&request->mutex, nullptr) != 0) {
    delete request;
    return nullptr;
  }
  pthread_condattr_t attributes;
  if (pthread_condattr_init(&attributes) != 0) {
    pthread_mutex_destroy(&request->mutex);
    delete request;
    return nullptr;
  }
  const bool initialized =
      pthread_condattr_setclock(&attributes, CLOCK_MONOTONIC) == 0 &&
      pthread_cond_init(&request->condition, &attributes) == 0;
  pthread_condattr_destroy(&attributes);
  if (!initialized) {
    pthread_mutex_destroy(&request->mutex);
    delete request;
    return nullptr;
  }
  return request;
}

void ReleaseDumpRequest(DumpRequest* request) {
  if (request->references.fetch_sub(1, std::memory_order_acq_rel) == 1) {
    pthread_cond_destroy(&request->condition);
    pthread_mutex_destroy(&request->mutex);
    delete request;
  }
}

void* RunDumpRequest(void* argument) {
  auto* request = static_cast<DumpRequest*>(argument);
  int handler_socket = -1;
  if (!crashpad::CrashpadClient::GetHandlerSocket(&handler_socket, nullptr)) {
    pthread_mutex_lock(&request->mutex);
    request->done = true;
    pthread_cond_broadcast(&request->condition);
    pthread_mutex_unlock(&request->mutex);
    ReleaseDumpRequest(request);
    return nullptr;
  }

  crashpad::NativeCPUContext context;
  crashpad::CaptureContext(&context);
  siginfo_t signal_info{};
  signal_info.si_signo = SIGTRAP;
  crashpad::ExceptionInformation exception_information{};
  exception_information.siginfo_address =
      crashpad::FromPointerCast<decltype(
          exception_information.siginfo_address)>(&signal_info);
  exception_information.context_address =
      crashpad::FromPointerCast<decltype(
          exception_information.context_address)>(&context);
  exception_information.thread_id = gettid();

  crashpad::SanitizationInformation sanitization{};
  sanitization.target_module_address =
      crashpad::FromPointerCast<crashpad::VMAddress>(&RunDumpRequest);
  sanitization.sanitize_stacks = 1;

  crashpad::ExceptionHandlerProtocol::ClientInformation client_information{};
  client_information.exception_information_address =
      crashpad::FromPointerCast<crashpad::VMAddress>(&exception_information);
  client_information.sanitization_information_address =
      crashpad::FromPointerCast<crashpad::VMAddress>(&sanitization);
  crashpad::ExceptionHandlerClient client(handler_socket, true);
  const bool success = client.RequestCrashDump(client_information) == 0;
  pthread_mutex_lock(&request->mutex);
  request->success = success;
  request->done = true;
  pthread_cond_broadcast(&request->condition);
  pthread_mutex_unlock(&request->mutex);
  ReleaseDumpRequest(request);
  return nullptr;
}

bool RequestDumpWithTimeout(int timeout_millis, uint64_t deadline_ns) {
  CapturePermit permit;
  if (timeout_millis != kNonfatalDeadlineMillis ||
      deadline_ns == 0 ||
      !g_handler_alive.load(std::memory_order_acquire) ||
      !g_raw_artifact_armed.load(std::memory_order_acquire) ||
      !BeginCapture(kAnrCategory | kCrashSummaryCategory, &permit)) {
    return false;
  }
  const uint64_t wait_deadline_ns = BlockingDeadline(deadline_ns);
  const timespec deadline{
      static_cast<time_t>(wait_deadline_ns / UINT64_C(1'000'000'000)),
      static_cast<long>(wait_deadline_ns % UINT64_C(1'000'000'000))};

  auto* request = CreateDumpRequest();
  if (request == nullptr) {
    EndCapture(&permit);
    return false;
  }
  pthread_t thread;
  if (CreateRegisteredThread(&thread, RunDumpRequest, request) != 0) {
    pthread_cond_destroy(&request->condition);
    pthread_mutex_destroy(&request->mutex);
    delete request;
    EndCapture(&permit);
    return false;
  }
  pthread_detach(thread);

  pthread_mutex_lock(&request->mutex);
  int wait_result = 0;
  while (!request->done && wait_result == 0) {
    wait_result =
        pthread_cond_timedwait(&request->condition, &request->mutex, &deadline);
  }
  const bool completed = request->done && request->success;
  pthread_mutex_unlock(&request->mutex);

  if (!completed) {
    FenceCrashpadClient();
  }
  ReleaseDumpRequest(request);
  const bool consumed = completed && MarkRawArtifactConsumed();
  EndCapture(&permit);
  return consumed;
}

bool ResetEmergencySlot(int fd) {
  std::array<uint8_t, TB_EMERGENCY_RECORD_SIZE> empty{};
  size_t written = 0;
  while (written < empty.size()) {
    const ssize_t result =
        pwrite(fd, empty.data() + written, empty.size() - written, written);
    if (result > 0) {
      written += static_cast<size_t>(result);
      continue;
    }
    if (result < 0 && errno == EINTR) {
      continue;
    }
    return false;
  }
  return fdatasync(fd) == 0;
}

bool InitializeEmergency(
    const std::string& directory,
    uint32_t process_role,
    const std::array<uint8_t, kProcessIdentityBytes>& process_identity,
    uint64_t policy_epoch) {
  if (!RegisterCurrentThreadSignalStack() ||
      directory.empty() ||
      directory.size() >= g_emergency_directory.size() ||
      IsAllZero(process_identity) ||
      !UpdatePolicyState(
          policy_epoch, true, UINT64_MAX, false)) {
    return false;
  }
  if (pthread_mutex_lock(&g_lifecycle_mutex) != 0) {
    return false;
  }
  const bool same_identity =
      g_emergency_fd >= 0 &&
      g_rust_panic_fd.load(std::memory_order_acquire) >= 0 &&
      g_process_role == process_role &&
      g_emergency_directory_size == directory.size() &&
      std::memcmp(
          g_emergency_directory.data(), directory.data(), directory.size()) ==
          0 &&
      g_process_id == process_identity;
  if (same_identity) {
    const bool installed = InstallEmergencyHandlersLocked();
    pthread_mutex_unlock(&g_lifecycle_mutex);
    return installed;
  }

  const std::string path = directory + "/tracebox-emergency-" +
                           std::to_string(process_role) + ".bin";
  const std::string rust_panic_path =
      directory + "/tracebox-rust-panic-" +
      std::to_string(process_role) + ".bin";
  const int fd =
      open(path.c_str(),
           O_CREAT | O_RDWR | O_CLOEXEC | O_DSYNC | O_NOFOLLOW,
           0600);
  const int rust_panic_fd =
      open(rust_panic_path.c_str(),
           O_CREAT | O_RDWR | O_CLOEXEC | O_DSYNC | O_NOFOLLOW,
           0600);
  struct stat rust_panic_status {};
  struct stat emergency_status {};
  if (fd < 0 || fstat(fd, &emergency_status) != 0 ||
      !S_ISREG(emergency_status.st_mode) ||
      ftruncate(fd, TB_EMERGENCY_RECORD_SIZE) != 0 ||
      !ResetEmergencySlot(fd) ||
      rust_panic_fd < 0 ||
      fstat(rust_panic_fd, &rust_panic_status) != 0 ||
      !S_ISREG(rust_panic_status.st_mode) ||
      rust_panic_status.st_size < 0 ||
      rust_panic_status.st_size > 64) {
    if (fd >= 0) {
      close(fd);
    }
    if (rust_panic_fd >= 0) {
      close(rust_panic_fd);
    }
    pthread_mutex_unlock(&g_lifecycle_mutex);
    return false;
  }
  const bool handlers_were_installed =
      g_signal_handler_installation.installed();
  if (!InstallEmergencyHandlersLocked()) {
    close(fd);
    close(rust_panic_fd);
    pthread_mutex_unlock(&g_lifecycle_mutex);
    return false;
  }
  if (tb_android_configure_panic_slot_v1(
          rust_panic_fd, policy_epoch, process_role, 0) != 0) {
    if (!handlers_were_installed) {
      static_cast<void>(RestoreEmergencyHandlersLocked());
    }
    close(fd);
    close(rust_panic_fd);
    pthread_mutex_unlock(&g_lifecycle_mutex);
    return false;
  }
  if (g_emergency_fd >= 0) {
    close(g_emergency_fd);
  }
  const int previous_rust_panic_fd =
      g_rust_panic_fd.exchange(rust_panic_fd, std::memory_order_acq_rel);
  if (previous_rust_panic_fd >= 0) {
    close(previous_rust_panic_fd);
  }
  const bool identity_changed = g_process_id != process_identity;
  g_emergency_fd = fd;
  g_process_role = process_role;
  g_process_id = process_identity;
  std::memset(
      g_emergency_directory.data(), 0, g_emergency_directory.size());
  std::memcpy(
      g_emergency_directory.data(), directory.data(), directory.size());
  g_emergency_directory_size = directory.size();
  if (identity_changed) {
    __atomic_store_n(&g_sequence, 0, __ATOMIC_RELAXED);
  }
  pthread_mutex_unlock(&g_lifecycle_mutex);
  return true;
}

int RunHandler(const std::string& socket_path,
               int argc,
               char* argv[]) {
  if (pthread_mutex_lock(&g_lifecycle_mutex) != 0) {
    return EBUSY;
  }
  const bool initialized =
      g_emergency_fd >= 0 && g_process_role == 2 &&
      !IsAllZero(g_process_id);
  pthread_mutex_unlock(&g_lifecycle_mutex);
  if (!initialized) {
    return EINVAL;
  }
  struct rlimit file_size_limit {};
  if (getrlimit(RLIMIT_FSIZE, &file_size_limit) != 0) {
    return errno == 0 ? EIO : errno;
  }
  const rlim_t native_raw_limit =
      static_cast<rlim_t>(kMaximumNativeRawStagingBytes);
  if (file_size_limit.rlim_cur == RLIM_INFINITY ||
      file_size_limit.rlim_cur > native_raw_limit) {
    file_size_limit.rlim_cur = native_raw_limit;
    if (setrlimit(RLIMIT_FSIZE, &file_size_limit) != 0) {
      return errno == 0 ? EPERM : errno;
    }
  }
  prctl(PR_SET_NAME, "tracebox_handler", 0, 0, 0);

  const std::string base_directory = ParentDirectory(socket_path);
  const std::string pending = base_directory + "/crashpad-db/pending";
  const std::string journals = base_directory + "/tracebox-handler-clients";
  const std::string handoff = base_directory + "/tracebox-handler-handoff";
  if (pending.size() >= g_pending_directory.size() ||
      journals.size() >= g_client_journal_directory.size() ||
      handoff.size() >= g_handler_handoff_directory.size() ||
      (mkdir(pending.c_str(), 0700) != 0 && errno != EEXIST) ||
      (mkdir(journals.c_str(), 0700) != 0 && errno != EEXIST) ||
      (mkdir(handoff.c_str(), 0700) != 0 && errno != EEXIST)) {
    return ENAMETOOLONG;
  }
  std::memset(g_pending_directory.data(), 0, g_pending_directory.size());
  std::memcpy(
      g_pending_directory.data(), pending.c_str(), pending.size() + 1);
  std::memset(
      g_client_journal_directory.data(),
      0,
      g_client_journal_directory.size());
  std::memcpy(g_client_journal_directory.data(),
              journals.c_str(),
              journals.size() + 1);
  std::memset(g_handler_handoff_directory.data(),
              0,
              g_handler_handoff_directory.size());
  std::memcpy(g_handler_handoff_directory.data(),
              handoff.c_str(),
              handoff.size() + 1);

  int handler_pair[2];
  if (socketpair(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0, handler_pair) != 0) {
    return errno;
  }
  int pass_credentials = 1;
  setsockopt(handler_pair[0],
             SOL_SOCKET,
             SO_PASSCRED,
             &pass_credentials,
             sizeof(pass_credentials));
  setsockopt(handler_pair[1],
             SOL_SOCKET,
             SO_PASSCRED,
             &pass_credentials,
             sizeof(pass_credentials));
  g_handler_server_socket.store(handler_pair[0], std::memory_order_release);
  g_shared_client_socket.store(handler_pair[1], std::memory_order_release);
  g_handler_pid.store(getpid(), std::memory_order_release);
  g_handler_draining.store(false, std::memory_order_release);
  g_handler_alive.store(true, std::memory_order_release);
  if (!StartControlServer(socket_path)) {
    static_cast<void>(ShutdownHandlerTransport());
    return errno == 0 ? EIO : errno;
  }

  std::vector<std::string> forwarded;
  forwarded.reserve(static_cast<size_t>(argc) + 2);
  forwarded.emplace_back(argv[0]);
  for (int index = 1; index < argc; ++index) {
    forwarded.emplace_back(argv[index]);
  }
  forwarded.push_back("--initial-client-fd=" + std::to_string(handler_pair[0]));
  forwarded.emplace_back("--shared-client-connection");

  std::vector<char*> forwarded_argv;
  forwarded_argv.reserve(forwarded.size());
  for (std::string& argument : forwarded) {
    forwarded_argv.push_back(argument.data());
  }
  const int result = crashpad::HandlerMain(
      static_cast<int>(forwarded_argv.size()), forwarded_argv.data(), nullptr);
  const bool drained = ShutdownHandlerTransport();
  return result == 0 && !drained ? ETIMEDOUT : result;
}

bool ValidIdentityJournalEntry(const IdentityJournalEntry& entry,
                               uint64_t expected_sequence) {
  if (entry.magic != kIdentityJournalMagic ||
      entry.version != kIdentityJournalVersion ||
      entry.record_size != sizeof(entry) ||
      entry.reserved != 0 ||
      entry.sequence != expected_sequence ||
      entry.checksum !=
          tb_crc32c_v1(reinterpret_cast<const uint8_t*>(&entry),
                       sizeof(entry) - sizeof(entry.checksum))) {
    return false;
  }
  return (entry.entry_type == kIdentityAllocationEntry &&
          entry.identity_kind >= 1 && entry.identity_kind <= 6 &&
          entry.payload_size == kProcessIdentityBytes) ||
         (entry.entry_type == kSummaryDerivationEntry &&
          entry.identity_kind == kSummaryIdentityKind &&
          entry.payload_size == sizeof(entry.payload));
}

bool RepairIdentityJournal(int fd, off_t* valid_size) {
  struct stat status {};
  if (fstat(fd, &status) != 0 || !S_ISREG(status.st_mode) ||
      status.st_size < 0) {
    return false;
  }
  const off_t scan_limit =
      std::min<off_t>(
          status.st_size,
          static_cast<off_t>(kMaximumIdentityJournalBytes));
  off_t offset = 0;
  uint64_t sequence = 1;
  while (offset + static_cast<off_t>(sizeof(IdentityJournalEntry)) <=
         scan_limit) {
    IdentityJournalEntry entry{};
    if (!PreadAll(fd, &entry, sizeof(entry), offset) ||
        !ValidIdentityJournalEntry(entry, sequence)) {
      break;
    }
    offset += static_cast<off_t>(sizeof(entry));
    ++sequence;
  }
  if (status.st_size != offset) {
    if (ftruncate(fd, offset) != 0 || fdatasync(fd) != 0) {
      return false;
    }
  }
  *valid_size = offset;
  return true;
}

int OpenIdentityJournal(const std::string& path, bool* created) {
  *created = false;
  int fd = open(path.c_str(), O_RDWR | O_CLOEXEC | O_NOFOLLOW);
  if (fd < 0 && errno == ENOENT) {
    fd = open(path.c_str(),
              O_CREAT | O_EXCL | O_RDWR | O_CLOEXEC | O_NOFOLLOW | O_DSYNC,
              0600);
    if (fd >= 0) {
      *created = true;
    } else if (errno == EEXIST) {
      fd = open(path.c_str(), O_RDWR | O_CLOEXEC | O_NOFOLLOW);
    }
  }
  if (fd < 0 || flock(fd, LOCK_EX) != 0) {
    if (fd >= 0) {
      close(fd);
    }
    return -1;
  }
  return fd;
}

void CloseIdentityJournal(int fd) {
  flock(fd, LOCK_UN);
  close(fd);
}

bool JournalContainsIdentity(int fd,
                             off_t valid_size,
                             const uint8_t identity[kProcessIdentityBytes]) {
  for (off_t offset = 0;
       offset < valid_size;
       offset += static_cast<off_t>(sizeof(IdentityJournalEntry))) {
    IdentityJournalEntry entry{};
    if (!PreadAll(fd, &entry, sizeof(entry), offset)) {
      return true;
    }
    const uint8_t* stored =
        entry.entry_type == kIdentityAllocationEntry
            ? entry.payload
            : entry.payload + sizeof(entry.payload) - kProcessIdentityBytes;
    if (std::memcmp(stored, identity, kProcessIdentityBytes) == 0) {
      return true;
    }
  }
  return false;
}

bool JournalContainsSummaryTuple(int fd,
                                 off_t valid_size,
                                 const uint8_t payload[132]) {
  for (off_t offset = 0;
       offset < valid_size;
       offset += static_cast<off_t>(sizeof(IdentityJournalEntry))) {
    IdentityJournalEntry entry{};
    if (!PreadAll(fd, &entry, sizeof(entry), offset)) {
      return false;
    }
    if (entry.entry_type == kSummaryDerivationEntry &&
        std::memcmp(entry.payload, payload, sizeof(entry.payload)) == 0) {
      return true;
    }
  }
  return false;
}

bool AppendIdentityJournalEntry(int fd,
                                off_t offset,
                                uint16_t entry_type,
                                uint32_t kind,
                                const uint8_t* payload,
                                uint32_t payload_size) {
  if (offset < 0 ||
      static_cast<uint64_t>(offset) + sizeof(IdentityJournalEntry) >
          kMaximumIdentityJournalBytes ||
      payload_size > kIdentityJournalPayloadBytes) {
    return false;
  }
  IdentityJournalEntry entry{};
  entry.magic = kIdentityJournalMagic;
  entry.version = kIdentityJournalVersion;
  entry.entry_type = entry_type;
  entry.record_size = sizeof(entry);
  entry.identity_kind = kind;
  entry.payload_size = payload_size;
  entry.sequence =
      static_cast<uint64_t>(offset / sizeof(IdentityJournalEntry)) + 1;
  std::memcpy(entry.payload, payload, payload_size);
  entry.checksum =
      tb_crc32c_v1(reinterpret_cast<const uint8_t*>(&entry),
                   sizeof(entry) - sizeof(entry.checksum));
  return PwriteAll(fd, &entry, sizeof(entry), offset) &&
         fdatasync(fd) == 0;
}

bool AllocateJournaledIdentity(const std::string& journal_path,
                               uint32_t kind,
                               std::array<uint8_t, kProcessIdentityBytes>* id) {
  if (journal_path.empty() || kind < 1 || kind > 6) {
    return false;
  }
  bool created = false;
  const int fd = OpenIdentityJournal(journal_path, &created);
  if (fd < 0) {
    return false;
  }
  off_t valid_size = 0;
  bool success = RepairIdentityJournal(fd, &valid_size);
  if (success) {
    const tb_android_identity_result_v1 candidate =
        tb_android_allocate_identity_v1(kind);
    success = candidate.status == 0 &&
              !JournalContainsIdentity(fd, valid_size, candidate.bytes) &&
              AppendIdentityJournalEntry(
                  fd,
                  valid_size,
                  kIdentityAllocationEntry,
                  kind,
                  candidate.bytes,
                  kProcessIdentityBytes);
    if (success) {
      std::memcpy(id->data(), candidate.bytes, id->size());
    }
  }
  if (success && created) {
    success = SyncParentDirectory(journal_path);
  }
  CloseIdentityJournal(fd);
  return success;
}

bool DeriveJournaledSummaryId(
    const std::string& journal_path,
    const std::array<uint8_t, kProcessIdentityBytes>& raw_id,
    uint32_t extractor_version,
    const std::array<uint8_t, kProcessIdentityBytes>& schema,
    const std::array<uint8_t, kProcessIdentityBytes>& content_sha256,
    std::array<uint8_t, kProcessIdentityBytes>* summary_id) {
  if (journal_path.empty() || IsAllZero(raw_id)) {
    return false;
  }
  bool created = false;
  const int fd = OpenIdentityJournal(journal_path, &created);
  if (fd < 0) {
    return false;
  }
  off_t valid_size = 0;
  bool success = RepairIdentityJournal(fd, &valid_size);
  if (success) {
    tb_android_summary_input_v1 input{};
    std::memcpy(
        input.raw_artifact_id, raw_id.data(), raw_id.size());
    input.extractor_version = extractor_version;
    std::memcpy(
        input.schema_fingerprint, schema.data(), schema.size());
    std::memcpy(input.canonical_content_sha256,
                content_sha256.data(),
                content_sha256.size());
    const tb_android_identity_result_v1 candidate =
        tb_android_summary_id_v1(input);
    std::array<uint8_t, kIdentityJournalPayloadBytes> payload{};
    size_t offset = 0;
    std::memcpy(payload.data() + offset, raw_id.data(), raw_id.size());
    offset += raw_id.size();
    std::memcpy(
        payload.data() + offset, &extractor_version, sizeof(extractor_version));
    offset += sizeof(extractor_version);
    std::memcpy(payload.data() + offset, schema.data(), schema.size());
    offset += schema.size();
    std::memcpy(
        payload.data() + offset, content_sha256.data(), content_sha256.size());
    offset += content_sha256.size();
    std::memcpy(
        payload.data() + offset, candidate.bytes, sizeof(candidate.bytes));
    success = candidate.status == 0 &&
              (JournalContainsSummaryTuple(fd, valid_size, payload.data()) ||
               AppendIdentityJournalEntry(
                   fd,
                   valid_size,
                   kSummaryDerivationEntry,
                   kSummaryIdentityKind,
                   payload.data(),
                   payload.size()));
    if (success) {
      std::memcpy(summary_id->data(), candidate.bytes, summary_id->size());
    }
  }
  if (success && created) {
    success = SyncParentDirectory(journal_path);
  }
  CloseIdentityJournal(fd);
  return success;
}

bool SummarizeMinidump(const std::string& path,
                       size_t maximum_bytes,
                       tb_android_minidump_summary_v1* summary) {
  constexpr size_t kMaximumMinidumpBytes = 16 * 1024 * 1024;
  if (path.empty() || maximum_bytes == 0 ||
      maximum_bytes > kMaximumMinidumpBytes) {
    return false;
  }
  const int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
  if (fd < 0) {
    return false;
  }
  struct stat before {};
  if (fstat(fd, &before) != 0 || !S_ISREG(before.st_mode) ||
      before.st_size <= 0 ||
      static_cast<uint64_t>(before.st_size) > maximum_bytes ||
      static_cast<uint64_t>(before.st_size) > kMaximumMinidumpBytes) {
    close(fd);
    return false;
  }
  std::vector<uint8_t> bytes(static_cast<size_t>(before.st_size));
  const bool read = PreadAll(fd, bytes.data(), bytes.size(), 0);
  struct stat after {};
  const bool stable =
      read && fstat(fd, &after) == 0 &&
      before.st_dev == after.st_dev &&
      before.st_ino == after.st_ino &&
      before.st_size == after.st_size;
  close(fd);
  if (!stable) {
    return false;
  }
  *summary =
      tb_android_summarize_minidump_v1(bytes.data(), bytes.size());
  return summary->status == 0 &&
         summary->stream_count <= static_cast<uint32_t>(INT_MAX) &&
         summary->thread_count <= static_cast<uint32_t>(INT_MAX) &&
         summary->module_count <= static_cast<uint32_t>(INT_MAX) &&
         summary->stream_profile_valid <= 1;
}

bool ShutdownCapture(uint32_t timeout_millis) {
  const uint64_t epoch = g_policy_epoch.load(std::memory_order_acquire);
  static_cast<void>(
      UpdatePolicyState(epoch, true, UINT64_MAX, true));
  const bool transport_drained =
      ShutdownHandlerTransport(timeout_millis);
  if (pthread_mutex_lock(&g_lifecycle_mutex) != 0) {
    return false;
  }
  if (g_emergency_fd >= 0) {
    close(g_emergency_fd);
    g_emergency_fd = -1;
  }
  const int rust_panic_fd =
      g_rust_panic_fd.exchange(-1, std::memory_order_acq_rel);
  static_cast<void>(tb_android_configure_panic_slot_v1(
      -1,
      g_policy_epoch.load(std::memory_order_acquire),
      g_process_role,
      0));
  if (rust_panic_fd >= 0) {
    close(rust_panic_fd);
  }
  const bool handlers_restored = RestoreEmergencyHandlersLocked();
  pthread_mutex_unlock(&g_lifecycle_mutex);
  const bool current_thread_released =
      handlers_restored && UnregisterCurrentThreadSignalStack();
  return transport_drained && handlers_restored &&
         current_thread_released;
}

int RequestPolicyOperation(uint32_t operation,
                           uint64_t epoch,
                           bool disabled,
                           uint64_t deny_mask,
                           uint32_t timeout_millis) {
  if (epoch == 0 || timeout_millis == 0 ||
      timeout_millis > kMaximumPolicyTimeoutMillis ||
      operation < kPolicyPrepareOperation ||
      operation > kPolicyAbortOperation ||
      pthread_mutex_lock(&g_policy_command_mutex) != 0) {
    return kPolicyProtocol;
  }
  const int socket_fd =
      g_registration_socket.load(std::memory_order_acquire);
  if (socket_fd < 0) {
    pthread_mutex_unlock(&g_policy_command_mutex);
    return kPolicyProtocol;
  }
  if (pthread_mutex_lock(&g_policy_result_mutex) != 0) {
    pthread_mutex_unlock(&g_policy_command_mutex);
    return kPolicyProtocol;
  }
  g_policy_result_epoch = epoch;
  g_policy_result_operation = operation;
  g_policy_result_status = kPolicyProtocol;
  g_policy_result_ready = false;
  pthread_mutex_unlock(&g_policy_result_mutex);

  PolicyControlMessage request{};
  request.magic = kRegistrationMagic;
  request.version = kRegistrationVersion;
  request.message_type = kPolicyRequestType;
  request.message_size = sizeof(request);
  request.policy_epoch = epoch;
  request.deny_mask = deny_mask;
  request.disabled = disabled ? 1 : 0;
  request.operation = operation;
  request.timeout_millis = timeout_millis;
  const uint64_t deadline =
      BlockingDeadline(DeadlineAfterMilliseconds(timeout_millis));
  if (deadline == 0 ||
      SendPacket(socket_fd, &request, sizeof(request), deadline) !=
          RegistrationOutcome::kSuccess ||
      pthread_mutex_lock(&g_policy_result_mutex) != 0) {
    pthread_mutex_unlock(&g_policy_command_mutex);
    return kPolicyProtocol;
  }
  const timespec wait_deadline = RealtimeDeadline(timeout_millis);
  int wait_result = 0;
  while ((!g_policy_result_ready ||
          g_policy_result_epoch != epoch ||
          g_policy_result_operation != operation) &&
         wait_result == 0) {
    wait_result = pthread_cond_timedwait(
        &g_policy_result_changed,
        &g_policy_result_mutex,
        &wait_deadline);
  }
  const int result =
      wait_result == 0 && g_policy_result_ready &&
              g_policy_result_epoch == epoch &&
              g_policy_result_operation == operation
          ? g_policy_result_status
          : kPolicyProtocol;
  pthread_mutex_unlock(&g_policy_result_mutex);
  pthread_mutex_unlock(&g_policy_command_mutex);
  if (result == kPolicySuccess &&
      (operation == kPolicyCommitOperation ||
       operation == kPolicyAbortOperation)) {
    int expected = socket_fd;
    if (g_registration_socket.compare_exchange_strong(
            expected, -1, std::memory_order_acq_rel)) {
      FenceDisconnectedPolicyParticipant();
      g_policy_participant_alive.store(false, std::memory_order_release);
      shutdown(socket_fd, SHUT_RDWR);
      close(socket_fd);
    } else {
      FenceCrashpadClient();
    }
  }
  return result;
}

int ConnectClientWithMode(JNIEnv* env,
                          jstring socket_path_value,
                          jint process_role,
                          jbyteArray process_identity_value,
                          jbyteArray raw_artifact_id_value,
                          jlong policy_epoch,
                          uint32_t requested_mode) {
  if (!tracebox::IsClientRegistrationRequestV2(requested_mode)) {
    return static_cast<int>(
        tracebox::ClientConnectionModeV2::kRejected);
  }
  const std::string socket_path = CopyString(env, socket_path_value);
  std::array<uint8_t, kProcessIdentityBytes> process_identity{};
  std::array<uint8_t, kProcessIdentityBytes> raw_artifact_id{};
  const auto request_kind =
      static_cast<tracebox::ClientRegistrationRequestV2>(
          requested_mode);
  if (process_role < 0 || policy_epoch < 0 ||
      socket_path.empty() ||
      socket_path.size() >= sizeof(sockaddr_un::sun_path) ||
      !CopyByteArray(env, process_identity_value, &process_identity) ||
      !CopyByteArray(env, raw_artifact_id_value, &raw_artifact_id) ||
      IsAllZero(process_identity) ||
      !tracebox::ClientRegistrationRawIdentityIsValidV2(
          request_kind, IsAllZero(raw_artifact_id)) ||
      g_registration_socket.load(std::memory_order_acquire) >= 0 ||
      process_identity != g_process_id ||
      static_cast<uint32_t>(process_role) != g_process_role ||
      static_cast<uint64_t>(policy_epoch) !=
          g_policy_epoch.load(std::memory_order_acquire)) {
    return static_cast<int>(
        tracebox::ClientConnectionModeV2::kRejected);
  }

  if (request_kind !=
      tracebox::ClientRegistrationRequestV2::kEmergencyRustOnly) {
    const std::string pending =
        ParentDirectory(socket_path) + "/crashpad-db/pending";
    if (pending.size() >= g_pending_directory.size()) {
      return static_cast<int>(
          tracebox::ClientConnectionModeV2::kRejected);
    }
    std::memset(
        g_pending_directory.data(), 0, g_pending_directory.size());
    std::memcpy(
        g_pending_directory.data(), pending.c_str(), pending.size() + 1);
  }

  RegistrationRequest request{};
  request.magic = kRegistrationMagic;
  request.version = kRegistrationVersion;
  request.message_type = kRegistrationRequestType;
  request.message_size = sizeof(request);
  request.client_pid = getpid();
  request.process_role = static_cast<uint32_t>(process_role);
  request.policy_epoch = static_cast<uint64_t>(policy_epoch);
  std::memcpy(
      request.process_id, process_identity.data(), process_identity.size());
  std::memcpy(request.raw_artifact_id,
              raw_artifact_id.data(),
              raw_artifact_id.size());
  request.requested_mode = requested_mode;

  int handler_socket = -1;
  pid_t handler_pid = -1;
  int control_connection = -1;
  auto granted_mode =
      tracebox::ClientConnectionModeV2::kRejected;
  const RegistrationOutcome registration =
      ConnectControlSocket(socket_path,
                           request,
                           &handler_socket,
                           &handler_pid,
                           &granted_mode,
                           &control_connection);
  if (registration != RegistrationOutcome::kSuccess ||
      granted_mode ==
          tracebox::ClientConnectionModeV2::kRejected) {
    return static_cast<int>(
        tracebox::ClientConnectionModeV2::kRejected);
  }

  if (granted_mode ==
      tracebox::ClientConnectionModeV2::kCrashpad) {
    crashpad::CrashpadClient client;
    if (!client.SetHandlerSocket(
            crashpad::ScopedFileHandle(handler_socket), handler_pid)) {
      close(control_connection);
      return static_cast<int>(
          tracebox::ClientConnectionModeV2::kRejected);
    }
    g_crashpad_socket_fenced.store(false, std::memory_order_release);
    crashpad::CrashpadClient::SetLastChanceExceptionHandler(
        EmergencyLastChance);
    g_armed_raw_artifact_id = raw_artifact_id;
    g_raw_artifact_armed.store(true, std::memory_order_release);
  }

  int expected_registration = -1;
  if (!g_registration_socket.compare_exchange_strong(
          expected_registration,
          control_connection,
          std::memory_order_acq_rel)) {
    close(control_connection);
    FenceCrashpadClient();
    return static_cast<int>(
        tracebox::ClientConnectionModeV2::kRejected);
  }
  g_policy_participant_alive.store(true, std::memory_order_release);
  g_handler_pid.store(handler_pid, std::memory_order_release);
  g_handler_alive.store(true, std::memory_order_release);
  if (!StartDeathWatcher(control_connection)) {
    int expected = control_connection;
    if (g_registration_socket.compare_exchange_strong(
            expected, -1, std::memory_order_acq_rel)) {
      FenceDisconnectedPolicyParticipant();
      g_policy_participant_alive.store(false, std::memory_order_release);
      close(control_connection);
    } else {
      FenceCrashpadClient();
    }
    return static_cast<int>(
        tracebox::ClientConnectionModeV2::kRejected);
  }
  return static_cast<int>(granted_mode);
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_tracebox_nativecapture_NativeRuntime_nativeInitializeEmergency(
    JNIEnv* env,
    jobject,
    jstring directory,
    jint process_role,
    jbyteArray process_identity_value,
    jlong policy_epoch) {
  if (!RegisterCurrentThreadSignalStack() ||
      process_role < 0 || policy_epoch < 0) {
    return JNI_FALSE;
  }
  const std::string base = CopyString(env, directory);
  std::array<uint8_t, kProcessIdentityBytes> process_identity{};
  if (!CopyByteArray(env, process_identity_value, &process_identity)) {
    return JNI_FALSE;
  }
  return InitializeEmergency(base,
                             static_cast<uint32_t>(process_role),
                             process_identity,
                             static_cast<uint64_t>(policy_epoch))
             ? JNI_TRUE
             : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_tracebox_nativecapture_NativeRuntime_nativeRegisterCurrentThreadForCapture(
    JNIEnv*,
    jobject) {
  return RegisterCurrentThreadSignalStack() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_tracebox_nativecapture_NativeRuntime_nativeUnregisterCurrentThreadForCapture(
    JNIEnv*,
    jobject) {
  return UnregisterCurrentThreadSignalStack() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_tracebox_nativecapture_NativeRuntime_updatePolicy(
    JNIEnv*,
    jobject,
    jlong policy_epoch,
    jboolean disabled,
    jlong deny_mask) {
  if (!RegisterCurrentThreadSignalStack() || policy_epoch < 0) {
    return JNI_FALSE;
  }
  return UpdatePolicyState(static_cast<uint64_t>(policy_epoch),
                           disabled == JNI_TRUE,
                           static_cast<uint64_t>(deny_mask),
                           true)
             ? JNI_TRUE
             : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_tracebox_nativecapture_NativeRuntime_preparePolicy(
    JNIEnv*,
    jobject,
    jlong policy_epoch,
    jboolean disabled,
    jlong deny_mask,
    jint timeout_millis) {
  if (!RegisterCurrentThreadSignalStack() ||
      policy_epoch <= 0 || timeout_millis <= 0) {
    return kPolicyProtocol;
  }
  return RequestPolicyOperation(
      kPolicyPrepareOperation,
      static_cast<uint64_t>(policy_epoch),
      disabled == JNI_TRUE,
      static_cast<uint64_t>(deny_mask),
      static_cast<uint32_t>(timeout_millis));
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_tracebox_nativecapture_NativeRuntime_commitPreparedPolicy(
    JNIEnv*,
    jobject,
    jlong policy_epoch,
    jint timeout_millis) {
  if (!RegisterCurrentThreadSignalStack() ||
      policy_epoch <= 0 || timeout_millis <= 0) {
    return kPolicyProtocol;
  }
  return RequestPolicyOperation(
      kPolicyCommitOperation,
      static_cast<uint64_t>(policy_epoch),
      false,
      0,
      static_cast<uint32_t>(timeout_millis));
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_tracebox_nativecapture_NativeRuntime_abortPreparedPolicy(
    JNIEnv*,
    jobject,
    jlong policy_epoch,
    jint timeout_millis) {
  if (!RegisterCurrentThreadSignalStack() ||
      policy_epoch <= 0 || timeout_millis <= 0) {
    return kPolicyProtocol;
  }
  return RequestPolicyOperation(
      kPolicyAbortOperation,
      static_cast<uint64_t>(policy_epoch),
      true,
      UINT64_MAX,
      static_cast<uint32_t>(timeout_millis));
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_tracebox_nativecapture_NativeRuntime_coordinatePolicy(
    JNIEnv*,
    jobject,
    jlong policy_epoch,
    jboolean disabled,
    jlong deny_mask,
    jint timeout_millis) {
  if (!RegisterCurrentThreadSignalStack() ||
      policy_epoch <= 0 || timeout_millis <= 0) {
    return kPolicyProtocol;
  }
  const uint64_t target_mask = static_cast<uint64_t>(deny_mask);
  const uint64_t current_mask =
      g_policy_deny_mask.load(std::memory_order_acquire);
  const bool current_disabled =
      g_policy_disabled.load(std::memory_order_acquire);
  const bool target_disabled = disabled == JNI_TRUE;
  const bool restrictive =
      target_disabled ||
      (!current_disabled &&
       (target_mask & current_mask) == current_mask);
  if (!restrictive) {
    return kPolicyProtocol;
  }
  const int prepared = RequestPolicyOperation(
      kPolicyPrepareOperation,
      static_cast<uint64_t>(policy_epoch),
      target_disabled,
      target_mask,
      static_cast<uint32_t>(timeout_millis));
  if (prepared != kPolicySuccess) {
    return prepared;
  }
  return RequestPolicyOperation(
      kPolicyCommitOperation,
      static_cast<uint64_t>(policy_epoch),
      target_disabled,
      target_mask,
      static_cast<uint32_t>(timeout_millis));
}

extern "C" JNIEXPORT void JNICALL
Java_dev_tracebox_nativecapture_NativeRuntime_shutdownCapture(
    JNIEnv*,
    jobject) {
  if (!RegisterCurrentThreadSignalStack()) {
    return;
  }
  static_cast<void>(
      ShutdownCapture(kDefaultHandlerDrainTimeoutMillis));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_tracebox_nativecapture_NativeRuntime_nativeShutdownCaptureAndDrain(
    JNIEnv*,
    jobject,
    jint timeout_millis) {
  if (!RegisterCurrentThreadSignalStack() ||
      timeout_millis <= 0 ||
      timeout_millis >
          static_cast<jint>(kMaximumHandlerDrainTimeoutMillis)) {
    return JNI_FALSE;
  }
  return ShutdownCapture(static_cast<uint32_t>(timeout_millis))
             ? JNI_TRUE
             : JNI_FALSE;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_tracebox_nativecapture_NativeRuntime_nativeAllocateIdentity(
    JNIEnv* env,
    jobject,
    jstring journal_path_value,
    jint kind) {
  if (!RegisterCurrentThreadSignalStack() ||
      kind < 1 || kind > 6) {
    return nullptr;
  }
  const std::string journal_path =
      CopyString(env, journal_path_value);
  std::array<uint8_t, kProcessIdentityBytes> identity{};
  if (!AllocateJournaledIdentity(
          journal_path, static_cast<uint32_t>(kind), &identity)) {
    return nullptr;
  }
  return NewByteArray(env, identity.data(), identity.size());
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_tracebox_nativecapture_NativeRuntime_nativeDeriveSummaryId(
    JNIEnv* env,
    jobject,
    jstring journal_path_value,
    jbyteArray raw_id_value,
    jint extractor_version,
    jbyteArray schema_value,
    jbyteArray content_sha256_value) {
  if (!RegisterCurrentThreadSignalStack() ||
      extractor_version < 0) {
    return nullptr;
  }
  std::array<uint8_t, kProcessIdentityBytes> raw_id{};
  std::array<uint8_t, kProcessIdentityBytes> schema{};
  std::array<uint8_t, kProcessIdentityBytes> content_sha256{};
  if (!CopyByteArray(env, raw_id_value, &raw_id) ||
      !CopyByteArray(env, schema_value, &schema) ||
      !CopyByteArray(env, content_sha256_value, &content_sha256)) {
    return nullptr;
  }
  const std::string journal_path =
      CopyString(env, journal_path_value);
  std::array<uint8_t, kProcessIdentityBytes> summary_id{};
  if (!DeriveJournaledSummaryId(
          journal_path,
          raw_id,
          static_cast<uint32_t>(extractor_version),
          schema,
          content_sha256,
          &summary_id)) {
    return nullptr;
  }
  return NewByteArray(env, summary_id.data(), summary_id.size());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_tracebox_nativecapture_NativeRuntime_installRustPanicHook(
    JNIEnv*,
    jobject) {
  if (!RegisterCurrentThreadSignalStack()) {
    return JNI_FALSE;
  }
  tb_android_install_panic_hook_v1();
  return JNI_TRUE;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_dev_tracebox_nativecapture_NativeRuntime_drainRustPanic(
    JNIEnv* env,
    jobject) {
  if (!RegisterCurrentThreadSignalStack()) {
    return nullptr;
  }
  const tb_android_panic_drain_v1 record =
      tb_android_drain_panic_v1();
  if (record.has_record == 0) {
    return nullptr;
  }
  if (record.has_record != 1 || record.has_location > 1 ||
      record.line > static_cast<uint32_t>(INT_MAX) ||
      record.column > static_cast<uint32_t>(INT_MAX)) {
    return nullptr;
  }
  const std::array<jint, 4> values{
      static_cast<jint>(record.payload_kind),
      static_cast<jint>(record.has_location),
      static_cast<jint>(record.line),
      static_cast<jint>(record.column),
  };
  jintArray result = env->NewIntArray(values.size());
  if (result == nullptr) {
    return nullptr;
  }
  env->SetIntArrayRegion(result, 0, values.size(), values.data());
  return env->ExceptionCheck() ? nullptr : result;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_dev_tracebox_nativecapture_NativeRuntime_nativeSummarizeMinidump(
    JNIEnv* env,
    jobject,
    jstring path_value,
    jint maximum_bytes) {
  if (!RegisterCurrentThreadSignalStack() ||
      maximum_bytes <= 0) {
    return nullptr;
  }
  tb_android_minidump_summary_v1 summary{};
  if (!SummarizeMinidump(
          CopyString(env, path_value),
          static_cast<size_t>(maximum_bytes),
          &summary)) {
    return nullptr;
  }
  const std::array<jint, 6> values{
      static_cast<jint>(summary.stream_count),
      static_cast<jint>(summary.thread_count),
      static_cast<jint>(summary.module_count),
      static_cast<jint>(summary.exception_code),
      static_cast<jint>(summary.processor_architecture),
      static_cast<jint>(summary.stream_profile_valid),
  };
  jintArray result = env->NewIntArray(values.size());
  if (result == nullptr) {
    return nullptr;
  }
  env->SetIntArrayRegion(result, 0, values.size(), values.data());
  return env->ExceptionCheck() ? nullptr : result;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_tracebox_nativecapture_NativeRuntime_startHandler(
    JNIEnv* env,
    jobject,
    jstring socket_path_value) {
  if (!RegisterCurrentThreadSignalStack()) {
    return -1;
  }
  const std::string socket_path = CopyString(env, socket_path_value);
  const std::string directory = ParentDirectory(socket_path);
  const std::string database = directory + "/crashpad-db";
  mkdir(database.c_str(), 0700);

  std::vector<std::string> arguments{
      "tracebox-crashpad-handler",
      "--database=" + database,
  };
  std::vector<char*> argv;
  argv.reserve(arguments.size());
  for (std::string& argument : arguments) {
    argv.push_back(argument.data());
  }
  return RunHandler(
      socket_path, static_cast<int>(argv.size()), argv.data());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_tracebox_nativecapture_NativeRuntime_cleanupStaleHandlerSocket(
    JNIEnv* env,
    jobject,
    jstring socket_path_value) {
  if (!RegisterCurrentThreadSignalStack() ||
      socket_path_value == nullptr) {
    return JNI_FALSE;
  }
  return CleanupStaleHandlerSocket(
             CopyString(env, socket_path_value))
             ? JNI_TRUE
             : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_tracebox_nativecapture_NativeRuntime_nativeConnectClient(
    JNIEnv* env,
    jobject,
    jstring socket_path_value,
    jint process_role,
    jbyteArray process_identity_value,
    jbyteArray raw_artifact_id_value,
    jlong policy_epoch) {
  if (!RegisterCurrentThreadSignalStack()) {
    return JNI_FALSE;
  }
  return ConnectClientWithMode(
             env,
             socket_path_value,
             process_role,
             process_identity_value,
             raw_artifact_id_value,
             policy_epoch,
             static_cast<uint32_t>(
                 tracebox::ClientRegistrationRequestV2::
                     kCrashpadRequired)) ==
                 static_cast<int>(
                     tracebox::ClientConnectionModeV2::kCrashpad)
             ? JNI_TRUE
             : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_tracebox_nativecapture_NativeRuntime_nativeConnectClientMode(
    JNIEnv* env,
    jobject,
    jstring socket_path_value,
    jint process_role,
    jbyteArray process_identity_value,
    jbyteArray raw_artifact_id_value,
    jlong policy_epoch,
    jint requested_mode) {
  if (!RegisterCurrentThreadSignalStack() ||
      requested_mode < 0) {
    return static_cast<jint>(
        tracebox::ClientConnectionModeV2::kRejected);
  }
  return static_cast<jint>(
      ConnectClientWithMode(
          env,
          socket_path_value,
          process_role,
          process_identity_value,
          raw_artifact_id_value,
          policy_epoch,
          static_cast<uint32_t>(requested_mode)));
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_tracebox_nativecapture_NativeRuntime_handlerPid(
    JNIEnv*,
    jobject) {
  if (!RegisterCurrentThreadSignalStack()) {
    return -1;
  }
  return g_handler_pid.load(std::memory_order_acquire);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_tracebox_nativecapture_NativeRuntime_isPolicyParticipantAlive(
    JNIEnv*,
    jobject) {
  if (!RegisterCurrentThreadSignalStack()) {
    return JNI_FALSE;
  }
  return g_policy_participant_alive.load(std::memory_order_acquire)
             ? JNI_TRUE
             : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_tracebox_nativecapture_NativeRuntime_requestNonFatal(
    JNIEnv*,
    jobject,
    jint,
    jint timeout_millis) {
  if (!RegisterCurrentThreadSignalStack()) {
    return JNI_FALSE;
  }
  const uint64_t deadline = DeadlineAfterMilliseconds(timeout_millis);
  return RequestDumpWithTimeout(timeout_millis, deadline) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_tracebox_nativecapture_NativeRuntime_isHandlerAlive(
    JNIEnv*,
    jobject) {
  if (!RegisterCurrentThreadSignalStack()) {
    return JNI_FALSE;
  }
  return g_handler_alive.load(std::memory_order_acquire) ? JNI_TRUE : JNI_FALSE;
}
