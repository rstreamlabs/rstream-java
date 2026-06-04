# rstream-java

`rstream-java` is the Java SDK for rstream tunnels and webhook receivers. It is
framework-free, blocking at the public stream boundary, asynchronous internally,
and uses the same runtime protocol and configuration model as the Go,
JavaScript, Python, and C++ SDKs.

The SDK is designed for Java services that need to publish local or private
services through rstream, dial private tunnels, accept tunnel streams directly,
or verify rstream webhook deliveries in a backend without coupling the core SDK
to Spring, Jakarta EE, Micronaut, Quarkus, or another application framework.

The primary stream API is intentionally close to the JDK networking model:
`RstreamStream` exposes `InputStream`, `OutputStream`, and the underlying
`Socket`. Operations that can wait on the engine also have `CompletableFuture`
variants (`connectAsync`, `createTunnelAsync`, `dialAsync`, `acceptAsync`,
`closeTunnelAsync`) so server applications can integrate them into their own
executor, servlet, worker, or framework lifecycle.

## SDK Surface

This repository targets Java 17 and newer.

The first public release focuses on the Java-native surface that is ready for
production use:

| Area | Supported |
| --- | --- |
| Runtime client | Control-channel client with async helpers |
| Published tunnels | HTTP/1.1 bytestream tunnels |
| Private tunnels | By name or ID through `RstreamClient.dial()` |
| Local forwarding | `BytestreamTunnel.forwardTo(host, port)` |
| Direct accept | Blocking `accept()`, timed `accept(...)`, and `acceptAsync()` |
| Direct HTTP handler | `BytestreamTunnel.serveHttp(handler)` |
| Webhooks | Signature generation, verification, and event parsing |
| Config | CLI-compatible YAML config and environment variables |

Datagram tunnels, QUIC runtime transport, HTTP/3 tunnel creation, custom
transport proxies, and external credential stores are rejected explicitly by
this SDK version instead of being ignored.

## Install

Maven:

```xml
<dependency>
  <groupId>io.rstream</groupId>
  <artifactId>rstream</artifactId>
  <version>0.1.0</version>
</dependency>
```

Gradle:

```kotlin
implementation("io.rstream:rstream:0.1.0")
```

Until the first public artifact is published, install the local snapshot from
this repository:

```bash
mvn -DskipTests install
```

## Configuration

The SDK reads the same config file as the CLI by default:

```text
~/.rstream/config.yaml
```

Configuration is resolved in this order:

1. Explicit `ClientOptions` values.
2. Environment variables.
3. The selected context in the config file.
4. SDK defaults.

## Environment variables

These variables follow the shared configuration model used by the other rstream
SDKs:

| Variable | Purpose |
| --- | --- |
| `RSTREAM_CONFIG` | Override the config file path. |
| `RSTREAM_CONTEXT` | Select a context from the config file. |
| `RSTREAM_ENGINE` | Use an explicit engine host and optional port. |
| `RSTREAM_AUTHENTICATION_TOKEN` | Use an explicit authentication token. |
| `RSTREAM_MTLS_CERT_FILE` | Client certificate file for mTLS authentication. |
| `RSTREAM_MTLS_KEY_FILE` | Client private key file for mTLS authentication. |
| `RSTREAM_API_URL` | Control plane API URL for managed project discovery. |

`RSTREAM_ENGINE_ADDRESS` is also accepted for compatibility with older local
SDK workflows. Prefer `RSTREAM_ENGINE` in new code.

See [docs/CONFIGURATION.md](docs/CONFIGURATION.md) for supported YAML fields and
error behavior.

## Published HTTP tunnel

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
      System.out.println("Forwarding address: " + tunnel.forwardingAddress());
      tunnel
          .serveHttp(
              request -> RstreamHttpResponse.text(200, InetAddress.getLocalHost().getHostName()))
          .join();
    }
  }
}
```

`serveHttp()` accepts rstream streams and invokes the handler in the process.
`forwardTo()` remains available for existing services that already bind a local
TCP port.

## Private dial

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

Private tunnels are addressed by name or ID. They do not expose a public
forwarding address.

## Webhook receiver

```java
import io.rstream.Webhooks;
import java.nio.charset.StandardCharsets;

byte[] payload = requestBodyBytes;
String signature = requestHeaders.get("rstream-signature");
String secret = System.getenv("RSTREAM_WEBHOOK_SECRET");

var event = Webhooks.event(payload, signature, secret);
var resourceId = event.object().path("id").asText("");
if (event.type().wireValue().equals("tunnel.created")) {
  System.out.println("Tunnel is online: " + resourceId);
}
```

`event.id()` is suitable for idempotency. Keep the raw request body unchanged
when verifying the signature.

See [docs/WEBHOOKS.md](docs/WEBHOOKS.md) for the payload shape and headers.

## Examples

| Example | Purpose |
| --- | --- |
| [examples/published-http-server](examples/published-http-server) | Serve HTTP directly from accepted rstream streams. |
| [examples/forward-local-port](examples/forward-local-port) | Publish an existing local TCP service through forwarding. |
| [examples/private-dial](examples/private-dial) | Dial a private tunnel by name or ID. |
| [examples/webhook-receiver](examples/webhook-receiver) | Verify webhook deliveries with the JDK HTTP server. |
| [examples/spring-boot-published-http](examples/spring-boot-published-http) | Serve Spring Boot application code directly from rstream streams. |
| [examples/spring-boot-webhook-receiver](examples/spring-boot-webhook-receiver) | Verify webhook deliveries inside a Spring Boot controller. |
| [examples/quarkus-published-http](examples/quarkus-published-http) | Serve Quarkus CDI application code directly from rstream streams. |
| [examples/micronaut-published-http](examples/micronaut-published-http) | Serve Micronaut application code directly from rstream streams. |
| [examples/micronaut-webhook-receiver](examples/micronaut-webhook-receiver) | Verify webhook deliveries inside a Micronaut controller. |
| [examples/vertx-published-http](examples/vertx-published-http) | Serve Vert.x application code without blocking the event loop. |

## Development

```bash
mvn spotless:check
mvn verify
```

Real-engine tests are opt-in:

```bash
RSTREAM_JAVA_E2E=1 mvn verify
```

See [docs/TESTING.md](docs/TESTING.md) for local-engine and managed-environment
test commands.

## Repository setup and release

The intended GitHub repository is `rstreamlabs/rstream-java`. CI requires no
secret for normal pull request checks. Release automation uses release-please and
requires the maintainer-managed `RELEASE_PLEASE_TOKEN` secret plus the
`CI_ALLOWED_ACTOR` repository variable.

See [docs/GITHUB_SETUP.md](docs/GITHUB_SETUP.md) before creating or publishing
the repository.

## License

Apache-2.0. See [LICENSE](LICENSE).
