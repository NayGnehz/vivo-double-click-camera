# Pairing transport patch

`src/io/github/muntashirakon/adb/PairingConnectionCtx.java` is based on the exact
libadb-android 3.1.1 source at commit
`c849886ebc6d48e7b46d967e78a6bb65c90c3b74`:

https://github.com/MuntashirAkon/libadb-android/blob/c849886ebc6d48e7b46d967e78a6bb65c90c3b74/libadb/src/main/java/io/github/muntashirakon/adb/PairingConnectionCtx.java

The upstream SPDX license (`GPL-3.0-or-later OR Apache-2.0`) is retained. This
application uses the Apache-2.0 option. Existing third-party source and license
notices remain applicable.

Local changes are limited to this class:

- TCP connect timeout: 5 seconds. Socket and TLS read timeout: 10 seconds.
- Overall pairing I/O deadline: 20 seconds. A daemon watchdog closes the raw
  transport socket, interrupting a blocked connect, TLS handshake, or read.
  This bounds stalled network I/O; it does not forcibly interrupt native CPU
  work or guarantee scheduling while Android has suspended the process.
- `cancelActivePairing()` closes sockets for active pairing attempts in this
  process. It does not disconnect established ADB connections. Cancellation
  reports `InterruptedIOException`; the overall deadline reports
  `SocketTimeoutException`. Normal socket timeouts retain their original
  `SocketTimeoutException`.
- The pairing thread alone destroys its native SPAKE2 state. A caller closing
  the object from another thread requests cancellation instead of destroying
  state concurrently. Cleanup tolerates partially initialized connections.
- Successful pairing and cancellation are serialized to avoid reporting
  success after an accepted cancellation or deadline.
- The upstream peer identity dump is removed. Pairing passwords, exported TLS
  key material, certificates, and peer identity bytes are not added to logs.

Existing constructors and `start()`/`close()` signatures remain unchanged.
The normal `AbsAdbConnectionManager.pair(...)` path uses this replacement.
Build packaging must omit the original `PairingConnectionCtx.class` and all
`PairingConnectionCtx$*.class` entries from the libadb dependency to avoid
duplicate definitions.

Validation: the replacement compiles with Java 8 language compatibility
against the actual libadb 3.1.1 dependencies and Android SDK. Host-side socket
checks cover null-safe partial cleanup, cancellation of a blocked socket read,
deadline abort, preservation of completed connections, and cleanup on a refused
connection. Android Conscrypt/SPAKE2 interoperability and successful pairing
still require the actual phone test.
