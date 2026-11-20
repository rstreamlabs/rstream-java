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
RSTREAM_WEBHOOK_SECRET=whsec_local_example \
mvn -f examples/spring-boot-webhook-receiver/pom.xml spring-boot:run
```

The endpoint listens on `POST /webhooks/rstream`. Keep the request body bytes
unchanged when passing them to `Webhooks.event(...)`; signature verification is
performed on the exact payload received over HTTP.

Use [First verified local delivery](../../docs/003-webhooks.md#first-verified-local-delivery) to send a synthetic signed event without a rstream account. The default endpoint is `http://127.0.0.1:8080/webhooks/rstream`; run only one receiver on that port. Stop it with an interrupt.
