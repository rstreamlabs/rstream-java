package io.rstream;

/** Deliverable webhook event types. */
public enum WebhookEventType {
  CLIENT_CREATED("client.created"),
  CLIENT_DELETED("client.deleted"),
  TUNNEL_CREATED("tunnel.created"),
  TUNNEL_DELETED("tunnel.deleted");

  private final String wireValue;

  WebhookEventType(String wireValue) {
    this.wireValue = wireValue;
  }

  public String wireValue() {
    return wireValue;
  }

  static WebhookEventType fromWireValue(String value) {
    for (WebhookEventType item : values()) if (item.wireValue.equals(value)) return item;
    return null;
  }
}
