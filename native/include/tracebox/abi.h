#ifndef TRACEBOX_ABI_H_
#define TRACEBOX_ABI_H_

#include <stddef.h>
#include <stdint.h>

#include "tracebox/generated_events.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
  uint32_t struct_size;
  uint32_t abi_version;
} tb_header_v1;

typedef enum {
  TB_STATUS_OK = 0,
  TB_STATUS_NOT_READY = 1,
  TB_STATUS_DROPPED = 2,
  TB_STATUS_UNSUPPORTED_VERSION = 3,
  TB_STATUS_INVALID_ARGUMENT = 4,
} tb_status_v1;

typedef struct {
  tb_header_v1 header;
  uint32_t code;
  uint32_t reserved_flags;
} tb_breadcrumb_v1;

/* Validates a size-prefixed public structure without reading absent fields. */
tb_status_v1 tb_validate_header_v1(const tb_header_v1* header,
                                   uint32_t minimum_size,
                                   uint32_t maximum_size);

/* Records one generated, bounded breadcrumb. No text or map fields are accepted. */
tb_status_v1 tb_record_breadcrumb_v1(const tb_breadcrumb_v1* breadcrumb,
                                     uint32_t recorder_ready);

#ifdef __cplusplus
}
#endif

#endif
