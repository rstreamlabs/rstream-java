package io.rstream;

/** Convenience authentication policy applied while creating a tunnel. */
public record TunnelAuth(Boolean token, Boolean rstream, Boolean challenge) {
  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private Boolean token;
    private Boolean rstream;
    private Boolean challenge;

    public Builder token(Boolean token) {
      this.token = token;
      return this;
    }

    public Builder rstream(Boolean rstream) {
      this.rstream = rstream;
      return this;
    }

    public Builder challenge(Boolean challenge) {
      this.challenge = challenge;
      return this;
    }

    public TunnelAuth build() {
      return new TunnelAuth(token, rstream, challenge);
    }
  }
}
