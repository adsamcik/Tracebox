# Third-Party Notices

The verified checkout created by `tools/crashpad/Acquire-Crashpad.ps1` preserves upstream license files.

| Component | License | License path after acquisition |
|---|---|---|
| Crashpad | Apache-2.0 | `checkout/crashpad/LICENSE` |
| mini_chromium | BSD-3-Clause | `checkout/crashpad/third_party/mini_chromium/mini_chromium/LICENSE` |
| linux-syscall-support | BSD-3-Clause | `checkout/crashpad/third_party/lss/lss/linux_syscall_support.h` header notice |
| zlib | Zlib | `checkout/crashpad/third_party/zlib/zlib/LICENSE` |
| googletest | BSD-3-Clause | `checkout/crashpad/third_party/googletest/googletest/LICENSE` |
| Chromium buildtools | BSD-3-Clause | `checkout/buildtools/LICENSE` |

Binary distributions reproduce these exact applicable texts in the public Android
resource `dev.tracebox.R.raw.tracebox_third_party_notices`. The deterministic
`tools/verify/Verify-CrashpadThirdPartyNotices.ps1` check binds every normalized
section to the pinned verified checkout and verifies the release AAR entry and
resource symbol.
