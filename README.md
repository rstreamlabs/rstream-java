# rstream Java SDK

rstream makes applications reachable through an outbound connection to an edge, without opening inbound ports on the application host. This SDK creates tunnels and dials private services from a JVM process.

It exposes familiar JDK streams and sockets, with `CompletableFuture` variants for connection and tunnel operations. The core has no application framework dependency, so it can fit an existing service's lifecycle. Futures schedule work on SDK-owned worker threads; applications can compose their continuations without adopting a reactive framework.

Java 17 or newer is required. The examples show plain JDK applications and how to use the SDK with Spring Boot, Quarkus, Micronaut and Vert.x.

## Where this SDK fits

An rstream client connects outbound to the edge. Published tunnels give ordinary clients a forwarding address; private tunnels are reached by name or ID through an authenticated rstream client.

| Integration | Java support |
| --- | --- |
| HTTP services | Serve HTTP/1.1 directly with `serveHttp()` or forward to a local TCP port. |
| Stream protocols | Accept bytestreams through `InputStream`, `OutputStream` and `Socket`. |
| Private connectivity | Dial bytestream tunnels by name or ID. |
| Async applications | Compose futures and tie closeable resources to the application's lifecycle. |
| Webhooks | Verify delivery signatures and parse events. |

The engine connection uses TLS. Datagram tunnels, QUIC runtime transport and HTTP/3 tunnel creation are outside this SDK’s current scope. The [SDK guide](https://rstream.io/sdks) compares language integrations and protocol coverage.

## Install

Add the published SDK from Maven Central. For a new application, [Run a first Java application](docs/000-getting-started.md) provides a complete Maven project and launch command:

```xml
<dependency>
  <groupId>io.rstream</groupId>
  <artifactId>rstream</artifactId>
  <version>0.6.1</version>
</dependency>
```

For Gradle Kotlin DSL:

```kotlin
implementation("io.rstream:rstream:0.6.1")
```

## Serve HTTP through a tunnel

Select a [project or Engine context](docs/000-getting-started.md#configure-access) that permits HTTP publication. The client reads that context when it connects. Save the following class as `PublishedTunnel.java`.

```java
import io.rstream.CreateTunnelOptions;
import io.rstream.HttpVersion;
import io.rstream.RstreamClient;
import io.rstream.RstreamHttpResponse;
import io.rstream.TunnelProtocol;

public final class PublishedTunnel {
  public static void main(String[] args) throws Exception {
    try (var client = RstreamClient.fromEnv();
        var control = client.connect();
        var tunnel =
            control.createTunnel(
                CreateTunnelOptions.builder()
                    .protocol(TunnelProtocol.HTTP)
                    .httpVersion(HttpVersion.HTTP_1_1)
                    .publish(true)
                    .build())) {
      System.out.println(tunnel.forwardingAddress());
      tunnel.serveHttp(request -> RstreamHttpResponse.text(200, "Hello from rstream")).join();
    }
  }
}
```

Run `mvn compile exec:java` with the [example POM](docs/000-getting-started.md#create-the-application). Open the printed HTTPS address to receive `Hello from rstream`. Stop the process with Ctrl+C. To keep an existing HTTP server, [forward to its local port](docs/tunnels/000-http-applications.md#keep-an-existing-http-server).

## Documentation

| Task | Guide |
| --- | --- |
| Build and run a first application | [Getting started](docs/000-getting-started.md) |
| Configure credentials and shared contexts | [Configuration](docs/001-configuration.md) |
| Create, accept or dial tunnels and manage their lifetime | [Tunnels](docs/002-tunnels.md) |
| Integrate an application framework | [Examples](examples/README.md) |
| Verify lifecycle notifications | [Webhooks](docs/003-webhooks.md) |
| Find the complete guide index | [Documentation index](docs/README.md) |
| Contribute | [Contributing](CONTRIBUTING.md) and [coding style](CODING_STYLE.md) |

## Security and support

Report vulnerabilities privately as described in [Security](SECURITY.md). For usage questions and reproducible bugs, open a [GitHub issue](https://github.com/rstreamlabs/rstream-java/issues) with secrets and customer details removed. Licensed under [Apache-2.0](LICENSE).
