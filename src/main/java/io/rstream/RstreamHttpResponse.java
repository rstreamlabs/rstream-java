package io.rstream;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Immutable HTTP response written back to an rstream bytestream tunnel. */
public record RstreamHttpResponse(
    int status, String reason, Map<String, List<String>> headers, byte[] body) {
  public RstreamHttpResponse {
    if (status < 100 || status > 599) throw new IllegalArgumentException("status is invalid");
    reason = reason == null || reason.isBlank() ? reasonPhrase(status) : reason;
    validateReason(reason);
    headers = copyHeaders(headers);
    body = body == null ? new byte[0] : body.clone();
  }

  public static RstreamHttpResponse text(int status, String body) {
    return new RstreamHttpResponse(
        status,
        null,
        Map.of("content-type", List.of("text/plain; charset=utf-8")),
        body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8));
  }

  public static RstreamHttpResponse json(int status, String body) {
    return new RstreamHttpResponse(
        status,
        null,
        Map.of("content-type", List.of("application/json")),
        body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8));
  }

  @Override
  public byte[] body() {
    return body.clone();
  }

  private static Map<String, List<String>> copyHeaders(Map<String, List<String>> input) {
    var copy = new TreeMap<String, List<String>>();
    if (input == null) return Map.of();
    input.forEach(
        (name, values) -> {
          if (name == null || name.isBlank()) return;
          var normalizedName = name.toLowerCase(Locale.ROOT);
          validateHeaderName(normalizedName);
          var copiedValues = values == null ? List.<String>of() : List.copyOf(values);
          copiedValues.forEach(RstreamHttpResponse::validateHeaderValue);
          copy.put(normalizedName, copiedValues);
        });
    return Map.copyOf(copy);
  }

  private static void validateHeaderName(String name) {
    for (var index = 0; index < name.length(); index++) {
      var character = name.charAt(index);
      var valid =
          Character.isLetterOrDigit(character)
              || character == '!'
              || character == '#'
              || character == '$'
              || character == '%'
              || character == '&'
              || character == '\''
              || character == '*'
              || character == '+'
              || character == '-'
              || character == '.'
              || character == '^'
              || character == '_'
              || character == '`'
              || character == '|'
              || character == '~';
      if (!valid) throw new IllegalArgumentException("header name is invalid");
    }
  }

  private static void validateHeaderValue(String value) {
    if (value == null || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
      throw new IllegalArgumentException("header value is invalid");
    }
  }

  private static void validateReason(String value) {
    if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
      throw new IllegalArgumentException("reason is invalid");
    }
  }

  private static String reasonPhrase(int status) {
    return switch (status) {
      case 200 -> "OK";
      case 201 -> "Created";
      case 204 -> "No Content";
      case 400 -> "Bad Request";
      case 408 -> "Request Timeout";
      case 404 -> "Not Found";
      case 413 -> "Payload Too Large";
      case 431 -> "Request Header Fields Too Large";
      case 500 -> "Internal Server Error";
      case 501 -> "Not Implemented";
      case 505 -> "HTTP Version Not Supported";
      default -> "";
    };
  }
}
