#ifndef TRACEBOX_RUST_BRIDGE_H_
#define TRACEBOX_RUST_BRIDGE_H_

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct tb_android_identity_result_v1 {
  uint32_t status;
  uint8_t bytes[32];
} tb_android_identity_result_v1;

typedef struct tb_android_summary_input_v1 {
  uint8_t raw_artifact_id[32];
  uint32_t extractor_version;
  uint8_t schema_fingerprint[32];
  uint8_t canonical_content_sha256[32];
} tb_android_summary_input_v1;

typedef struct tb_android_panic_drain_v1 {
  uint32_t has_record;
  uint32_t payload_kind;
  uint32_t has_location;
  uint32_t line;
  uint32_t column;
} tb_android_panic_drain_v1;

typedef struct tb_android_minidump_summary_v1 {
  uint32_t status;
  uint32_t stream_count;
  uint32_t thread_count;
  uint32_t module_count;
  uint32_t exception_code;
  uint16_t processor_architecture;
  uint16_t stream_profile_valid;
} tb_android_minidump_summary_v1;

tb_android_identity_result_v1 tb_android_allocate_identity_v1(uint32_t kind);
tb_android_identity_result_v1 tb_android_summary_id_v1(
    tb_android_summary_input_v1 input);
void tb_android_install_panic_hook_v1(void);
uint32_t tb_android_configure_panic_slot_v1(int32_t file_descriptor,
                                            uint64_t epoch,
                                            uint32_t process_role,
                                            uint32_t enabled);
tb_android_panic_drain_v1 tb_android_drain_panic_v1(void);
tb_android_minidump_summary_v1 tb_android_summarize_minidump_v1(
    const uint8_t* bytes,
    uintptr_t length);

#ifdef __cplusplus
}  // extern "C"
#endif

#endif  // TRACEBOX_RUST_BRIDGE_H_
