# Serve a Java application through a tunnel

Use the direct JDK HTTP handler when rstream should deliver requests to
application code. If the service already has an HTTP server, forward to
its local port and keep its application routing and filters. Use an Engine
HTTP tunnel that supports WebSocket upgrades if your application needs them.

## Run the direct HTTP examples

[Build and install the SDK snapshot](../000-getting-started.md#build-the-checkout)
and select a project or Engine context with permission to publish HTTP.
Run one of these commands from the checkout root.

| Application | Command |
| --- | --- |
| [Plain JDK](../../examples/published-http-server/README.md) | `mvn -f examples/published-http-server/pom.xml compile exec:java` |
| [Spring Boot](../../examples/spring-boot-published-http/README.md) | `mvn -f examples/spring-boot-published-http/pom.xml spring-boot:run` |
| [Quarkus](../../examples/quarkus-published-http/README.md) | `mvn -f examples/quarkus-published-http/pom.xml quarkus:dev` |
| [Micronaut](../../examples/micronaut-published-http/README.md) | `mvn -f examples/micronaut-published-http/pom.xml compile exec:java` |
| [Vert.x](../../examples/vertx-published-http/README.md) | `mvn -f examples/vertx-published-http/pom.xml compile exec:java` |

Use the printed forwarding address. The JDK program returns the hostname.
The framework programs return JSON with the framework name and hostname.
They invoke application code directly. The HTTP connection reaches the
SDK handler, which reads one bounded HTTP/1.1 request and writes its
response on the accepted stream. Framework code called by the handler keeps
its own dependency injection and service lifetime. Framework HTTP
routers and middleware require the forwarding path below.

The SDK invokes direct handlers on its workers. Set
`RstreamHttpOptions` for header and body limits and the request read
timeout. The response is buffered. Close the SDK resources with the
owning service, as described in [lifecycle and futures](002-lifecycle.md).

## Keep an existing HTTP server

[Build and install the SDK snapshot](../000-getting-started.md#build-the-checkout)
and choose a context that can publish HTTP. Start your framework on a
local interface such as loopback and make a request to it first.

### Start a test origin

For a disposable origin, use Python 3 on macOS or Linux.

```sh
demo_dir=$(mktemp -d)
printf 'hello from the loopback service\n' > "$demo_dir/index.html"
python3 -m http.server 8000 --bind 127.0.0.1 --directory "$demo_dir"
```

### Forward the local port

From the Java checkout root, launch the [forwarder](../../examples/forward-local-port/README.md)
in another terminal.

```sh
mvn -f examples/forward-local-port/pom.xml compile exec:java -Dexec.args="127.0.0.1 8000"
```

Request the printed HTTPS address and expect the same body as
`http://127.0.0.1:8000/`. Stop the forwarding process and then the local
service. An application calling `tunnel.forwardTo("127.0.0.1", 8000)`
uses the same relay. Select the published tunnel protocol and upstream HTTP
mode that match the service.
