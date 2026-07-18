# Third-Party Notices

The verified checkout created by `tools/crashpad/Acquire-Crashpad.ps1` preserves upstream license files.

| Component | License | License path after acquisition |
|---|---|---|
| Crashpad | Apache-2.0 | `checkout/crashpad/LICENSE` |
| mini_chromium | BSD-3-Clause | `checkout/crashpad/third_party/mini_chromium/mini_chromium/LICENSE` |
| linux-syscall-support | BSD-3-Clause | `checkout/crashpad/third_party/lss/lss/linux_syscall_support.h` header notice |
| zlib | Zlib | `checkout/crashpad/third_party/zlib/zlib/LICENSE` |
| googletest | BSD-3-Clause | `checkout/crashpad/third_party/googletest/googletest/LICENSE` |
| Chromium buildtools | BSD-3-Clause | component file notices under `checkout/buildtools` |

Binary distributions must reproduce applicable notices from the exact verified checkout.
