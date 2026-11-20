# Webhooks

The SDK includes a small helper for webhook receivers. It verifies the raw
request body against the `rstream-signature` header and returns a parsed event.

## JDK HTTP receiver

```java
import com.sun.net.httpserver.HttpServer;
import io.rstream.RstreamException;
import io.rstream.Webhooks;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

public final class WebhookReceiver {
  public static void main(String[] args) throws Exception {
    var secret = System.getenv("RSTREAM_WEBHOOK_SECRET");
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 8080), 0);
    server.createContext(
        "/webhooks/rstream",
        exchange -> {
          var payload = exchange.getRequestBody().readAllBytes();
          var signature = exchange.getRequestHeaders().getFirst("rstream-signature");
          try {
            var event = Webhooks.event(payload, signature, secret);
            System.out.println(event.type().wireValue() + " " + event.id());
            exchange.sendResponseHeaders(200, 0);
          } catch (RstreamException error) {
            var body = error.getMessage().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(400, body.length);
            exchange.getResponseBody().write(body);
          } finally {
            exchange.close();
          }
        });
    server.start();
  }
}
```

`event.id()` is the idempotency key for the webhook event. `event.object()` and
`event.raw()` are Jackson `JsonNode` values; use normal Jackson accessors such
as `event.object().path("id").asText("")` for resource fields.

## Event payload

| Field | Description |
| --- | --- |
| `id` | Event identifier, suitable for idempotency. |
| `type` | One of `client.created`, `client.deleted`, `tunnel.created`, or `tunnel.deleted`. |
| `created_at` | Event creation timestamp, when available. |
| `object` | Tunnel or client resource carried by the event. |
| `workspace_id` | Workspace identifier, when available. |
| `project_id` | Tunnel project identifier, when available. |
| `cluster_id` | Engine cluster identifier, when available. |

Select the four supported event types in the webhook destination's event
filters. `Webhooks.event()` rejects WebTTY events even when their signatures are
valid; use a receiver that supports the [service event catalog](https://rstream.io/docs/integrations/webhooks#receiver-compatibility)
when subscribing to those events.

## Headers

| Header | Purpose |
| --- | --- |
| `rstream-signature` | HMAC signature for the raw body. |
| `rstream-event-id` | Event identifier. |
| `rstream-event-type` | Event type. |
| `rstream-webhook-id` | Webhook endpoint identifier. |
| `rstream-delivery-id` | Delivery identifier. |

The signature header uses `t=<timestamp>,v1=<hex hmac>`. During rotation, a
single request may contain more than one `v1` signature.

## Receiver rules

Keep the raw request body unchanged until signature verification is complete.
Parsing, re-serializing, trimming, or decoding the body before verification will
change the signed bytes.

Return a 2xx status only after the event has been persisted or fully handled.
Use `event.id()` as an idempotency key if the receiver writes state, because a
delivery can be retried.

## First verified local delivery

No rstream account is required for the synthetic receiver check. From the
repository root, run `mvn install`, then use two terminals with the same local
fixture secret (`whsec_local_example`, not a production destination secret).

Terminal one starts the JDK receiver on loopback:

```sh
RSTREAM_WEBHOOK_SECRET=whsec_local_example \
mvn -f examples/webhook-receiver/pom.xml compile exec:java
```

Terminal two sends a freshly signed event using the SDK helper:

```sh
RSTREAM_WEBHOOK_SECRET=whsec_local_example \
mvn -f examples/webhook-receiver/pom.xml compile exec:java \
  -Dexec.mainClass=io.rstream.examples.SendTestWebhook
```

The sender prints `HTTP 200`. The JDK receiver logs `evt_local_example`;
the Spring and Micronaut receivers return it in the response body. An
invalid signature returns 400. Use the same sender for Spring/Micronaut receivers on
`http://127.0.0.1:8080/webhooks/rstream`, or pass a different loopback URL in
`-Dexec.args`. Do not run several receivers on the same port.

To receive live local events, keep the receiver running. In a second terminal,
select a project with access to its tunnel events and use the same signing secret
as the receiver.

```sh
rstream events --webhook --webhook-secret whsec_local_example \
  --events tunnel.created,tunnel.deleted \
  --forward-to http://127.0.0.1:8080/webhooks/rstream
```

Create and stop a [tunnel](000-getting-started.md#create-the-application)
under that project to see the receiver log the events. For a hosted destination,
use a reachable HTTPS receiver and keep its signing secret in a secret store.
