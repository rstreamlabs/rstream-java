package io.rstream;

import java.util.List;

/** Browser access policy for a published HTTP tunnel; maxAge is in seconds. */
public record HttpCors(
    List<String> allowedOrigins,
    List<String> allowedMethods,
    List<String> allowedHeaders,
    List<String> exposedHeaders,
    boolean allowCredentials,
    long maxAge) {
  public HttpCors {
    allowedOrigins = List.copyOf(allowedOrigins);
    allowedMethods = allowedMethods == null ? List.of() : List.copyOf(allowedMethods);
    allowedHeaders = allowedHeaders == null ? List.of() : List.copyOf(allowedHeaders);
    exposedHeaders = exposedHeaders == null ? List.of() : List.copyOf(exposedHeaders);
  }

  public HttpCors(List<String> allowedOrigins) {
    this(allowedOrigins, List.of(), List.of(), List.of(), false, 0);
  }
}
