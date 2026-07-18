#include <jni.h>

#include <fcntl.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/random.h>
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
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

#include "base/files/file_path.h"
#include "client/crashpad_client.h"
#include "handler/handler_main.h"
#include "tracebox/emergency.h"
#include "util/file/file_io.h"
#include "util/misc/capture_context.h"

namespace {

constexpr int kControlBacklog = 8;
constexpr int kConnectAttempts = 40;
constexpr long kConnectDelayNanoseconds = 50'000'000;
constexpr size_t kSignalStackBytes = 64 * 1024;

std::atomic<bool> g_handler_alive{false};
int g_control_socket = -1;
int g_shared_client_socket = -1;
int g_emergency_fd = -1;
uint32_t g_process_role = 0;
std::array<uint8_t, 32> g_process_id{};
uint64_t g_sequence = 0;
using OverflowFunction = void (*)(uint64_t);
volatile OverflowFunction g_overflow_function = nullptr;
__thread volatile sig_atomic_t g_in_signal = 0;
__thread void* g_signal_stack = nullptr;

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

[[noreturn]] void ReraiseDefault(int signal_number) {
  struct sigaction action {};
  sigemptyset(&action.sa_mask);
  action.sa_handler = SIG_DFL;
  sigaction(signal_number, &action, nullptr);
  sigset_t unblocked;
  sigemptyset(&unblocked);
  sigaddset(&unblocked, signal_number);
  sigprocmask(SIG_UNBLOCK, &unblocked, nullptr);
  syscall(SYS_tgkill, getpid(), gettid(), signal_number);
  _exit(128 + signal_number);
}

void EmergencySignalHandler(int signal_number, siginfo_t* signal_info, void* context) {
  if (g_in_signal != 0) {
    ReraiseDefault(signal_number);
  }
  g_in_signal = 1;
  const int signal_code = signal_info == nullptr ? 0 : signal_info->si_code;
  WriteEmergency(signal_number, signal_code, context, UINT64_C(1));
  ReraiseDefault(signal_number);
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

bool InstallEmergencyHandlers() {
  if (!InstallSignalStack()) {
    return false;
  }
  constexpr int signals[] = {SIGABRT, SIGBUS, SIGFPE, SIGILL, SIGSEGV, SIGTRAP};
  for (const int signal_number : signals) {
    struct sigaction action {};
    sigemptyset(&action.sa_mask);
    action.sa_sigaction = EmergencySignalHandler;
    action.sa_flags = SA_SIGINFO | SA_ONSTACK;
    if (sigaction(signal_number, &action, nullptr) != 0) {
      return false;
    }
  }
  return true;
}

bool SendRegistration(int socket_fd) {
  ucred credentials{};
  socklen_t credentials_size = sizeof(credentials);
  if (getsockopt(socket_fd,
                 SOL_SOCKET,
                 SO_PEERCRED,
                 &credentials,
                 &credentials_size) != 0 ||
      credentials.uid != getuid()) {
    return false;
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
  return sendmsg(socket_fd, &message, MSG_NOSIGNAL) ==
         static_cast<ssize_t>(sizeof(reply));
}

void* ControlServer(void*) {
  while (g_handler_alive.load(std::memory_order_acquire)) {
    const int client = accept4(g_control_socket, nullptr, nullptr, SOCK_CLOEXEC);
    if (client < 0) {
      if (errno == EINTR) {
        continue;
      }
      break;
    }
    SendRegistration(client);
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

bool ReceiveRegistration(int socket_fd, int* handler_socket, pid_t* handler_pid) {
  RegistrationReply reply{};
  iovec io{&reply, sizeof(reply)};
  std::array<char, CMSG_SPACE(sizeof(int))> control{};
  msghdr message{};
  message.msg_iov = &io;
  message.msg_iovlen = 1;
  message.msg_control = control.data();
  message.msg_controllen = control.size();
  if (recvmsg(socket_fd, &message, 0) != static_cast<ssize_t>(sizeof(reply)) ||
      reply.status != 0) {
    return false;
  }
  const cmsghdr* header = CMSG_FIRSTHDR(&message);
  if (header == nullptr || header->cmsg_level != SOL_SOCKET ||
      header->cmsg_type != SCM_RIGHTS ||
      header->cmsg_len != CMSG_LEN(sizeof(int))) {
    return false;
  }
  std::memcpy(handler_socket, CMSG_DATA(header), sizeof(int));
  *handler_pid = reply.handler_pid;
  return true;
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

bool ConnectControlSocket(const std::string& socket_path,
                          int* handler_socket,
                          pid_t* handler_pid) {
  for (int attempt = 0; attempt < kConnectAttempts; ++attempt) {
    const int socket_fd = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0);
    if (socket_fd < 0) {
      return false;
    }
    sockaddr_un address{};
    address.sun_family = AF_UNIX;
    std::memcpy(address.sun_path, socket_path.c_str(), socket_path.size() + 1);
    if (connect(socket_fd,
                reinterpret_cast<const sockaddr*>(&address),
                sizeof(address)) == 0) {
      const bool received =
          ReceiveRegistration(socket_fd, handler_socket, handler_pid);
      close(socket_fd);
      return received;
    }
    close(socket_fd);
    timespec delay{0, kConnectDelayNanoseconds};
    nanosleep(&delay, nullptr);
  }
  return false;
}

struct DumpRequest {
  pthread_mutex_t mutex = PTHREAD_MUTEX_INITIALIZER;
  pthread_cond_t condition = PTHREAD_COND_INITIALIZER;
  std::atomic<int> references{2};
  bool done = false;
};

void ReleaseDumpRequest(DumpRequest* request) {
  if (request->references.fetch_sub(1, std::memory_order_acq_rel) == 1) {
    pthread_cond_destroy(&request->condition);
    pthread_mutex_destroy(&request->mutex);
    delete request;
  }
}

void* RunDumpRequest(void* argument) {
  auto* request = static_cast<DumpRequest*>(argument);
  crashpad::NativeCPUContext context;
  crashpad::CaptureContext(&context);
  crashpad::CrashpadClient::DumpWithoutCrash(&context);
  pthread_mutex_lock(&request->mutex);
  request->done = true;
  pthread_cond_broadcast(&request->condition);
  pthread_mutex_unlock(&request->mutex);
  ReleaseDumpRequest(request);
  return nullptr;
}

bool RequestDumpWithTimeout(int timeout_millis) {
  if (timeout_millis <= 0 ||
      !g_handler_alive.load(std::memory_order_acquire)) {
    return false;
  }
  auto* request = new DumpRequest();
  pthread_t thread;
  if (pthread_create(&thread, nullptr, RunDumpRequest, request) != 0) {
    delete request;
    return false;
  }
  pthread_detach(thread);

  timespec deadline{};
  clock_gettime(CLOCK_REALTIME, &deadline);
  deadline.tv_sec += timeout_millis / 1000;
  deadline.tv_nsec += static_cast<long>(timeout_millis % 1000) * 1'000'000;
  if (deadline.tv_nsec >= 1'000'000'000) {
    ++deadline.tv_sec;
    deadline.tv_nsec -= 1'000'000'000;
  }

  pthread_mutex_lock(&request->mutex);
  int wait_result = 0;
  while (!request->done && wait_result == 0) {
    wait_result =
        pthread_cond_timedwait(&request->condition, &request->mutex, &deadline);
  }
  const bool completed = request->done;
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

bool InitializeEmergency(const std::string& directory, uint32_t process_role) {
  if (directory.empty() ||
      getrandom(g_process_id.data(), g_process_id.size(), 0) !=
          static_cast<ssize_t>(g_process_id.size())) {
    return false;
  }
  const std::string path =
      directory + "/tracebox-emergency-" + std::to_string(process_role) + ".bin";
  const int fd = open(path.c_str(), O_CREAT | O_RDWR | O_CLOEXEC | O_DSYNC, 0600);
  if (fd < 0 || ftruncate(fd, TB_EMERGENCY_RECORD_SIZE) != 0) {
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

  int handler_socket = -1;
  pid_t handler_pid = -1;
  if (!ConnectControlSocket(socket_path, &handler_socket, &handler_pid)) {
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

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_tracebox_nativecapture_NativeRuntime_requestNonFatal(
    JNIEnv*,
    jobject,
    jint,
    jint timeout_millis) {
  return RequestDumpWithTimeout(timeout_millis) ? JNI_TRUE : JNI_FALSE;
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
  crashpad::NativeCPUContext context;
  crashpad::CaptureContext(&context);
  crashpad::CrashpadClient::DumpWithoutCrash(&context);
  uint8_t checksum = 0;
  for (size_t index = 0; index < sizeof(seeded_secret); ++index) {
    checksum ^= static_cast<uint8_t>(seeded_secret[index]);
  }
  return checksum != 0 ? JNI_TRUE : JNI_FALSE;
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
