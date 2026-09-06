# Native capture is an optional compileOnly bridge. Managed-only applications leave
# it absent and keep nativeCaptureEnabled=false; all native operations are gated.
# Suppress only these optional bridge types, without retaining or suppressing other code.
-dontwarn dev.tracebox.nativecapture.HandlerStartPermit
-dontwarn dev.tracebox.nativecapture.NativeRuntime
-dontwarn dev.tracebox.nativecapture.TraceboxHandlerService
-dontwarn dev.tracebox.nativecapture.TraceboxHandlerService$Companion
