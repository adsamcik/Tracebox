#include <jni.h>

#include <android/log.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#include <array>
#include <cerrno>
#include <cstdint>
#include <cstring>
#include <string>

#include "tracebox/emergency.h"

namespace {

constexpr char kLogTag[] = "TraceboxNative";
int g_emergency_fd = -1;
uint64_t g_sequence = 0;
std::array<uint8_t, 32> g_process_id{};

std::string CopyString(JNIEnv* env, jstring value) {
  const char* chars = env->GetStringUTFChars(value, nullptr);
  if (chars == nullptr) {
    return {};
  }
  std::string result(chars);
  env->ReleaseStringUTFChars(value, chars);
  return result;
}

bool WriteEmergency(int signal_number) {
  if (g_emergency_fd < 0) {
    return false;
  }
  tb_emergency_record_v1 record;
  if (tb_emergency_initialize_v1(&record,
                                 g_process_id.data(),
                                 ++g_sequence,
                                 0,
                                 0,
                                 signal_number,
                                 0,
                                 0,
                                 0,
                                 0,
                                 1,
                                 1,
                                 0) != 0) {
    return false;
  }
  const ssize_t written = pwrite(g_emergency_fd, record.bytes, sizeof(record.bytes), 0);
  return written == static_cast<ssize_t>(sizeof(record.bytes));
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_tracebox_nativecapture_NativeRuntime_initializeEmergency(
    JNIEnv* env,
    jobject,
    jstring directory,
    jint process_role) {
  const std::string base = CopyString(env, directory);
  if (base.empty()) {
    return JNI_FALSE;
  }
  const std::string path = base + "/tracebox-emergency.bin";
  const int fd = open(path.c_str(), O_CREAT | O_RDWR | O_CLOEXEC, 0600);
  if (fd < 0 || ftruncate(fd, TB_EMERGENCY_RECORD_SIZE) != 0) {
    if (fd >= 0) {
      close(fd);
    }
    return JNI_FALSE;
  }
  if (g_emergency_fd >= 0) {
    close(g_emergency_fd);
  }
  g_emergency_fd = fd;
  for (size_t index = 0; index < g_process_id.size(); ++index) {
    g_process_id[index] =
        static_cast<uint8_t>((getpid() >> (index % 4)) ^ process_role ^ index);
  }
  return JNI_TRUE;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_tracebox_nativecapture_NativeRuntime_startHandler(
    JNIEnv* env,
    jobject,
    jstring socket_path) {
  const std::string path = CopyString(env, socket_path);
  __android_log_print(ANDROID_LOG_INFO, kLogTag, "handler bootstrap path=%s", path.c_str());
  return path.empty() ? EINVAL : ENOSYS;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_tracebox_nativecapture_NativeRuntime_connectClient(
    JNIEnv* env,
    jobject,
    jstring socket_path,
    jint process_role) {
  const std::string path = CopyString(env, socket_path);
  __android_log_print(
      ANDROID_LOG_INFO, kLogTag, "client role=%d path=%s", process_role, path.c_str());
  return JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_tracebox_nativecapture_NativeRuntime_requestNonFatal(
    JNIEnv*,
    jobject,
    jint,
    jint) {
  return JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_tracebox_nativecapture_NativeRuntime_writeEmergencyForTest(
    JNIEnv*,
    jobject,
    jint signal_number) {
  return WriteEmergency(signal_number) ? JNI_TRUE : JNI_FALSE;
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
