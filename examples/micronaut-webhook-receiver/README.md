# Micronaut webhook receiver

This example verifies signed rstream webhook deliveries inside a Micronaut
controller. It demonstrates the important receiver rule: pass the exact raw
request body bytes to the SDK before trusting the parsed event.

Install the local SDK snapshot first:

```bash
mvn -DskipTests install
```

Run the receiver:

```bash
RSTREAM_WEBHOOK_SECRET=whsec_test \
mvn -f examples/micronaut-webhook-receiver/pom.xml exec:java
```

The endpoint listens on `POST /webhooks/rstream`.
