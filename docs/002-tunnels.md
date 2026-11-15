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

## Create a published TCP tunnel

Use `TunnelProtocol.TCP` for a raw TCP bytestream. Omit `port()` for an ephemeral address, or set it to a port already reserved by the project through the Control plane:

```java
try (var client = RstreamClient.fromEnv();
    var control = client.connect()) {
  var tunnel =
      control.createTunnel(
          CreateTunnelOptions.builder()
              .protocol(TunnelProtocol.TCP)
              .publish(true)
              .port(10042)
              .build());
  System.out.println(tunnel.forwardingAddress());
  tunnel.forwardTo("127.0.0.1", 22).join();
}
```

The SDK does not reserve the port. Published TCP forwards downstream bytes without adding encryption or authentication; use a secure application protocol such as SSH, or choose a TLS tunnel for TLS traffic.

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

Private tunnels do not accept public exposure options such as `hostname()`,
public authentication modes, trusted IPs, or GeoIP policy. Protocol options may
still be used when the engine can inspect the private stream, for example
`protocol(HTTP)` with `httpVersion(H2C)` or `protocol(TLS)` with
`upstreamTls(true)`. Use `publish(false)` with a name and labels, then dial it by
name or ID.

`accept(Duration)` returns `null` when no stream arrives before the timeout.
`acceptAsync()` returns a `CompletableFuture<RstreamStream>` for services that
already coordinate work through futures.

## Lifecycle

The control channel uses a negotiated heartbeat deadline. A missed deadline or
an unexpected control-transport EOF closes tunnel admission immediately, while
streams already returned by `accept()` and active `forwardTo()` relays keep
their independent payload sockets and drain normally. This prevents a
transient or asymmetric control-path outage from interrupting an established
application session. Explicit tunnel/control closure and protocol revocation
remain hard lifecycle operations enforced by the engine. Treat stream EOF or
I/O failure—not `control.done()`—as the payload-lifecycle signal.

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

The SDK does not automatically recreate a lost control channel/tunnel. The
application owns supervision: close the old resources, apply bounded backoff and
create new ones when retry is appropriate. Explicit application shutdown must
also stop that loop. Authentication/configuration errors should surface rather
than retry indefinitely, and reconnecting does not replay an application request.

## Creation and HTTP handler options

`CreateTunnelOptions.builder()` preserves unset optional values for Engine policy.
Set `publish` deliberately. A tunnel name is a private lookup key, not a reserved
public domain; protocol availability also depends on the Engine and project.

| Builder methods | Meaning and boundary |
| --- | --- |
| `name`, `labels` | Stable application lookup/discovery metadata. |
| `publish`, `type`, `protocol` | Public endpoint or private connection; only bytestreams are implemented here. |
| `hostname`, `port` | Request a stable published domain or a previously reserved TCP port; does not perform reservation. |
| `httpVersion`, `upstreamTls` | Upstream HTTP mode; `serveHttp` handles HTTP/1.1. Use forwarding for an existing server, including h2c. HTTP/3 is rejected. |
| `tokenAuth`, `rstreamAuth`, `challengeMode`, `auth` | Published HTTP request policy. `auth` groups the same flags; agent identity is configured on the client instead. |
| `httpCors` | Explicit published HTTP browser policy, below. |
| `tlsMode`, `tlsMinVersion`, `tlsAlpns`, `tlsCiphers`, `mtlsAuth` | Published TLS termination/passthrough and downstream certificate requirements. Passthrough leaves verification policy with the application. |
| `trustedIps`, `geoIp` | Published source-address/country restrictions enforced by the Engine. |
| `allowCrossRegionRouting` | Supported private-routing policy override; not client region selection. |

The wire model `TunnelProperties` additionally contains Engine-assigned fields;
use `CreateTunnelOptions` for inputs. Unsupported combinations fail explicitly.
`serveHttp` is a bounded direct handler, not a Servlet/MVC server: it buffers the
request body, invokes `RstreamHttpHandler`, writes one response and closes that
stream. Framework samples invoke application services/beans directly. Keep the
framework's full HTTP server and use `forwardTo` when its routing, filters,
WebSocket or streaming features are required.

`RstreamHttpOptions.builder()` sets `maxHeaderBytes` (default 65536), `maxBodyBytes`
(default 16777216) and `readTimeout` (default 30 seconds). These are HTTP-parser
limits, separate from client/control timeouts. `accept(Duration)` and the futures
allow the application to choose where blocking stream work runs. The SDK's own
futures use its internal worker pool; there is no client executor injection option.

## HTTP CORS

Use `.httpCors(new HttpCors(List.of("https://dashboard.example")))` on `CreateTunnelOptions.builder()`. The full `HttpCors` constructor accepts allowed origins, methods, request headers, exposed response headers, `allowCredentials`, and `maxAge` in seconds.

CORS requires a published HTTP tunnel. Allowed origins must be explicit HTTP(S) origins without paths or wildcards. Omitted method and request-header lists use GET/HEAD/POST and Authorization/Content-Type; credentials are disabled and max age is zero by default. An absent policy preserves upstream CORS. Engine answers valid preflights before request authentication, but every actual request still requires its usual authorization. A preflight does not grant access to the upstream application.
