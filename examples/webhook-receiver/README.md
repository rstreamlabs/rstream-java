# Webhook receiver

This JDK HTTP receiver verifies the `rstream-signature` header against the
raw request body before parsing the event with `Webhooks.event(...)`.

From the repository root, install the SDK and start the receiver on loopback
with a local test secret.

```sh
mvn -DskipTests install
RSTREAM_WEBHOOK_SECRET=whsec_local_example \
  mvn -f examples/webhook-receiver/pom.xml compile exec:java
```

In a second terminal at the repository root, send a signed event.

```sh
RSTREAM_WEBHOOK_SECRET=whsec_local_example \
  mvn -f examples/webhook-receiver/pom.xml compile exec:java \
    -Dexec.mainClass=io.rstream.examples.SendTestWebhook
```

The sender prints `HTTP 200` and the receiver logs `evt_local_example`.
Stop the receiver with Ctrl+C. For a configured webhook destination, use
the signing secret returned at creation or rotation. See
[Webhooks](../../docs/003-webhooks.md) for headers and event handling.
