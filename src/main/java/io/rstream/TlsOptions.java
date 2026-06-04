package io.rstream;

/** TLS options used when connecting to the rstream engine. */
public record TlsOptions(
    String caFile, String certFile, String keyFile, String serverName, boolean insecureSkipVerify) {
  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private String caFile;
    private String certFile;
    private String keyFile;
    private String serverName;
    private boolean insecureSkipVerify;

    public Builder caFile(String caFile) {
      this.caFile = caFile;
      return this;
    }

    public Builder certFile(String certFile) {
      this.certFile = certFile;
      return this;
    }

    public Builder keyFile(String keyFile) {
      this.keyFile = keyFile;
      return this;
    }

    public Builder serverName(String serverName) {
      this.serverName = serverName;
      return this;
    }

    public Builder insecureSkipVerify(boolean insecureSkipVerify) {
      this.insecureSkipVerify = insecureSkipVerify;
      return this;
    }

    public TlsOptions build() {
      return new TlsOptions(caFile, certFile, keyFile, serverName, insecureSkipVerify);
    }
  }
}
