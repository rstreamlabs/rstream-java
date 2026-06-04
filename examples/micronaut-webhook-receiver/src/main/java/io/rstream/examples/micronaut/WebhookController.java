package io.rstream.examples.micronaut;

import io.micronaut.http.HttpHeaders;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.Post;
import io.micronaut.serde.annotation.Serdeable;
import io.rstream.Webhooks;

@Controller("/webhooks")
public final class WebhookController {
  @Post(value = "/rstream", consumes = MediaType.APPLICATION_JSON, produces = MediaType.APPLICATION_JSON)
  public AcceptedWebhookEvent receive(
      @Body byte[] payload,
      @Header(Webhooks.SIGNATURE_HEADER) String signatureHeader,
      @Header(HttpHeaders.CONTENT_TYPE) String contentType) {
    var secret = System.getenv("RSTREAM_WEBHOOK_SECRET");
    // Verify the raw request body bytes before using any parsed webhook fields.
    var event = Webhooks.event(payload, signatureHeader, secret);
    return new AcceptedWebhookEvent(true, contentType, event.id(), event.type().wireValue());
  }

  @Serdeable
  public record AcceptedWebhookEvent(
      boolean received, String contentType, String eventId, String eventType) {}
}
