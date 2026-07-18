#include "tracebox/abi.h"
#include "tracebox/emergency.h"

#include <assert.h>
#include <stddef.h>

int main(void) {
  tb_breadcrumb_v1 current = {{sizeof(current), 1u}, 7u, 9u};
  tb_breadcrumb_v1 old_sized = {{offsetof(tb_breadcrumb_v1, monotonic_time_ns), 1u}, 7u, 0u};
  tb_breadcrumb_v1 oversized = {{sizeof(current) + 1u, 1u}, 7u, 0u};
  tb_breadcrumb_v1 undersized = {{sizeof(tb_header_v1) - 1u, 1u}, 7u, 0u};
  tb_breadcrumb_v1 unsupported = {{sizeof(current), 2u}, 7u, 0u};

  assert(tb_record_breadcrumb_v1(&current, 1u) == TB_STATUS_OK);
  assert(tb_record_breadcrumb_v1(&old_sized, 1u) == TB_STATUS_OK);
  assert(tb_record_breadcrumb_v1(&oversized, 1u) == TB_STATUS_INVALID_ARGUMENT);
  assert(tb_record_breadcrumb_v1(&undersized, 1u) == TB_STATUS_INVALID_ARGUMENT);
  assert(tb_record_breadcrumb_v1(&unsupported, 1u) == TB_STATUS_UNSUPPORTED_VERSION);
  assert(tb_record_breadcrumb_v1(&current, 0u) == TB_STATUS_NOT_READY);
  current.code = 0u;
  assert(tb_record_breadcrumb_v1(&current, 1u) == TB_STATUS_DROPPED);
  uint8_t raw[TB_EMERGENCY_RECORD_SIZE] = {0};
  tb_emergency_record_view_v1 view = {{sizeof(view), 1u}, raw, sizeof(raw), 0u};
  tb_emergency_record_view_v1 old_view = {{offsetof(tb_emergency_record_view_v1, reserved_flags), 1u}, raw, sizeof(raw), 0u};
  assert(tb_validate_emergency_record_view_v1(&view) == TB_STATUS_OK);
  assert(tb_validate_emergency_record_view_v1(&old_view) == TB_STATUS_OK);
  view.byte_count -= 1u;
  assert(tb_validate_emergency_record_view_v1(&view) == TB_STATUS_INVALID_ARGUMENT);
  return 0;
}
