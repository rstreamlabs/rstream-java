package io.rstream;

/**
 * Tunnel protocol. Published tunnels use it as the edge protocol; private tunnels use it for
 * protocol dispatch when supported by the engine.
 */
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
