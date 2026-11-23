# Micronaut webhook receiver

This example verifies signed rstream webhook deliveries inside a Micronaut
controller. It demonstrates the important receiver rule: pass the exact raw
request body bytes to the SDK before trusting the parsed event.

For a local test, install the SDK and start the receiver from the repository root.

```bash
mvn -DskipTests install
RSTREAM_WEBHOOK_SECRET=whsec_local_example \
mvn -f examples/micronaut-webhook-receiver/pom.xml compile exec:java
```

The endpoint listens on `POST /webhooks/rstream`.

Use [First verified local delivery](../../docs/003-webhooks.md#first-verified-local-delivery) to send a synthetic signed event without a rstream account. The default endpoint is `http://127.0.0.1:8080/webhooks/rstream`; run only one receiver on that port. Stop it with an interrupt.

`mvn -f examples/micronaut-webhook-receiver/pom.xml test` checks the
controller's verification behavior with a synthetic test-only secret: unchanged signed bytes
are accepted, while a bad signature or modified body produces HTTP 400 rather
than an unhandled server exception. Runtime configuration still needs the
receiver's own signing secret; the test setting does not configure `exec:java`.
