# Emergency Record v1

One preallocated slot is exactly 256 bytes. The writer prepares the complete buffer and performs one positional write. Integers are little-endian.

| Offset | Size | Field |
|---:|---:|---|
| 0 | 8 | `TBEMERG1` magic |
| 8 | 4 | version = 1 |
| 12 | 4 | record size = 256 |
| 16 | 32 | process-instance ID |
| 48 | 8 | slot sequence |
| 56 | 8 | policy epoch, zero when unavailable |
| 64 | 8 | monotonic timestamp ns |
| 72 | 8 | wall timestamp ms, zero when unavailable |
| 80 | 4 | signal |
| 84 | 4 | signal code |
| 88 | 8 | fault address |
| 96 | 8 | instruction address |
| 104 | 8 | link/return address |
| 112 | 4 | generated process role |
| 116 | 4 | generated thread role |
| 120 | 8 | flags |
| 128 | 116 | zero reserved bytes |
| 244 | 4 | CRC32C over bytes 0..243 |
| 248 | 8 | completion marker `0x5442454d434f4d50` |

Flags identify registered alternate stack, recursive entry, CE/policy availability, short/failed prior write, and chaining mode. No stack byte, general-purpose register file, path, string, allocation, lock, JNI, compression, encryption, or unwind is allowed.

Signal installation preserves the prior action. After the bounded write attempt, the handler restores/chains or re-raises so debuggerd/default behavior remains possible. A recursion guard permits no second Tracebox write for the same thread.

