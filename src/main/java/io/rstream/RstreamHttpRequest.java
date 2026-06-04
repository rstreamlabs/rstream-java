package io.rstream;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/** Immutable HTTP request received through an rstream bytestream tunnel. */
public record RstreamHttpRequest(
    String method,
    String target,
    String path,
    String query,
    String httpVersion,
    Map<String, List<String>> headers,
    byte[] body) {
  public RstreamHttpRequest {
    method = required(method, "method");
    target = required(target, "target");
    path = required(path, "path");
    query = query == null ? "" : query;
    httpVersion = required(httpVersion, "httpVersion");
    headers = copyHeaders(headers);
    body = body == null ? new byte[0] : body.clone();
  }

  public Optional<String> header(String name) {
    var values = headerValues(name);
    return values.isEmpty() ? Optional.empty() : Optional.of(values.get(0));
  }

  public List<String> headerValues(String name) {
    Objects.requireNonNull(name, "name");
    return headers.getOrDefault(name.toLowerCase(Locale.ROOT), List.of());
  }

  public String bodyAsUtf8() {
    return new String(body, StandardCharsets.UTF_8);
  }

  @Override
  public byte[] body() {
    return body.clone();
  }

  private static String required(String value, String name) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    return value;
  }

  private static Map<String, List<String>> copyHeaders(Map<String, List<String>> input) {
    var copy = new TreeMap<String, List<String>>();
    if (input == null) return Map.of();
    input.forEach(
        (name, values) -> {
          if (name == null || name.isBlank()) return;
          copy.put(name.toLowerCase(Locale.ROOT), values == null ? List.of() : List.copyOf(values));
        });
    return Map.copyOf(copy);
  }
}
