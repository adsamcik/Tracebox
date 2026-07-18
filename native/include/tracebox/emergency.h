#ifndef TRACEBOX_EMERGENCY_H_
#define TRACEBOX_EMERGENCY_H_

#include <stddef.h>
#include <stdint.h>

#include "tracebox/abi.h"

#ifdef __cplusplus
extern "C" {
#endif

#define TB_EMERGENCY_RECORD_SIZE 256u
#define TB_EMERGENCY_COMPLETION UINT64_C(0x5442454d434f4d50)

/* The fixed 256-byte raw record is an internal on-disk format used by the
 * Phase 0 signal-safe writer/reader. It remains byte-for-byte stable and is
 * not itself a size/version-prefixed public ABI struct. */
typedef struct {
  uint8_t bytes[TB_EMERGENCY_RECORD_SIZE];
} tb_emergency_record_v1;

/* Public versioned view for new ABI consumers of the internal raw on-disk
 * record. Describes (does not replace) the fixed byte layout above. */
typedef struct {
  tb_header_v1 header;
  const uint8_t* bytes;
  uint32_t byte_count;
  uint32_t reserved_flags;
} tb_emergency_record_view_v1;

/* Initializes the internal fixed raw on-disk record. */
int tb_emergency_initialize_v1(tb_emergency_record_v1* record,
                                const uint8_t process_instance_id[32],
                                uint64_t slot_sequence,
                                uint64_t policy_epoch,
                                uint64_t monotonic_time_ns,
                                int32_t signal_number,
                                int32_t signal_code,
                                uint64_t fault_address,
                                uint64_t instruction_address,
                                uint64_t link_address,
                                uint32_t process_role,
                                uint32_t thread_role,
                                uint64_t flags);

/* Validates a size-prefixed public view without changing the raw record layout. */
tb_status_v1 tb_validate_emergency_record_view_v1(
    const tb_emergency_record_view_v1* view);

uint32_t tb_crc32c_v1(const uint8_t* data, size_t length);

#ifdef __cplusplus
}
#endif

#endif
