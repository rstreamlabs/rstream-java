package io.rstream;

import java.time.Duration;

/** Runtime limits for the direct HTTP/1.1 tunnel adapter. */
public record RstreamHttpOptions(int maxHeaderBytes, int maxBodyBytes, Duration readTimeout) {
  private static final int DEFAULT_MAX_HEADER_BYTES = 64 * 1024;
  private static final int DEFAULT_MAX_BODY_BYTES = 16 * 1024 * 1024;
  private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(30);

  public RstreamHttpOptions {
    if (maxHeaderBytes <= 0) {
      throw new IllegalArgumentException("maxHeaderBytes must be greater than zero");
    }
    if (maxBodyBytes < 0) {
      throw new IllegalArgumentException("maxBodyBytes must not be negative");
    }
    readTimeout = readTimeout == null ? DEFAULT_READ_TIMEOUT : readTimeout;
    if (readTimeout.isNegative()) {
      throw new IllegalArgumentException("readTimeout must not be negative");
    }
    if (!readTimeout.isZero() && readTimeout.toMillis() > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("readTimeout is too large");
    }
  }

  public static RstreamHttpOptions defaults() {
    return builder().build();
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private int maxHeaderBytes = DEFAULT_MAX_HEADER_BYTES;
    private int maxBodyBytes = DEFAULT_MAX_BODY_BYTES;
    private Duration readTimeout = DEFAULT_READ_TIMEOUT;

    private Builder() {}

    public Builder maxHeaderBytes(int value) {
      maxHeaderBytes = value;
      return this;
    }

    public Builder maxBodyBytes(int value) {
      maxBodyBytes = value;
      return this;
    }

    public Builder readTimeout(Duration value) {
      readTimeout = value;
      return this;
    }

    public RstreamHttpOptions build() {
      return new RstreamHttpOptions(maxHeaderBytes, maxBodyBytes, readTimeout);
    }
  }
}
