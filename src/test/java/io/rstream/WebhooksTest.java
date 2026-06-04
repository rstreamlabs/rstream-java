package io.rstream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class WebhooksTest {
  @Test
  void webhookSignatureRoundTrip() {
    var payload =
        "{\"id\":\"evt_123\",\"type\":\"tunnel.created\",\"created_at\":\"2026-06-01T12:00:00Z\",\"project_id\":\"proj_123\",\"object\":{\"id\":\"tun_123\",\"labels\":{\"device\":\"camera-1\"}}}";
    var signature =
        Webhooks.sign(payload.getBytes(StandardCharsets.UTF_8), "whsec_test", 1_780_000_000);
    var event =
        Webhooks.event(
            payload.getBytes(StandardCharsets.UTF_8),
            signature,
            "whsec_test",
            Duration.ofSeconds(300),
            Instant.ofEpochSecond(1_780_000_010));
    assertThat(event.id()).isEqualTo("evt_123");
    assertThat(event.type()).isEqualTo(WebhookEventType.TUNNEL_CREATED);
    assertThat(event.projectId()).isEqualTo("proj_123");
    assertThat(event.object().path("id").asText()).isEqualTo("tun_123");
    assertThat(event.object().path("labels").path("device").asText()).isEqualTo("camera-1");
  }

  @Test
  void webhookSignatureAcceptsMatchingSignatureAmongMany() {
    var payload = "{\"id\":\"evt_123\",\"type\":\"client.created\",\"object\":{}}";
    var valid = Webhooks.sign(payload, "whsec_valid", 10);
    var header = valid.replace("v1=", "v1=badbad,v1=");
    var event =
        Webhooks.event(
            payload.getBytes(StandardCharsets.UTF_8),
            header,
            "whsec_valid",
            Duration.ofSeconds(300),
            Instant.ofEpochSecond(10));
    assertThat(event.type()).isEqualTo(WebhookEventType.CLIENT_CREATED);
  }

  @Test
  void webhookSignatureRejectsInvalidSecret() {
    var payload = "{\"id\":\"evt_123\",\"type\":\"client.created\",\"object\":{}}";
    var signature = Webhooks.sign(payload, "whsec_valid", 10);
    assertThatThrownBy(
            () ->
                Webhooks.event(
                    payload.getBytes(StandardCharsets.UTF_8),
                    signature,
                    "whsec_other",
                    Duration.ofSeconds(300),
                    Instant.ofEpochSecond(10)))
        .isInstanceOf(RstreamException.class)
        .hasMessageContaining("Signature verification failed");
  }

  @Test
  void webhookSignatureRejectsStaleTimestamp() {
    var payload = "{\"id\":\"evt_123\",\"type\":\"client.created\",\"object\":{}}";
    var signature = Webhooks.sign(payload, "whsec_valid", 10);
    assertThatThrownBy(
            () ->
                Webhooks.event(
                    payload.getBytes(StandardCharsets.UTF_8),
                    signature,
                    "whsec_valid",
                    Duration.ofSeconds(300),
                    Instant.ofEpochSecond(400)))
        .isInstanceOf(RstreamException.class)
        .hasMessageContaining("outside tolerance");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "v1=abc", "t=abc,v1=abc", "t=10", "t=10,v1=not-hex"})
  void webhookSignatureRejectsMalformedHeaders(String header) {
    var payload = "{\"id\":\"evt_123\",\"type\":\"client.created\",\"object\":{}}";
    assertThatThrownBy(
            () ->
                Webhooks.event(
                    payload.getBytes(StandardCharsets.UTF_8),
                    header,
                    "whsec_valid",
                    Duration.ofSeconds(300),
                    Instant.ofEpochSecond(10)))
        .isInstanceOf(RstreamException.class);
  }

  @Test
  void buildHeadersIncludesDeliveryMetadata() {
    var payload =
        "{\"id\":\"evt_123\",\"type\":\"client.created\",\"object\":{}}"
            .getBytes(StandardCharsets.UTF_8);
    var event = eventFixture(payload);
    var headers =
        Webhooks.buildHeaders(
            payload,
            event,
            "whsec_test",
            WebhookHeaderOptions.builder()
                .webhookId("we_123")
                .deliveryId("del_123")
                .timestamp(1L)
                .build());
    assertThat(headers).containsEntry(Webhooks.EVENT_ID_HEADER, "evt_123");
    assertThat(headers).containsEntry(Webhooks.EVENT_TYPE_HEADER, "client.created");
    assertThat(headers).containsEntry(Webhooks.WEBHOOK_ID_HEADER, "we_123");
    assertThat(headers).containsEntry(Webhooks.DELIVERY_ID_HEADER, "del_123");
    assertThat(headers.get(Webhooks.SIGNATURE_HEADER)).startsWith("t=1,v1=");
  }

  @Test
  void buildHeadersRequiresDeliveryIdentifiers() {
    var payload =
        "{\"id\":\"evt_123\",\"type\":\"client.created\",\"object\":{}}"
            .getBytes(StandardCharsets.UTF_8);
    var event = eventFixture(payload);
    assertThatThrownBy(
            () ->
                Webhooks.buildHeaders(
                    payload,
                    event,
                    "whsec_test",
                    WebhookHeaderOptions.builder().webhookId("we_123").build()))
        .isInstanceOf(RstreamException.class)
        .hasMessageContaining("delivery id");
  }

  @Test
  void webhookHelpersRejectMissingSecretAndInvalidTimestamp() {
    var payload = "{\"id\":\"evt_123\",\"type\":\"client.created\",\"object\":{}}";
    assertThatThrownBy(() -> Webhooks.sign(payload, "", 10))
        .isInstanceOf(RstreamException.class)
        .hasMessageContaining("secret");
    assertThatThrownBy(() -> Webhooks.sign(payload, "whsec_test", -1))
        .isInstanceOf(RstreamException.class)
        .hasMessageContaining("timestamp");
    assertThatThrownBy(
            () ->
                Webhooks.event(
                    payload.getBytes(StandardCharsets.UTF_8),
                    "t=10,v1=00",
                    "whsec_test",
                    Duration.ofSeconds(-1),
                    Instant.ofEpochSecond(10)))
        .isInstanceOf(RstreamException.class)
        .hasMessageContaining("tolerance");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "not-json",
        "[]",
        "{\"id\":\"evt_123\",\"type\":\"stream.summary\",\"object\":{}}",
        "{\"id\":123,\"type\":\"client.created\",\"object\":{}}",
        "{\"id\":\"\",\"type\":\"client.created\",\"object\":{}}",
        "{\"id\":\"evt_123\",\"type\":\"client.created\"}",
        "{\"id\":\"evt_123\",\"type\":\"client.created\",\"object\":[]}"
      })
  void webhookPayloadValidation(String payload) {
    var signature = Webhooks.sign(payload, "whsec_valid", 10);
    assertThatThrownBy(
            () ->
                Webhooks.event(
                    payload.getBytes(StandardCharsets.UTF_8),
                    signature,
                    "whsec_valid",
                    Duration.ofSeconds(300),
                    Instant.ofEpochSecond(10)))
        .isInstanceOf(RstreamException.class);
  }

  @Test
  void generateSigningSecretUsesWebhookPrefix() {
    assertThat(Webhooks.generateSigningSecret()).startsWith("whsec_");
  }

  private static WebhookEvent eventFixture(byte[] payload) {
    return Webhooks.event(
        payload,
        Webhooks.sign(payload, "whsec_test", 1L),
        "whsec_test",
        Duration.ofSeconds(300),
        Instant.ofEpochSecond(1L));
  }
}
