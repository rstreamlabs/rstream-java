package io.rstream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Helpers for signing local webhook payloads and verifying webhook receiver requests. */
public final class Webhooks {
  public static final String SIGNATURE_HEADER = "rstream-signature";
  public static final String EVENT_ID_HEADER = "rstream-event-id";
  public static final String EVENT_TYPE_HEADER = "rstream-event-type";
  public static final String WEBHOOK_ID_HEADER = "rstream-webhook-id";
  public static final String DELIVERY_ID_HEADER = "rstream-delivery-id";
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final SecureRandom RANDOM = new SecureRandom();

  private Webhooks() {}

  public static String generateSigningSecret() {
    var bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return "whsec_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  public static String sign(byte[] payload, String secret) {
    return sign(payload, secret, Instant.now().getEpochSecond());
  }

  public static String sign(String payload, String secret) {
    return sign(payload.getBytes(StandardCharsets.UTF_8), secret);
  }

  public static String sign(String payload, String secret, long timestamp) {
    return sign(payload.getBytes(StandardCharsets.UTF_8), secret, timestamp);
  }

  public static String sign(byte[] payload, String secret, long timestamp) {
    assertSecret(secret);
    if (timestamp < 0) {
      throw new RstreamException(
          "Invalid webhook signature timestamp.", "ERR_RSTREAM_WEBHOOK_SIGNATURE");
    }
    var signedPayload = bytes(Long.toString(timestamp) + ".", payload);
    var signature = hmac(secret, signedPayload);
    return "t=" + timestamp + ",v1=" + hex(signature);
  }

  public static Map<String, String> buildHeaders(
      byte[] payload, WebhookEvent event, String secret, WebhookHeaderOptions options) {
    var out = new LinkedHashMap<String, String>();
    out.put(
        SIGNATURE_HEADER,
        sign(
            payload,
            secret,
            options.timestamp() == null ? Instant.now().getEpochSecond() : options.timestamp()));
    out.put(EVENT_ID_HEADER, required(event.id(), "Webhook event id"));
    out.put(EVENT_TYPE_HEADER, event.type().wireValue());
    out.put(WEBHOOK_ID_HEADER, required(options.webhookId(), "Webhook id"));
    out.put(DELIVERY_ID_HEADER, required(options.deliveryId(), "Webhook delivery id"));
    return Map.copyOf(out);
  }

  public static WebhookEvent event(String payload, String signatureHeader, String secret) {
    return event(payload.getBytes(StandardCharsets.UTF_8), signatureHeader, secret);
  }

  public static WebhookEvent event(byte[] payload, String signatureHeader, String secret) {
    return event(payload, signatureHeader, secret, Duration.ofSeconds(300), Instant.now());
  }

  public static WebhookEvent event(
      byte[] payload,
      String signatureHeader,
      String secret,
      Duration tolerance,
      Instant receivedAt) {
    assertSecret(secret);
    if (tolerance.isNegative()) {
      throw new RstreamException("Invalid signature tolerance.", "ERR_RSTREAM_WEBHOOK_SIGNATURE");
    }
    var parsed = parseSignatureHeader(signatureHeader);
    if (Math.abs(receivedAt.getEpochSecond() - parsed.timestamp()) > tolerance.toSeconds()) {
      throw new RstreamException(
          "Webhook signature timestamp outside tolerance.", "ERR_RSTREAM_WEBHOOK_SIGNATURE");
    }
    var expected = hmac(secret, bytes(parsed.timestamp() + ".", payload));
    var match = parsed.signatures().stream().anyMatch(signature -> matches(signature, expected));
    if (!match) {
      throw new RstreamException("Signature verification failed.", "ERR_RSTREAM_WEBHOOK_SIGNATURE");
    }
    return parseEvent(payload);
  }

  private static WebhookEvent parseEvent(byte[] payload) {
    try {
      var raw = JSON.readTree(payload);
      if (raw == null || !raw.isObject()) {
        throw new RstreamException(
            "Webhook event payload must be a JSON object.", "ERR_RSTREAM_WEBHOOK_PAYLOAD");
      }
      var eventId = optionalString(raw, "id");
      var eventType = WebhookEventType.fromWireValue(optionalString(raw, "type"));
      var createdAt = optionalString(raw, "created_at");
      var object = raw.get("object");
      var normalizedEventId = normalizeOptional(eventId);
      if (normalizedEventId == null) {
        throw new RstreamException("Webhook event id is required.", "ERR_RSTREAM_WEBHOOK_PAYLOAD");
      }
      if (eventType == null) {
        throw new RstreamException(
            "Webhook event type is not deliverable.", "ERR_RSTREAM_WEBHOOK_PAYLOAD");
      }
      if (object == null || !object.isObject()) {
        throw new RstreamException(
            "Webhook event object is required.", "ERR_RSTREAM_WEBHOOK_PAYLOAD");
      }
      return new WebhookEvent(
          normalizedEventId,
          eventType,
          createdAt,
          optionalString(raw, "user_id"),
          optionalString(raw, "workspace_id"),
          optionalString(raw, "project_id"),
          optionalString(raw, "cluster_id"),
          object,
          raw);
    } catch (IOException error) {
      throw new RstreamException(
          "Failed to parse webhook payload.", "ERR_RSTREAM_WEBHOOK_PAYLOAD", error);
    }
  }

  private static SignatureHeader parseSignatureHeader(String header) {
    if (header == null || header.isBlank()) {
      throw new RstreamException("No signature header.", "ERR_RSTREAM_WEBHOOK_SIGNATURE");
    }
    Long timestamp = null;
    var signatures = new java.util.ArrayList<String>();
    for (var part : header.split(",")) {
      var item = part.trim();
      if (item.startsWith("t=")) timestamp = parseTimestamp(item.substring(2));
      if (item.startsWith("v1=")) signatures.add(item.substring(3));
    }
    if (timestamp == null || signatures.isEmpty()) {
      throw new RstreamException(
          "Invalid signature header format.", "ERR_RSTREAM_WEBHOOK_SIGNATURE");
    }
    return new SignatureHeader(timestamp, List.copyOf(signatures));
  }

  private static long parseTimestamp(String value) {
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException error) {
      throw new RstreamException(
          "Invalid signature timestamp.", "ERR_RSTREAM_WEBHOOK_SIGNATURE", error);
    }
  }

  private static byte[] hmac(String secret, byte[] payload) {
    try {
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return mac.doFinal(payload);
    } catch (java.security.GeneralSecurityException error) {
      throw new RstreamException(
          "Failed to sign webhook payload.", "ERR_RSTREAM_WEBHOOK_SIGNATURE", error);
    }
  }

  private static boolean matches(String hexSignature, byte[] expected) {
    if (hexSignature.length() != expected.length * 2) return false;
    try {
      return MessageDigest.isEqual(fromHex(hexSignature), expected);
    } catch (IllegalArgumentException error) {
      return false;
    }
  }

  private static byte[] fromHex(String value) {
    var out = new byte[value.length() / 2];
    for (var index = 0; index < out.length; index++) {
      out[index] = (byte) Integer.parseInt(value.substring(index * 2, index * 2 + 2), 16);
    }
    return out;
  }

  private static String hex(byte[] bytes) {
    var out = new StringBuilder(bytes.length * 2);
    for (var item : bytes) out.append(String.format("%02x", item));
    return out.toString();
  }

  private static byte[] bytes(String prefix, byte[] payload) {
    var prefixBytes = prefix.getBytes(StandardCharsets.UTF_8);
    var out = new byte[prefixBytes.length + payload.length];
    System.arraycopy(prefixBytes, 0, out, 0, prefixBytes.length);
    System.arraycopy(payload, 0, out, prefixBytes.length, payload.length);
    return out;
  }

  private static String required(String value, String label) {
    var normalized = normalizeOptional(value);
    if (normalized == null) {
      throw new RstreamException(label + " is required.", "ERR_RSTREAM_WEBHOOK_PAYLOAD");
    }
    return normalized;
  }

  private static void assertSecret(String secret) {
    if (normalizeOptional(secret) == null) {
      throw new RstreamException(
          "Webhook signing secret is required.", "ERR_RSTREAM_WEBHOOK_SIGNATURE");
    }
  }

  private static String optionalString(JsonNode data, String field) {
    var value = data.get(field);
    if (value == null) return null;
    if (value.isNull()) return null;
    if (value.isTextual()) return value.asText();
    throw new RstreamException(
        "Webhook payload contains an invalid string field.", "ERR_RSTREAM_WEBHOOK_PAYLOAD");
  }

  private static String normalizeOptional(String value) {
    if (value == null) return null;
    var normalized = value.trim();
    return normalized.isEmpty() ? null : normalized;
  }

  private record SignatureHeader(long timestamp, List<String> signatures) {}
}
