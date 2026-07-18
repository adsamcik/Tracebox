#include "tracebox/abi.h"

#include <stddef.h>

enum {
  TB_BREADCRUMB_V1_MIN_SIZE = offsetof(tb_breadcrumb_v1, reserved_flags),
};

tb_status_v1 tb_validate_header_v1(const tb_header_v1* header,
                                   uint32_t minimum_size,
                                   uint32_t maximum_size) {
  if (header == NULL || header->struct_size < minimum_size ||
      header->struct_size > maximum_size) {
    return TB_STATUS_INVALID_ARGUMENT;
  }
  if (header->abi_version != 1u) {
    return TB_STATUS_UNSUPPORTED_VERSION;
  }
  return TB_STATUS_OK;
}

tb_status_v1 tb_record_breadcrumb_v1(const tb_breadcrumb_v1* breadcrumb,
                                     uint32_t recorder_ready) {
  tb_status_v1 status = tb_validate_header_v1(
      breadcrumb == NULL ? NULL : &breadcrumb->header, TB_BREADCRUMB_V1_MIN_SIZE,
      (uint32_t)sizeof(tb_breadcrumb_v1));
  if (status != TB_STATUS_OK) {
    return status;
  }
  if (recorder_ready == 0u) {
    return TB_STATUS_NOT_READY;
  }
  if (breadcrumb->code == 0u) {
    return TB_STATUS_DROPPED;
  }
  return TB_STATUS_OK;
}
