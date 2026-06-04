# Webhook receiver

JDK HTTP receiver that verifies rstream webhook signatures before parsing the
event. The secret must be the endpoint signing secret shown after webhook
creation or rotation.

```bash
mvn -DskipTests install -f ../../pom.xml
export RSTREAM_WEBHOOK_SECRET="whsec_..."
mvn compile exec:java
```

Keep the raw request body unchanged until `Webhooks.event(...)` has checked the
`rstream-signature` header.
