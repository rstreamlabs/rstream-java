# Java application examples

Start with `published-http-server` for a plain JDK handler. The table also includes local forwarding, private dialing and framework-specific HTTP and webhook examples.

[Install the SDK from this checkout](../docs/000-getting-started.md#build-the-checkout) before running an example. Run the Maven commands from the repository root. Tunnel examples need a
context that allows publication or private dialing. Webhook examples
verify signed deliveries locally without an Engine.

| Example | Purpose |
| --- | --- |
| [published-http-server](published-http-server) | Serve HTTP directly from accepted rstream streams. |
| [forward-local-port](forward-local-port) | Publish an already running local TCP or HTTP service through forwarding. |
| [private-dial](private-dial) | Send an HTTP request to a private tunnel by name or ID. |
| [webhook-receiver](webhook-receiver) | Verify webhook deliveries with only JDK APIs. |
| [spring-boot-published-http](spring-boot-published-http) | Serve Spring Boot application code directly from rstream streams. |
| [spring-boot-webhook-receiver](spring-boot-webhook-receiver) | Verify webhook deliveries inside a Spring Boot controller. |
| [quarkus-published-http](quarkus-published-http) | Serve Quarkus CDI application code directly from rstream streams. |
| [micronaut-published-http](micronaut-published-http) | Serve Micronaut application code directly from rstream streams. |
| [micronaut-webhook-receiver](micronaut-webhook-receiver) | Verify webhook deliveries inside a Micronaut controller. |
| [vertx-published-http](vertx-published-http) | Serve Vert.x application code without blocking the event loop. |

Tunnel examples use `RstreamClient.fromEnv()`, so runtime configuration comes from
the standard rstream config file or environment variables.

## Run the first server

Select a [project or Engine context](../docs/000-getting-started.md#configure-access) with permission to publish HTTP. From the repository root, run the JDK server.

```sh
mvn -f examples/published-http-server/pom.xml compile exec:java
```

Open the printed forwarding address while the process is running; it returns the server's hostname. Stop the process with an interrupt. Other examples describe their own target services, framework dependencies and receiving endpoints. Webhook receivers need a signing secret and deliveries to verify, rather than a tunnel connection.
