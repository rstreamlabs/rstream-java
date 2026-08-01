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
