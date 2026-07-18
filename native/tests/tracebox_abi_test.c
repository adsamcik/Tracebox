#include "tracebox/abi.h"

#include <assert.h>
#include <stddef.h>

int main(void) {
  tb_breadcrumb_v1 current = {{sizeof(current), 1u}, 7u, 0u};
  tb_breadcrumb_v1 old_sized = {{offsetof(tb_breadcrumb_v1, reserved_flags), 1u}, 7u, 0u};
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
  return 0;
}
