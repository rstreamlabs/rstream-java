# Own Java connections and futures

The SDK uses familiar JDK sockets and streams. Control operations have
`CompletableFuture` variants. Stream reads and writes are blocking.
Choose an application executor for them if the caller runs on an event
loop or a request thread.

| Resource | Own it for |
| --- | --- |
| `RstreamClient` | The application or worker that resolves credentials and opens connections. |
| `ControlChannel` | The lifetime of the tunnels it creates. Observe `done()` to detect control loss. |
| `BytestreamTunnel` | Admission for one service. Close it to retire that tunnel. |
| `RstreamStream` | One accepted or dialed exchange. Use try-with-resources and application timeouts. |

## Use a future without blocking an event loop

Schedule the dial and the blocking exchange together on an application
executor. The worker opens and closes the stream. Given an existing client
and an `ioExecutor` owned by the caller:

```java
var response = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
  try (var stream = client.dial("java-private-http")) {
    stream.socket().setSoTimeout(15_000);
    var request = "GET / HTTP/1.0\r\nHost: localhost\r\nConnection: close\r\n\r\n";
    stream.outputStream().write(request.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    stream.outputStream().flush();
    return new String(stream.inputStream().readNBytes(256), java.nio.charset.StandardCharsets.UTF_8);
  } catch (java.io.IOException error) {
    throw new java.util.concurrent.CompletionException(error);
  }
}, ioExecutor);
```

The future returns a bounded response prefix. Keep the client and executor
open until it completes. The [private service walkthrough](001-private-and-tcp.md)
provides an endpoint for the exchange. Use the blocking version in the
complete example when orchestration already runs on a worker.
`dialAsync()` and `acceptAsync()` return futures for the connection itself.
The code receiving the stream owns its eventual close.

## Detect interruption and close the owners

When a heartbeat expires or the control transport ends, admission stops.
An accepted stream has its own payload socket; it can continue until EOF
or I/O failure. `serveHttp()` and `forwardTo()` complete when their
admission loop ends. Use `control.done()` to distinguish control failure
from normal closure, and observe application tasks to determine when
their exchanges have drained.

During shutdown, stop admission and close resources in reverse ownership
order. Cancelling a CompletableFuture alone does not close the socket.
An explicit tunnel close can terminate active connections. If the
application needs a grace period, track its active exchanges and bound
the drain before hard closure.

The application decides when a new generation should reconnect. Retry
transient failures with bounded backoff and close the previous owners
before replacing them. Surface authentication and configuration errors.
Reuse a requested hostname when the application requires a stable
published address.
