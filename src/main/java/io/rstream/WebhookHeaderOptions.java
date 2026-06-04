package io.rstream;

/** Metadata required to build signed webhook request headers. */
public record WebhookHeaderOptions(String webhookId, String deliveryId, Long timestamp) {
  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private String webhookId;
    private String deliveryId;
    private Long timestamp;

    public Builder webhookId(String webhookId) {
      this.webhookId = webhookId;
      return this;
    }

    public Builder deliveryId(String deliveryId) {
      this.deliveryId = deliveryId;
      return this;
    }

    public Builder timestamp(Long timestamp) {
      this.timestamp = timestamp;
      return this;
    }

    public WebhookHeaderOptions build() {
      return new WebhookHeaderOptions(webhookId, deliveryId, timestamp);
    }
  }
}
