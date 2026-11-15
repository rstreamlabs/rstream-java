# Java application examples

These projects show direct tunnel integration, private dialing and framework-specific HTTP or webhook handling. Start with `published-http-server` for the smallest running service. They consume the SDK snapshot from this checkout, rather than the published Maven Central version.

Use the JDK and Maven prerequisites in [Contributing](../CONTRIBUTING.md). From the repository root, test and install that snapshot before building an example:

```bash
mvn install
```

| Example | Purpose |
| --- | --- |
| [published-http-server](published-http-server) | Serve HTTP directly from accepted rstream streams. |
| [forward-local-port](forward-local-port) | Publish an already running local TCP or HTTP service through forwarding. |
| [private-dial](private-dial) | Dial a private tunnel by name or ID. |
| [webhook-receiver](webhook-receiver) | Verify webhook deliveries with only JDK APIs. |
| [spring-boot-published-http](spring-boot-published-http) | Serve Spring Boot application code directly from rstream streams. |
| [spring-boot-webhook-receiver](spring-boot-webhook-receiver) | Verify webhook deliveries inside a Spring Boot controller. |
| [quarkus-published-http](quarkus-published-http) | Serve Quarkus CDI application code directly from rstream streams. |
| [micronaut-published-http](micronaut-published-http) | Serve Micronaut application code directly from rstream streams. |
| [micronaut-webhook-receiver](micronaut-webhook-receiver) | Verify webhook deliveries inside a Micronaut controller. |
| [vertx-published-http](vertx-published-http) | Serve Vert.x application code without blocking the event loop. |

All examples use `RstreamClient.fromEnv()`, so runtime configuration comes from
the standard rstream config file or environment variables.

## Run the first server

[Install the CLI](https://rstream.io/docs/getting-started/installation), run `rstream login` and select a project with `rstream project use my-project-endpoint`. Replace the endpoint with your project's value. The HTTP example needs permission to publish a tunnel.

From the repository root:

```sh
mvn -f examples/published-http-server/pom.xml compile exec:java
```

Open the printed forwarding address while the process is running; it returns the server's hostname. Stop the process with an interrupt. Other examples describe their own target services, framework dependencies and receiving endpoints. Webhook receivers need a signing secret and deliveries to verify, rather than a tunnel connection.
