package io.rstream;

/** Tunnel transport family. */
public enum TunnelType {
  BYTESTREAM("bytestream"),
  DATAGRAM("datagram");

  private final String wireValue;

  TunnelType(String wireValue) {
    this.wireValue = wireValue;
  }

  public String wireValue() {
    return wireValue;
  }

  static TunnelType fromWireValue(String value) {
    for (TunnelType item : values()) if (item.wireValue.equals(value)) return item;
    return null;
  }
}
