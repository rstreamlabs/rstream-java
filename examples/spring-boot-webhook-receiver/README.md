# Spring Boot webhook receiver

This example receives signed rstream webhook deliveries in a Spring Boot
controller. The SDK verifies the raw request body before the application uses
the parsed event.

Install the local SDK snapshot first:

```bash
mvn -DskipTests install
```

Run the receiver:

```bash
RSTREAM_WEBHOOK_SECRET=whsec_test \
mvn -f examples/spring-boot-webhook-receiver/pom.xml spring-boot:run
```

The endpoint listens on `POST /webhooks/rstream`. Keep the request body bytes
unchanged when passing them to `Webhooks.event(...)`; signature verification is
performed on the exact payload received over HTTP.
