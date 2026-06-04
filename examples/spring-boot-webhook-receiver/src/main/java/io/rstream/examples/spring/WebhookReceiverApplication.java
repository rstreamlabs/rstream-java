package io.rstream.examples.spring;

import io.rstream.Webhooks;
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
  public static void main(String[] args) {
    SpringApplication.run(WebhookReceiverApplication.class, args);
  }

  @PostMapping("/webhooks/rstream")
  ResponseEntity<AcceptedWebhookEvent> receive(
      @RequestBody byte[] payload,
      @RequestHeader(Webhooks.SIGNATURE_HEADER) String signatureHeader) {
    var secret = System.getenv("RSTREAM_WEBHOOK_SECRET");
    // Verify the raw request body bytes before using the parsed webhook event.
    var event = Webhooks.event(payload, signatureHeader, secret);
    return ResponseEntity.ok(
        new AcceptedWebhookEvent(true, event.id(), event.type().wireValue()));
  }

  record AcceptedWebhookEvent(boolean received, String eventId, String eventType) {}
}
