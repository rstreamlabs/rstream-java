package io.rstream;

/** HTTP version requested for HTTP tunnels. */
public enum HttpVersion {
  HTTP_1_1("http/1.1"),
  H2("h2"),
  H2C("h2c"),
  H3("h3");

  private final String wireValue;

  HttpVersion(String wireValue) {
    this.wireValue = wireValue;
  }

  public String wireValue() {
    return wireValue;
  }

  static HttpVersion fromWireValue(String value) {
    for (HttpVersion item : values()) if (item.wireValue.equals(value)) return item;
    return null;
  }
}
