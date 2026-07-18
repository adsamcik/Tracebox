#include "tracebox/abi.h"

#include <stddef.h>

static int test_structural_summary(void) {
  tb_generated_structuralsummary_v1 value = {
      .header = {(uint32_t)sizeof(value), 1u},
  };
  return tb_record_generated_structuralsummary_v1(&value, 1u) == TB_STATUS_OK;
}

static int test_emergency_record(void) {
  tb_generated_emergencyrecord_v1 value = {
      .header = {(uint32_t)sizeof(value), 1u},
  };
  return tb_record_generated_emergencyrecord_v1(&value, 1u) == TB_STATUS_OK;
}

static int test_breadcrumb(void) {
  tb_generated_breadcrumb_v1 value = {
      .header = {(uint32_t)sizeof(value), 1u},
      .code = 1u,
  };
  return tb_record_generated_breadcrumb_v1(&value, 1u) == TB_STATUS_OK &&
         tb_record_generated_breadcrumb_v1(&value, 0u) == TB_STATUS_NOT_READY;
}

static int test_handled_error(void) {
  tb_generated_handlederror_v1 value = {
      .header = {(uint32_t)offsetof(tb_generated_handlederror_v1, kind) - 1u, 1u},
  };
  return tb_record_generated_handlederror_v1(&value, 1u) ==
         TB_STATUS_INVALID_ARGUMENT;
}

int main(void) {
  return test_structural_summary() && test_emergency_record() &&
                 test_breadcrumb() && test_handled_error()
             ? 0
             : 1;
}
