package io.rstream.examples.micronaut;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.micronaut.http.HttpStatus;
import io.micronaut.http.exceptions.HttpStatusException;
import io.rstream.Webhooks;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class WebhookControllerTest {
  private static final byte[] PAYLOAD =
      "{\"id\":\"evt_local\",\"type\":\"tunnel.created\",\"object\":{\"id\":\"tun_local\"}}"
          .getBytes(StandardCharsets.UTF_8);

  @Test
  void acceptsUnchangedSignedBytesAndMapsUntrustedDeliveriesToBadRequest() {
    var controller = new WebhookController();
    var signature = Webhooks.sign(PAYLOAD, System.getenv("RSTREAM_WEBHOOK_SECRET"));
    var event = controller.receive(PAYLOAD, signature, "application/json");
    assertEquals("evt_local", event.eventId());
    var rejected =
        assertThrows(
            HttpStatusException.class,
            () -> controller.receive(PAYLOAD, "t=1,v1=00", "application/json"));
    assertEquals(HttpStatus.BAD_REQUEST, rejected.getStatus());
    var changed = PAYLOAD.clone();
    changed[changed.length - 2] = ' ';
    assertThrows(
        HttpStatusException.class,
        () -> controller.receive(changed, signature, "application/json"));
  }
}
