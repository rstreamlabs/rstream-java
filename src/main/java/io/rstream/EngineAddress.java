package io.rstream;

import java.net.IDN;
import java.util.Locale;
import java.util.regex.Pattern;

record EngineAddress(String host, int port) {
  private static final Pattern SCHEME =
      Pattern.compile("^[a-z][a-z0-9+.-]*://", Pattern.CASE_INSENSITIVE);
  private static final Pattern HOST_LABEL =
      Pattern.compile("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$");

  static EngineAddress parse(String value) {
    var normalized = normalize(value);
    var host = normalized;
    var port = 443;
    if (normalized.startsWith("[")) {
      var closing = normalized.indexOf(']');
      if (closing < 0) throw invalid();
      host = normalized.substring(1, closing);
      if (normalized.length() > closing + 1) {
        if (normalized.charAt(closing + 1) != ':') throw invalid();
        port = parsePort(normalized.substring(closing + 2));
      }
      if (host.isBlank()) throw invalid();
      return new EngineAddress(host, port);
    }
    var separator = normalized.lastIndexOf(':');
    if (separator >= 0) {
      host = normalized.substring(0, separator);
      if (host.indexOf(':') >= 0) throw invalid();
      port = parsePort(normalized.substring(separator + 1));
    }
    if (host.isBlank()) throw invalid();
    var ascii = asciiHost(host);
    validateHost(ascii);
    return new EngineAddress(ascii, port);
  }

  static String normalize(String value) {
    var normalized = value == null ? "" : value.trim();
    if (normalized.isEmpty()
        || SCHEME.matcher(normalized).find()
        || containsForbidden(normalized)) {
      throw invalid();
    }
    return normalized;
  }

  String authority() {
    return host.contains(":") ? "[" + host + "]:" + port : host + ":" + port;
  }

  private static int parsePort(String value) {
    try {
      var port = Integer.parseInt(value);
      if (port < 1 || port > 65_535) throw invalid();
      return port;
    } catch (NumberFormatException error) {
      throw new ConfigurationException(
          "Engine port must be an integer between 1 and 65535.",
          "ERR_RSTREAM_INVALID_ENGINE",
          error);
    }
  }

  private static String asciiHost(String host) {
    try {
      return IDN.toASCII(host).toLowerCase(Locale.ROOT);
    } catch (IllegalArgumentException error) {
      throw invalid();
    }
  }

  private static void validateHost(String host) {
    if (host.length() > 253) throw invalid();
    for (var label : host.split("\\.", -1)) {
      if (!HOST_LABEL.matcher(label).matches()) throw invalid();
    }
  }

  private static boolean containsForbidden(String value) {
    return value.indexOf('/') >= 0
        || value.indexOf('?') >= 0
        || value.indexOf('#') >= 0
        || value.indexOf('@') >= 0
        || value.indexOf('\\') >= 0
        || value.chars().anyMatch(Character::isWhitespace);
  }

  private static ConfigurationException invalid() {
    return new ConfigurationException(
        "Engine must be a host[:port] value.", "ERR_RSTREAM_INVALID_ENGINE");
  }
}
