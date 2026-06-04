package io.rstream;

/** Published tunnel protocol. */
public enum TunnelProtocol {
  TLS("tls"),
  DTLS("dtls"),
  QUIC("quic"),
  HTTP("http");

  private final String wireValue;

  TunnelProtocol(String wireValue) {
    this.wireValue = wireValue;
  }

  public String wireValue() {
    return wireValue;
  }

  static TunnelProtocol fromWireValue(String value) {
    for (TunnelProtocol item : values()) if (item.wireValue.equals(value)) return item;
    return null;
  }
}
