#include "tracebox/emergency.h"

#include <assert.h>
#include <string.h>

int main(void) {
  uint8_t record[TB_EMERGENCY_RECORD_SIZE];
  uint8_t process_id[32];
  memset(process_id, 0x5a, sizeof(process_id));

  assert(tb_emergency_initialize_v1(record,
                                    process_id,
                                    7,
                                    11,
                                    13,
                                    6,
                                    1,
                                    17,
                                    19,
                                    23,
                                    2,
                                    3,
                                    5) == 0);
  assert(memcmp(record, "TBEMERG1", 8) == 0);
  assert(record[8] == 1);
  assert(record[12] == 0);
  assert(record[13] == 1);
  assert(record[16] == 0x5a);
  assert(record[48] == 7);
  for (size_t index = 128; index < 244; ++index) {
    assert(record[index] == 0);
  }
  assert(record[248] == 0x50);
  assert(record[255] == 0x54);
  return 0;
}
