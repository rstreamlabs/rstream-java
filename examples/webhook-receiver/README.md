# Webhook receiver

JDK HTTP receiver that verifies rstream webhook signatures before parsing the
event. The secret must be the endpoint signing secret shown after webhook
creation or rotation.

Use the [catalog setup](../README.md) first. Run this block from the example's own directory; the `../../pom.xml` path selects the SDK root.

```bash
mvn -DskipTests install -f ../../pom.xml
export RSTREAM_WEBHOOK_SECRET="whsec_..."
mvn compile exec:java
```

Keep the raw request body unchanged until `Webhooks.event(...)` has checked the
`rstream-signature` header.

Use [First verified local delivery](../../docs/003-webhooks.md#first-verified-local-delivery) to send a synthetic signed event without a rstream account. The default endpoint is `http://127.0.0.1:8080/webhooks/rstream`; run only one receiver on that port. Stop it with an interrupt.
