#include "tracebox/emergency.h"

static void tb_store_u32(uint8_t* destination, uint32_t value) {
  destination[0] = (uint8_t)value;
  destination[1] = (uint8_t)(value >> 8);
  destination[2] = (uint8_t)(value >> 16);
  destination[3] = (uint8_t)(value >> 24);
}

static void tb_store_u64(uint8_t* destination, uint64_t value) {
  for (size_t index = 0; index < 8; ++index) {
    destination[index] = (uint8_t)(value >> (index * 8));
  }
}

static void tb_zero_bytes(uint8_t* destination, size_t length) {
  for (size_t index = 0; index < length; ++index) {
    destination[index] = 0;
  }
}

static void tb_copy_bytes(uint8_t* destination,
                          const uint8_t* source,
                          size_t length) {
  for (size_t index = 0; index < length; ++index) {
    destination[index] = source[index];
  }
}

uint32_t tb_crc32c_v1(const uint8_t* data, size_t length) {
  uint32_t crc = UINT32_MAX;
  for (size_t index = 0; index < length; ++index) {
    crc ^= data[index];
    for (unsigned bit = 0; bit < 8; ++bit) {
      const uint32_t mask = (uint32_t)-(int32_t)(crc & 1u);
      crc = (crc >> 1) ^ (UINT32_C(0x82f63b78) & mask);
    }
  }
  return ~crc;
}

tb_status_v1 tb_validate_emergency_record_view_v1(
    const tb_emergency_record_view_v1* view) {
  tb_status_v1 status = tb_validate_header_v1(
      view == NULL ? NULL : &view->header,
      (uint32_t)offsetof(tb_emergency_record_view_v1, reserved_flags),
      (uint32_t)sizeof(tb_emergency_record_view_v1));
  if (status != TB_STATUS_OK) {
    return status;
  }
  if (view->bytes == NULL || view->byte_count != TB_EMERGENCY_RECORD_SIZE) {
    return TB_STATUS_INVALID_ARGUMENT;
  }
  return TB_STATUS_OK;
}

int tb_emergency_initialize_v1(uint8_t record[TB_EMERGENCY_RECORD_SIZE],
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
                               uint64_t flags) {
  if (record == NULL || process_instance_id == NULL) {
    return -1;
  }

  static const uint8_t magic[8] = {'T', 'B', 'E', 'M', 'E', 'R', 'G', '1'};
  tb_zero_bytes(record, TB_EMERGENCY_RECORD_SIZE);
  tb_copy_bytes(record, magic, sizeof(magic));
  tb_store_u32(&record[8], 1);
  tb_store_u32(&record[12], TB_EMERGENCY_RECORD_SIZE);
  tb_copy_bytes(&record[16], process_instance_id, 32);
  tb_store_u64(&record[48], slot_sequence);
  tb_store_u64(&record[56], policy_epoch);
  tb_store_u64(&record[64], monotonic_time_ns);
  tb_store_u32(&record[80], (uint32_t)signal_number);
  tb_store_u32(&record[84], (uint32_t)signal_code);
  tb_store_u64(&record[88], fault_address);
  tb_store_u64(&record[96], instruction_address);
  tb_store_u64(&record[104], link_address);
  tb_store_u32(&record[112], process_role);
  tb_store_u32(&record[116], thread_role);
  tb_store_u64(&record[120], flags);
  tb_store_u32(&record[244], tb_crc32c_v1(record, 244));
  tb_store_u64(&record[248], TB_EMERGENCY_COMPLETION);
  return 0;
}
