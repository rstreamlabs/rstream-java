package io.rstream.examples;

import io.rstream.Webhooks;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Sends one synthetic delivery to a local receiver; no rstream account is required. */
public final class SendTestWebhook {
  private SendTestWebhook() {}

  public static void main(String[] args) throws Exception {
    var secret = System.getenv("RSTREAM_WEBHOOK_SECRET");
    var url = URI.create(args.length == 0 ? "http://127.0.0.1:8080/webhooks/rstream" : args[0]);
    var payload =
        "{\"id\":\"evt_local_example\",\"type\":\"tunnel.created\",\"object\":{\"id\":\"tun_local\"}}"
            .getBytes(StandardCharsets.UTF_8);
    var request =
        HttpRequest.newBuilder(url)
            .timeout(Duration.ofSeconds(5))
            .header("content-type", "application/json")
            .header(Webhooks.SIGNATURE_HEADER, Webhooks.sign(payload, secret))
            .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
            .build();
    var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    var response = client.send(request, HttpResponse.BodyHandlers.discarding());
    System.out.println("HTTP " + response.statusCode());
    if (response.statusCode() != 200) throw new IllegalStateException("Local delivery was rejected.");
  }
}
