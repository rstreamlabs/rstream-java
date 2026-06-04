package io.rstream;

/** Raised when a feature exists in other rstream clients but is not supported by this SDK. */
public final class UnsupportedFeatureException extends RstreamException {
  public UnsupportedFeatureException(String message, String code) {
    super(message, code);
  }
}
