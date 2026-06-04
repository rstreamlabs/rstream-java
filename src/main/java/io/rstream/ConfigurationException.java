package io.rstream;

/** Raised when local rstream configuration cannot be resolved safely. */
public final class ConfigurationException extends RstreamException {
  public ConfigurationException(String message, String code) {
    super(message, code);
  }

  public ConfigurationException(String message, String code, Throwable cause) {
    super(message, code, cause);
  }
}
