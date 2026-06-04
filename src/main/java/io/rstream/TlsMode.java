package io.rstream;

/** TLS handling mode for published tunnels. */
public enum TlsMode {
  PASSTHROUGH("passthrough"),
  TERMINATED("terminated");

  private final String wireValue;

  TlsMode(String wireValue) {
    this.wireValue = wireValue;
  }

  public String wireValue() {
    return wireValue;
  }

  static TlsMode fromWireValue(String value) {
    for (TlsMode item : values()) if (item.wireValue.equals(value)) return item;
    return null;
  }
}
