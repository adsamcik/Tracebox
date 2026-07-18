#ifndef TRACEBOX_EMERGENCY_H_
#define TRACEBOX_EMERGENCY_H_

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define TB_EMERGENCY_RECORD_SIZE 256u
#define TB_EMERGENCY_COMPLETION UINT64_C(0x5442454d434f4d50)

typedef struct {
  uint8_t bytes[TB_EMERGENCY_RECORD_SIZE];
} tb_emergency_record_v1;

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

uint32_t tb_crc32c_v1(const uint8_t* data, size_t length);

#ifdef __cplusplus
}
#endif

#endif
