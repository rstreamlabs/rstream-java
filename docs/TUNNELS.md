# Tunnels

The runtime client uses the same engine protocol as the Go, JavaScript, Python,
and C++ SDKs. Public stream APIs are blocking and closeable, which keeps them
natural for Java services, while the control channel and forwarding internals
run asynchronously.

The current Java surface focuses on bytestream tunnels. It supports published
HTTP tunnels, direct HTTP handlers, private bytestream tunnels, local TCP
forwarding, and direct stream accept/dial. Datagram tunnels and HTTP/3 tunnel
creation are rejected explicitly because this SDK version does not yet
implement the datagram path.

The synchronous methods are the shortest path for services that already own
their worker threads. The same operations are also available as futures:

```java
try (var client = RstreamClient.fromEnv()) {
  var control = client.connectAsync().join();
  var tunnel =
      control
          .createTunnelAsync(CreateTunnelOptions.builder().name("private-api").build())
          .join();
  var stream = client.dialAsync(tunnel.id()).join();
  stream.close();
  tunnel.closeAsync().join();
  control.closeAsync().join();
}
```

The SDK does not impose a reactive framework or application server dependency.
Use the blocking stream methods directly, run them on your own executor, or use
the `CompletableFuture` helpers where that is a better fit.

## Create a published HTTP tunnel

```java
import io.rstream.CreateTunnelOptions;
import io.rstream.HttpVersion;
import io.rstream.RstreamClient;
import io.rstream.RstreamHttpResponse;
import io.rstream.TunnelProtocol;
import java.net.InetAddress;

public final class PublishedTunnel {
  public static void main(String[] args) throws Exception {
    try (var client = RstreamClient.fromEnv();
        var control = client.connect()) {
      var tunnel =
          control.createTunnel(
              CreateTunnelOptions.builder()
                  .protocol(TunnelProtocol.HTTP)
                  .httpVersion(HttpVersion.HTTP_1_1)
                  .publish(true)
                  .build());
      System.out.println(tunnel.forwardingAddress());
      tunnel
          .serveHttp(
              request -> RstreamHttpResponse.text(200, InetAddress.getLocalHost().getHostName()))
          .join();
    }
  }
}
```

`serveHttp()` accepts each rstream stream, parses the HTTP/1.1 request, invokes
the handler, writes the response, and closes the stream. Fixed-length and
chunked request bodies are supported, with bounded header and body sizes. This
is the preferred path for application code that can handle tunnel traffic
in-process.

Use `RstreamHttpOptions` when a service needs stricter request limits:

```java
tunnel
    .serveHttp(
        request -> RstreamHttpResponse.text(200, "ok"),
        RstreamHttpOptions.builder()
            .maxHeaderBytes(32 * 1024)
            .maxBodyBytes(1024 * 1024)
            .readTimeout(Duration.ofSeconds(10))
            .build())
    .join();
```

## Dial a private tunnel

```java
import io.rstream.RstreamClient;
import java.nio.charset.StandardCharsets;

public final class PrivateDial {
  public static void main(String[] args) throws Exception {
    var target = args.length == 0 ? "private-api" : args[0];
    try (var client = RstreamClient.fromEnv();
        var stream = client.dial(target)) {
      stream.outputStream().write("ping".getBytes(StandardCharsets.UTF_8));
      stream.outputStream().flush();
      System.out.println(new String(stream.inputStream().readNBytes(4), StandardCharsets.UTF_8));
    }
  }
}
```

## Direct accept

Use `accept()` when the application wants to own each incoming stream instead of
forwarding everything to a local TCP service:

```java
try (var client = RstreamClient.fromEnv();
    var control = client.connect()) {
  var tunnel =
      control.createTunnel(
          CreateTunnelOptions.builder()
              .name("private-api")
              .publish(false)
              .build());
  while (!tunnel.closed()) {
    try (var stream = tunnel.accept()) {
      stream.outputStream().write("ok".getBytes(StandardCharsets.UTF_8));
      stream.outputStream().flush();
    }
  }
}
```

Private tunnels do not accept public exposure options such as `protocol()`,
`httpVersion()`, `tlsMode()`, public authentication modes, trusted IPs, or GeoIP
policy. Use `publish(false)` with a name and labels, then dial it by name or ID.

`accept(Duration)` returns `null` when no stream arrives before the timeout.
`acceptAsync()` returns a `CompletableFuture<RstreamStream>` for services that
already coordinate work through futures.

## Lifecycle

`RstreamClient.connect()` opens a control channel. Use try-with-resources so the
close request is sent even when application code raises:

```java
try (var client = RstreamClient.fromEnv();
    var control = client.connect()) {
  var tunnel = control.createTunnel(CreateTunnelOptions.builder().publish(true).build());
  try {
    tunnel.forwardTo("127.0.0.1", 8000).join();
  } finally {
    tunnel.close();
  }
}
```

`BytestreamTunnel.forwardTo(host, port)` starts accepting streams and relays each
one to an existing local TCP service. Use it for services that already bind a
local port. New Java framework integrations should prefer `serveHttp()` when
the application can handle requests in-process.
