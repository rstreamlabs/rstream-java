package io.rstream;

/** Base runtime exception raised by the rstream Java SDK. */
public class RstreamException extends RuntimeException {
  private final String code;

  public RstreamException(String message, String code) {
    super(message);
    this.code = code;
  }

  public RstreamException(String message, String code, Throwable cause) {
    super(message, cause);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
