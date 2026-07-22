#include <jni.h>

#include <dirent.h>
#include <fcntl.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/un.h>
#include <time.h>
#include <ucontext.h>
#include <unistd.h>

#include <array>
#include <atomic>
#include <cerrno>
#include <climits>
#include <cstdint>
#include <cstring>
#include <new>
#include <string>
#include <string_view>
#include <vector>

#include "base/files/file_path.h"
#include "client/crashpad_client.h"
#include "handler/handler_main.h"
#include "snapshot/sanitized/sanitization_information.h"
#include "tracebox/emergency.h"
#include "tracebox/emergency_initialization.h"
#include "util/file/file_io.h"
#include "util/linux/exception_handler_client.h"
#include "util/linux/exception_handler_protocol.h"
#include "util/linux/exception_information.h"
#include "util/misc/address_types.h"
#include "util/misc/capture_context.h"
#include "util/misc/from_pointer_cast.h"

namespace {

constexpr int kControlBacklog = 8;
constexpr int kRegistrationDeadlineMillis = 2'000;
constexpr int kNonfatalDeadlineMillis = 2'000;
// The public deadline includes cancellation, descriptor teardown, and JNI return.
constexpr uint64_t kDeadlineCompletionReserveNanoseconds = UINT64_C(25'000'000);
constexpr long kConnectDelayNanoseconds = 50'000'000;
constexpr size_t kSignalStackBytes = 64 * 1024;
constexpr std::array<int, 6> kHandledSignals{
    SIGABRT, SIGBUS, SIGFPE, SIGILL, SIGSEGV, SIGTRAP};

std::atomic<bool> g_handler_alive{false};
std::atomic<int32_t> g_last_registration_outcome{2};
int g_control_socket = -1;
int g_shared_client_socket = -1;
int g_emergency_fd = -1;
uint32_t g_process_role = 0;
std::array<uint8_t, 32> g_process_id{};
std::array<char, 256> g_pending_directory{};
uint64_t g_sequence = 0;
using OverflowFunction = void (*)(uint64_t);
volatile OverflowFunction g_overflow_function = nullptr;
__thread volatile sig_atomic_t g_in_signal = 0;
__thread void* g_signal_stack = nullptr;
pthread_once_t g_signal_install_once = PTHREAD_ONCE_INIT;
int g_signal_install_result = EIO;
std::array<struct sigaction, kHandledSignals.size()> g_previous_actions{};
std::array<struct sigaction, kHandledSignals.size()> g_default_actions{};
int g_chain_test_fd = -1;
volatile sig_atomic_t g_chain_test_count = 0;
tracebox::EmergencyInitializationGate g_emergency_initialization;

enum class RegistrationOutcome : int32_t {
  kSuccess = 0,
  kDeadlineExceeded = 1,
  kUnavailable = 2,
  kProtocolError = 3,
  kSystemError = 4,
};

struct RegistrationReply {
  int32_t handler_pid;
  int32_t status;
};

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

uint64_t MonotonicNanoseconds() {
  timespec now{};
  if (clock_gettime(CLOCK_MONOTONIC, &now) != 0) {
    return 0;
  }
  return static_cast<uint64_t>(now.tv_sec) * 1'000'000'000ULL +
         static_cast<uint64_t>(now.tv_nsec);
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
  if (g_emergency_fd < 0) {
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
                                 0,
                                 MonotonicNanoseconds(),
                                 signal_number,
                                 signal_code,
                                 0,
                                 instruction_address,
                                 link_address,
                                 g_process_role,
                                 0,
                                 flags) != 0) {
    return false;
  }

  return pwrite(g_emergency_fd, record.bytes, sizeof(record.bytes), 0) ==
         static_cast<ssize_t>(sizeof(record.bytes));
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

bool InstallSignalStack() {
  if (g_signal_stack != nullptr) {
    return true;
  }
  void* memory = mmap(nullptr,
                      kSignalStackBytes,
                      PROT_READ | PROT_WRITE,
                      MAP_PRIVATE | MAP_ANONYMOUS,
                      -1,
                      0);
  if (memory == MAP_FAILED) {
    return false;
  }
  stack_t stack{};
  stack.ss_sp = memory;
  stack.ss_size = kSignalStackBytes;
  if (sigaltstack(&stack, nullptr) != 0) {
    munmap(memory, kSignalStackBytes);
    return false;
  }
  g_signal_stack = memory;
  return true;
}

void InstallEmergencyHandlersOnce() {
  if (!InstallSignalStack()) {
    g_signal_install_result = errno == 0 ? EIO : errno;
    return;
  }
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
            kHandledSignals[index], &action, &g_previous_actions[index]) != 0) {
      g_signal_install_result = errno == 0 ? EIO : errno;
      for (size_t restore = 0; restore < index; ++restore) {
        sigaction(
            kHandledSignals[restore], &g_previous_actions[restore], nullptr);
      }
      return;
    }
  }
  g_signal_install_result = 0;
}

bool InstallEmergencyHandlers() {
  const int once_result =
      pthread_once(&g_signal_install_once, InstallEmergencyHandlersOnce);
  return once_result == 0 && g_signal_install_result == 0;
}

void TestPriorSignalHandler(int, siginfo_t*, void*) {
  const sig_atomic_t count = g_chain_test_count + 1;
  g_chain_test_count = count;
  if (g_chain_test_fd >= 0) {
    const uint8_t marker =
        count > static_cast<sig_atomic_t>(UINT8_MAX)
            ? UINT8_MAX
            : static_cast<uint8_t>(count);
    pwrite(g_chain_test_fd, &marker, sizeof(marker), 0);
  }
}

RegistrationOutcome SendRegistration(int socket_fd) {
  const uint64_t overall_deadline =
      DeadlineAfterMilliseconds(kRegistrationDeadlineMillis);
  if (overall_deadline == 0) {
    return RegistrationOutcome::kSystemError;
  }
  const uint64_t deadline = BlockingDeadline(overall_deadline);
  ucred credentials{};
  socklen_t credentials_size = sizeof(credentials);
  if (getsockopt(socket_fd,
                 SOL_SOCKET,
                 SO_PEERCRED,
                 &credentials,
                 &credentials_size) != 0 ||
      credentials.uid != getuid()) {
    return RegistrationOutcome::kProtocolError;
  }

  RegistrationReply reply{getpid(), 0};
  iovec io{&reply, sizeof(reply)};
  std::array<char, CMSG_SPACE(sizeof(int))> control{};
  msghdr message{};
  message.msg_iov = &io;
  message.msg_iovlen = 1;
  message.msg_control = control.data();
  message.msg_controllen = control.size();
  cmsghdr* header = CMSG_FIRSTHDR(&message);
  header->cmsg_level = SOL_SOCKET;
  header->cmsg_type = SCM_RIGHTS;
  header->cmsg_len = CMSG_LEN(sizeof(int));
  std::memcpy(CMSG_DATA(header), &g_shared_client_socket, sizeof(int));
  while (true) {
    const ssize_t sent =
        sendmsg(socket_fd, &message, MSG_NOSIGNAL | MSG_DONTWAIT);
    if (sent == static_cast<ssize_t>(sizeof(reply))) {
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

void* ControlServer(void*) {
  while (g_handler_alive.load(std::memory_order_acquire)) {
    const int client =
        accept4(g_control_socket, nullptr, nullptr, SOCK_CLOEXEC | SOCK_NONBLOCK);
    if (client < 0) {
      if (errno == EINTR) {
        continue;
      }
      break;
    }
    static_cast<void>(SendRegistration(client));
    close(client);
  }
  return nullptr;
}

bool StartControlServer(const std::string& socket_path) {
  if (socket_path.size() >= sizeof(sockaddr_un::sun_path)) {
    return false;
  }
  const int socket_fd = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0);
  if (socket_fd < 0) {
    return false;
  }
  sockaddr_un address{};
  address.sun_family = AF_UNIX;
  std::memcpy(address.sun_path, socket_path.c_str(), socket_path.size() + 1);
  unlink(socket_path.c_str());
  if (bind(socket_fd,
           reinterpret_cast<const sockaddr*>(&address),
           sizeof(address)) != 0 ||
      chmod(socket_path.c_str(), 0600) != 0 ||
      listen(socket_fd, kControlBacklog) != 0) {
    close(socket_fd);
    return false;
  }
  g_control_socket = socket_fd;
  pthread_t thread;
  if (pthread_create(&thread, nullptr, ControlServer, nullptr) != 0) {
    close(socket_fd);
    g_control_socket = -1;
    return false;
  }
  pthread_detach(thread);
  return true;
}

RegistrationOutcome ReceiveRegistration(int socket_fd,
                                        int* handler_socket,
                                        pid_t* handler_pid,
                                        uint64_t deadline) {
  RegistrationReply reply{};
  iovec io{&reply, sizeof(reply)};
  std::array<char, CMSG_SPACE(sizeof(int))> control{};
  msghdr message{};
  message.msg_iov = &io;
  message.msg_iovlen = 1;
  message.msg_control = control.data();
  message.msg_controllen = control.size();
  while (true) {
    const ssize_t received = recvmsg(socket_fd, &message, MSG_DONTWAIT);
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
  if (reply.status != 0 ||
      (message.msg_flags & (MSG_CTRUNC | MSG_TRUNC)) != 0) {
    return RegistrationOutcome::kProtocolError;
  }
  const cmsghdr* header = CMSG_FIRSTHDR(&message);
  if (header == nullptr || header->cmsg_level != SOL_SOCKET ||
      header->cmsg_type != SCM_RIGHTS ||
      header->cmsg_len != CMSG_LEN(sizeof(int))) {
    return RegistrationOutcome::kProtocolError;
  }
  std::memcpy(handler_socket, CMSG_DATA(header), sizeof(int));
  *handler_pid = reply.handler_pid;
  return RegistrationOutcome::kSuccess;
}

void* HandlerDeathWatcher(void* argument) {
  const int socket_fd = static_cast<int>(reinterpret_cast<intptr_t>(argument));
  pollfd descriptor{socket_fd, POLLHUP | POLLERR, 0};
  while (poll(&descriptor, 1, -1) < 0 && errno == EINTR) {
  }
  g_handler_alive.store(false, std::memory_order_release);
  close(socket_fd);
  return nullptr;
}

bool StartDeathWatcher(int socket_fd) {
  pthread_t thread;
  if (pthread_create(&thread,
                     nullptr,
                     HandlerDeathWatcher,
                     reinterpret_cast<void*>(static_cast<intptr_t>(socket_fd))) != 0) {
    return false;
  }
  pthread_detach(thread);
  return true;
}

RegistrationOutcome ConnectControlSocket(const std::string& socket_path,
                                        int* handler_socket,
                                        pid_t* handler_pid) {
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
      const RegistrationOutcome received =
          ReceiveRegistration(socket_fd, handler_socket, handler_pid, deadline);
      close(socket_fd);
      return received;
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

int CountPendingReports() {
  DIR* directory = opendir(g_pending_directory.data());
  if (directory == nullptr) {
    return -1;
  }
  int count = 0;
  while (dirent* entry = readdir(directory)) {
    const std::string_view name(entry->d_name);
    if (name.ends_with(".dmp")) {
      ++count;
    }
  }
  closedir(directory);
  return count;
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
  if (timeout_millis != kNonfatalDeadlineMillis ||
      deadline_ns == 0 ||
      !g_handler_alive.load(std::memory_order_acquire)) {
    return false;
  }
  const uint64_t wait_deadline_ns = BlockingDeadline(deadline_ns);
  const timespec deadline{
      static_cast<time_t>(wait_deadline_ns / UINT64_C(1'000'000'000)),
      static_cast<long>(wait_deadline_ns % UINT64_C(1'000'000'000))};

  auto* request = CreateDumpRequest();
  if (request == nullptr) {
    return false;
  }
  pthread_t thread;
  if (pthread_create(&thread, nullptr, RunDumpRequest, request) != 0) {
    pthread_cond_destroy(&request->condition);
    pthread_mutex_destroy(&request->mutex);
    delete request;
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
    int handler_socket = -1;
    if (crashpad::CrashpadClient::GetHandlerSocket(&handler_socket, nullptr)) {
      shutdown(handler_socket, SHUT_RDWR);
      close(handler_socket);
    }
    g_handler_alive.store(false, std::memory_order_release);
  }
  ReleaseDumpRequest(request);
  return completed;
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

bool FillRandomBytes(uint8_t* bytes, size_t size) {
  size_t offset = 0;
#if defined(SYS_getrandom)
  while (offset < size) {
    const ssize_t result =
        syscall(SYS_getrandom, bytes + offset, size - offset, 0);
    if (result > 0) {
      offset += static_cast<size_t>(result);
      continue;
    }
    if (result < 0 && errno == EINTR) {
      continue;
    }
    break;
  }
  if (offset == size) {
    return true;
  }
#endif

  const int fd = open("/dev/urandom", O_RDONLY | O_CLOEXEC);
  if (fd < 0) {
    return false;
  }
  while (offset < size) {
    const ssize_t result = read(fd, bytes + offset, size - offset);
    if (result > 0) {
      offset += static_cast<size_t>(result);
      continue;
    }
    if (result < 0 && errno == EINTR) {
      continue;
    }
    close(fd);
    return false;
  }
  return close(fd) == 0;
}

bool InitializeEmergency(const std::string& directory, uint32_t process_role) {
  if (directory.empty()) {
    return false;
  }
  return g_emergency_initialization.Initialize(
      directory, process_role, [&directory, process_role] {
        if (!FillRandomBytes(g_process_id.data(), g_process_id.size())) {
          return false;
        }
        const std::string path = directory + "/tracebox-emergency-" +
                                 std::to_string(process_role) + ".bin";
        const int fd =
            open(path.c_str(), O_CREAT | O_RDWR | O_CLOEXEC | O_DSYNC, 0600);
        if (fd < 0 || ftruncate(fd, TB_EMERGENCY_RECORD_SIZE) != 0 ||
            !ResetEmergencySlot(fd)) {
          if (fd >= 0) {
            close(fd);
          }
          return false;
        }
        if (g_emergency_fd >= 0) {
          close(g_emergency_fd);
        }
        g_emergency_fd = fd;
        g_process_role = process_role;
        return InstallEmergencyHandlers();
      });
}

int RunHandler(const std::string& socket_path,
               const std::string& emergency_directory,
               int argc,
               char* argv[]) {
  if (!InitializeEmergency(emergency_directory, 2)) {
    return errno == 0 ? EIO : errno;
  }
  prctl(PR_SET_NAME, "tracebox_handler", 0, 0, 0);

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
  g_shared_client_socket = handler_pair[1];
  g_handler_alive.store(true, std::memory_order_release);
  if (!StartControlServer(socket_path)) {
    close(handler_pair[0]);
    close(handler_pair[1]);
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
  g_handler_alive.store(false, std::memory_order_release);
  if (g_control_socket >= 0) {
    close(g_control_socket);
  }
  close(handler_pair[0]);
  close(handler_pair[1]);
  return result;
}

__attribute__((noinline)) void OverflowStack(uint64_t depth) {
  volatile uint8_t page[4096]{};
  page[depth % sizeof(page)] = static_cast<uint8_t>(depth);
  asm volatile("" : : "r"(&page[0]) : "memory");
  g_overflow_function(depth + 1);
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_tracebox_nativecapture_NativeRuntime_initializeEmergency(
    JNIEnv* env,
    jobject,
    jstring directory,
    jint process_role) {
  const std::string base = CopyString(env, directory);
  return InitializeEmergency(base, static_cast<uint32_t>(process_role))
             ? JNI_TRUE
             : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_tracebox_nativecapture_NativeRuntime_startHandler(
    JNIEnv* env,
    jobject,
    jstring socket_path_value) {
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
      socket_path, directory, static_cast<int>(argv.size()), argv.data());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_tracebox_nativecapture_NativeRuntime_connectClient(
    JNIEnv* env,
    jobject,
    jstring socket_path_value,
    jint) {
  const std::string socket_path = CopyString(env, socket_path_value);
  if (socket_path.empty() || socket_path.size() >= sizeof(sockaddr_un::sun_path)) {
    return JNI_FALSE;
  }
  const std::string pending =
      ParentDirectory(socket_path) + "/crashpad-db/pending";
  if (pending.size() >= g_pending_directory.size()) {
    return JNI_FALSE;
  }
  std::memset(g_pending_directory.data(), 0, g_pending_directory.size());
  std::memcpy(
      g_pending_directory.data(), pending.c_str(), pending.size() + 1);

  int handler_socket = -1;
  pid_t handler_pid = -1;
  const RegistrationOutcome registration =
      ConnectControlSocket(socket_path, &handler_socket, &handler_pid);
  g_last_registration_outcome.store(
      static_cast<int32_t>(registration), std::memory_order_release);
  if (registration != RegistrationOutcome::kSuccess) {
    return JNI_FALSE;
  }
  const int watcher_socket = dup(handler_socket);
  if (watcher_socket < 0) {
    close(handler_socket);
    return JNI_FALSE;
  }

  crashpad::CrashpadClient client;
  if (!client.SetHandlerSocket(crashpad::ScopedFileHandle(handler_socket),
                               handler_pid)) {
    close(watcher_socket);
    return JNI_FALSE;
  }
  crashpad::CrashpadClient::SetLastChanceExceptionHandler(EmergencyLastChance);
  g_handler_alive.store(true, std::memory_order_release);
  if (!StartDeathWatcher(watcher_socket)) {
    close(watcher_socket);
    return JNI_FALSE;
  }
  return JNI_TRUE;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_tracebox_nativecapture_NativeRuntime_lastRegistrationOutcomeForTest(
    JNIEnv*,
    jobject) {
  return g_last_registration_outcome.load(std::memory_order_acquire);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_tracebox_nativecapture_NativeRuntime_requestNonFatal(
    JNIEnv*,
    jobject,
    jint,
    jint timeout_millis) {
  const uint64_t deadline = DeadlineAfterMilliseconds(timeout_millis);
  return RequestDumpWithTimeout(timeout_millis, deadline) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_tracebox_nativecapture_NativeRuntime_requestSeededNonFatalForTest(
    JNIEnv*,
    jobject) {
  volatile char seeded_secret[] =
      "TRACEBOX_PHASE0_SEEDED_SECRET_7F4C19E2A6B35D80";
  asm volatile("" : : "r"(seeded_secret) : "memory");
  if (!g_handler_alive.load(std::memory_order_acquire)) {
    return JNI_FALSE;
  }
  const int before = CountPendingReports();
  if (before < 0) {
    return JNI_FALSE;
  }
  crashpad::NativeCPUContext context;
  crashpad::CaptureContext(&context);
  crashpad::CrashpadClient::DumpWithoutCrash(&context);
  uint8_t checksum = 0;
  for (size_t index = 0; index < sizeof(seeded_secret); ++index) {
    checksum ^= static_cast<uint8_t>(seeded_secret[index]);
  }
  return checksum != 0 && CountPendingReports() > before ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_tracebox_nativecapture_NativeRuntime_isHandlerAlive(
    JNIEnv*,
    jobject) {
  return g_handler_alive.load(std::memory_order_acquire) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_tracebox_nativecapture_NativeRuntime_writeEmergencyForTest(
    JNIEnv*,
    jobject,
    jint signal_number) {
  return WriteEmergency(signal_number, 0, nullptr, UINT64_C(8)) ? JNI_TRUE
                                                                : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_tracebox_nativecapture_NativeRuntime_writeEmergencyFaultForTest(
    JNIEnv*,
    jobject,
    jint mode) {
  if (g_emergency_fd < 0) {
    return JNI_FALSE;
  }
  tb_emergency_record_v1 record;
  if (tb_emergency_initialize_v1(&record,
                                 g_process_id.data(),
                                 __atomic_add_fetch(
                                     &g_sequence, 1, __ATOMIC_RELAXED),
                                 0,
                                 MonotonicNanoseconds(),
                                 SIGABRT,
                                 0,
                                 0,
                                 0,
                                 0,
                                 g_process_role,
                                 0,
                                 UINT64_C(16)) != 0) {
    return JNI_FALSE;
  }
  if (mode == 1) {
    return pwrite(g_emergency_fd, record.bytes, 128, 0) ==
                   static_cast<ssize_t>(sizeof(record.bytes))
               ? JNI_TRUE
               : JNI_FALSE;
  }
  return pwrite(-1, record.bytes, sizeof(record.bytes), 0) ==
                 static_cast<ssize_t>(sizeof(record.bytes))
             ? JNI_TRUE
             : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_tracebox_nativecapture_NativeRuntime_crashForTest(
    JNIEnv*,
    jobject,
    jint kind) {
  if (kind == 0) {
    abort();
  }
  volatile int* invalid = nullptr;
  *invalid = kind;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_tracebox_nativecapture_NativeRuntime_stackOverflowForTest(
    JNIEnv*,
    jobject) {
  g_overflow_function = OverflowStack;
  OverflowStack(1);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_tracebox_nativecapture_NativeRuntime_hangForTest(
    JNIEnv*,
    jobject) {
  kill(getpid(), SIGSTOP);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_tracebox_nativecapture_NativeRuntime_recursiveSignalForTest(
    JNIEnv*,
    jobject) {
  g_in_signal = 1;
  EmergencySignalHandler(SIGABRT, nullptr, nullptr);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_tracebox_nativecapture_NativeRuntime_prepareSignalChainForTest(
    JNIEnv* env,
    jobject,
    jstring directory) {
  const std::string base = CopyString(env, directory);
  if (base.empty()) {
    return JNI_FALSE;
  }
  const std::string path = base + "/tracebox-chain-marker.bin";
  const int fd = open(path.c_str(), O_CREAT | O_RDWR | O_CLOEXEC | O_DSYNC, 0600);
  if (fd < 0 || ftruncate(fd, 1) != 0) {
    if (fd >= 0) {
      close(fd);
    }
    return JNI_FALSE;
  }
  const uint8_t zero = 0;
  if (pwrite(fd, &zero, sizeof(zero), 0) != static_cast<ssize_t>(sizeof(zero))) {
    close(fd);
    return JNI_FALSE;
  }
  struct sigaction action {};
  sigemptyset(&action.sa_mask);
  action.sa_sigaction = TestPriorSignalHandler;
  action.sa_flags = SA_SIGINFO | SA_ONSTACK;
  if (sigaction(SIGABRT, &action, nullptr) != 0) {
    close(fd);
    return JNI_FALSE;
  }
  if (g_chain_test_fd >= 0) {
    close(g_chain_test_fd);
  }
  g_chain_test_fd = fd;
  g_chain_test_count = 0;
  return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_tracebox_nativecapture_NativeRuntime_terminateHandlerForTest(
    JNIEnv*,
    jobject) {
  kill(getpid(), SIGTERM);
}
