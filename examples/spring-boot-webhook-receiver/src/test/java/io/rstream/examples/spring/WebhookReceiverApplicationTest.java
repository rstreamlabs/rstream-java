package io.rstream.examples.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.rstream.Webhooks;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;

final class WebhookReceiverApplicationTest {
  @Test
  void acceptsSignedBytesAndRejectsChanges() throws Exception {
    var secret = "whsec_local_example";
    try (var app =
        SpringApplication.run(
            WebhookReceiverApplication.class,
            "--server.address=127.0.0.1",
            "--server.port=0",
            "--RSTREAM_WEBHOOK_SECRET=" + secret)) {
      var client = HttpClient.newHttpClient();
      var server = (WebServerApplicationContext) app;
      var uri = URI.create("http://127.0.0.1:" + server.getWebServer().getPort() + "/webhooks/rstream");
      var body = "{\"id\":\"evt_local\",\"type\":\"tunnel.created\",\"object\":{\"id\":\"tun_local\"}}";
      var signature =
          Webhooks.sign(body.getBytes(StandardCharsets.UTF_8), secret, Instant.now().getEpochSecond());

      assertEquals(200, post(client, uri, body, signature));
      assertEquals(400, post(client, uri, body + " ", signature));
    }
  }

  private static int post(HttpClient client, URI endpoint, String body, String signature)
      throws Exception {
    var request =
        HttpRequest.newBuilder(endpoint)
            .header("Content-Type", "application/json")
            .header(Webhooks.SIGNATURE_HEADER, signature)
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
    return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
  }
}
