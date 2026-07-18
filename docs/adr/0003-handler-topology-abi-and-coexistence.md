# ADR-0003: Handler Topology, ABI, and Coexistence

## Status

Accepted by implementation assignment

## Decisions

This ADR freezes section-27 decisions 3, 4, and 5.

- Production ABIs are `arm64-v8a` and `x86_64`.
- One non-exported Android service runs in `:tracebox_handler`. After minimal service bootstrap it enters the native handler loop and initializes no ordinary recorder or watchdog.
- Crashpad transport and a separate bounded control channel use private same-UID local Unix sockets. Messages are fixed-size or length-prefixed with a 64 KiB hard maximum, peer credentials are verified, and queues are finite.
- Startup timeout is 2 seconds. Control operations time out after 500 ms. Nonfatal capture has a 2-second overall deadline and a 100 ms target-process-pause ceiling.
- Socket closure is the death signal; no timer or polling detects liveness.
- Reconnection occurs only on explicit initialization, foreground/component activation, capture, or package preparation.
- At most three handler starts are attempted in a rolling ten-minute window. Exceeding the limit enters crash-loop `Degraded` until a later explicit lifecycle trigger outside the window.
- A hung request is cancelled by closing its request channel. The client enters `Degraded` and retains the emergency path.
- The default coexistence mode is `Exclusive`. `BestEffortChain` and `DisableOnConflict` remain explicit. `DisableOnConflict` is non-certifying.
- Exactly one primary Crashpad result or one emergency fallback result is accepted per dispatch token. Chaining never reports a duplicate Tracebox capture.
- There is no uploader, network dependency, maintenance timer, or transport abstraction.

## Rationale

The service gives Android a declared private process while retaining Crashpad's native blocked-handler model. Event-driven reconnect and socket death notification satisfy zero-polling requirements. The supported ABIs match the mandatory engineering emulator and representative physical-device lanes.

