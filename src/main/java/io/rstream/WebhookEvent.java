package io.rstream;

import com.fasterxml.jackson.databind.JsonNode;

/** Parsed rstream webhook event. */
public record WebhookEvent(
    String id,
    WebhookEventType type,
    String createdAt,
    String userId,
    String workspaceId,
    String projectId,
    String clusterId,
    JsonNode object,
    JsonNode raw) {
  public WebhookEvent {
    if (object == null || !object.isObject()) {
      throw new RstreamException(
          "Webhook event object is required.", "ERR_RSTREAM_WEBHOOK_PAYLOAD");
    }
    if (raw == null || !raw.isObject()) {
      throw new RstreamException(
          "Webhook event payload is required.", "ERR_RSTREAM_WEBHOOK_PAYLOAD");
    }
  }
}
