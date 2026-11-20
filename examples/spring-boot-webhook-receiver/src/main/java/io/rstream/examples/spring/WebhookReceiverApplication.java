package io.rstream.examples.spring;

import io.rstream.RstreamException;
import io.rstream.Webhooks;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
@RestController
public class WebhookReceiverApplication {
  private final String secret;

  WebhookReceiverApplication(@Value("${RSTREAM_WEBHOOK_SECRET}") String secret) {
    if (secret.isBlank()) throw new IllegalArgumentException("Configure RSTREAM_WEBHOOK_SECRET.");
    this.secret = secret;
  }

  public static void main(String[] args) {
    SpringApplication.run(WebhookReceiverApplication.class, args);
  }

  @PostMapping("/webhooks/rstream")
  ResponseEntity<AcceptedWebhookEvent> receive(
      @RequestBody byte[] payload,
      @RequestHeader(Webhooks.SIGNATURE_HEADER) String signatureHeader) {
    try {
      var event = Webhooks.event(payload, signatureHeader, secret);
      return ResponseEntity.ok(
          new AcceptedWebhookEvent(true, event.id(), event.type().wireValue()));
    } catch (RstreamException invalidDelivery) {
      return ResponseEntity.badRequest().build();
    }
  }

  record AcceptedWebhookEvent(boolean received, String eventId, String eventType) {}
}
