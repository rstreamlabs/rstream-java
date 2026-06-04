package io.rstream.examples;

import com.sun.net.httpserver.HttpServer;
import io.rstream.RstreamException;
import io.rstream.Webhooks;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

public final class WebhookReceiver {
  private WebhookReceiver() {}

  public static void main(String[] args) throws Exception {
    var secret = System.getenv("RSTREAM_WEBHOOK_SECRET");
    if (secret == null || secret.isBlank()) {
      throw new IllegalStateException("RSTREAM_WEBHOOK_SECRET is required.");
    }
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 8080), 0);
    server.createContext(
        "/webhooks/rstream",
        exchange -> {
          var payload = exchange.getRequestBody().readAllBytes();
          var signature = exchange.getRequestHeaders().getFirst("rstream-signature");
          try {
            // Webhooks.event verifies the raw body before parsing the JSON payload.
            var event = Webhooks.event(payload, signature, secret);
            var resourceId = event.object().path("id").asText("");
            System.out.printf(
                "Received rstream event: %s %s %s%n",
                event.type().wireValue(), event.id(), resourceId);
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
    System.out.println("Listening on http://127.0.0.1:8080/webhooks/rstream");
  }
}
