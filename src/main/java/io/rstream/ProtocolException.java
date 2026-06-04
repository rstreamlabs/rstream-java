package io.rstream;

/** Raised when the engine protocol stream is malformed or unexpected. */
public final class ProtocolException extends RstreamException {
  public ProtocolException(String message, String code) {
    super(message, code);
  }

  public ProtocolException(String message, String code, Throwable cause) {
    super(message, code, cause);
  }
}
