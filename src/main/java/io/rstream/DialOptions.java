package io.rstream;

/** Options accepted by {@link RstreamClient#dial(String, DialOptions)}. */
public record DialOptions(String token, Boolean zeroRtt) {
  public static Builder builder() {
    return new Builder();
  }

  public static DialOptions defaults() {
    return builder().build();
  }

  public static final class Builder {
    private String token;
    private Boolean zeroRtt;

    public Builder token(String token) {
      this.token = token;
      return this;
    }

    public Builder zeroRtt(Boolean zeroRtt) {
      this.zeroRtt = zeroRtt;
      return this;
    }

    public DialOptions build() {
      return new DialOptions(token, zeroRtt);
    }
  }
}
