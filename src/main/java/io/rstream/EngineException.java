package io.rstream;

/** Raised when the rstream engine returns a protocol error payload. */
public final class EngineException extends RstreamException {
  private final int engineCode;

  public EngineException(int engineCode, String message) {
    super(message, "ERR_RSTREAM_ENGINE");
    this.engineCode = engineCode;
  }

  public int engineCode() {
    return engineCode;
  }
}
