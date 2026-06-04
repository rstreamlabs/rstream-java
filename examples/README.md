# Examples

Install the SDK snapshot before running examples:

```bash
mvn -DskipTests install
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
