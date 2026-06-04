package io.rstream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/** HTTP/1.1 adapter that serves application handlers directly from tunnel streams. */
public final class RstreamHttpServer {
  private static final byte[] HEADER_END = {'\r', '\n', '\r', '\n'};

  private RstreamHttpServer() {}

  static CompletableFuture<Void> serve(
      BytestreamTunnel tunnel,
      RstreamHttpHandler handler,
      RstreamHttpOptions options,
      ExecutorService executor) {
    Objects.requireNonNull(tunnel, "tunnel");
    Objects.requireNonNull(handler, "handler");
    Objects.requireNonNull(options, "options");
    Objects.requireNonNull(executor, "executor");
    return CompletableFuture.runAsync(
        () -> acceptLoop(tunnel, handler, options, executor), executor);
  }

  private static void acceptLoop(
      BytestreamTunnel tunnel,
      RstreamHttpHandler handler,
      RstreamHttpOptions options,
      ExecutorService executor) {
    while (!tunnel.closed()) {
      try {
        var stream = tunnel.accept();
        executor.submit(() -> handleStream(stream, handler, options));
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        return;
      } catch (RstreamException error) {
        if (!tunnel.closed()) throw error;
        return;
      }
    }
  }

  private static void handleStream(
      RstreamStream stream, RstreamHttpHandler handler, RstreamHttpOptions options) {
    try {
      applyReadTimeout(stream, options);
      var request = readRequest(stream.inputStream(), options);
      var response = handler.handle(request);
      writeResponse(stream, response == null ? RstreamHttpResponse.text(204, "") : response);
    } catch (SocketTimeoutException error) {
      writeQuietly(stream, RstreamHttpResponse.text(408, "Request timeout."));
    } catch (HttpError error) {
      writeQuietly(stream, error.response());
    } catch (Exception error) {
      writeQuietly(stream, RstreamHttpResponse.text(500, "Internal server error."));
    } finally {
      stream.closeQuietly();
    }
  }

  private static RstreamHttpRequest readRequest(InputStream input, RstreamHttpOptions options)
      throws IOException, HttpError {
    var headerBytes = readHeader(input, options.maxHeaderBytes());
    var headerText = new String(headerBytes, StandardCharsets.ISO_8859_1);
    var lines = headerText.substring(0, headerText.length() - HEADER_END.length).split("\r\n");
    if (lines.length == 0) throw badRequest();
    var requestLine = lines[0].split(" ", 3);
    if (requestLine.length != 3) throw badRequest();
    if (requestLine[0].isBlank() || requestLine[1].isBlank()) throw badRequest();
    if (!requestLine[2].equals("HTTP/1.0") && !requestLine[2].equals("HTTP/1.1")) {
      throw new HttpError(RstreamHttpResponse.text(505, "HTTP version not supported."));
    }
    var headers = parseHeaders(lines);
    var body = readBody(input, headers, options);
    var target = requestLine[1];
    return new RstreamHttpRequest(
        requestLine[0],
        target,
        path(target),
        query(target),
        requestLine[2].substring("HTTP/".length()),
        headers,
        body);
  }

  private static byte[] readBody(
      InputStream input, Map<String, List<String>> headers, RstreamHttpOptions options)
      throws IOException, HttpError {
    var encodings = transferEncodings(headers);
    if (!encodings.isEmpty()) {
      if (headers.containsKey("content-length")) throw badRequest();
      if (encodings.stream()
          .anyMatch(value -> !value.equals("chunked") && !value.equals("identity"))) {
        throw new HttpError(
            RstreamHttpResponse.text(501, "Transfer-encoded request bodies are unsupported."));
      }
      if (encodings.contains("chunked")) {
        return readChunkedBody(input, options.maxBodyBytes(), options.maxHeaderBytes());
      }
    }
    var contentLength = contentLength(headers);
    if (contentLength > options.maxBodyBytes()) {
      throw new HttpError(RstreamHttpResponse.text(413, "Payload too large."));
    }
    var body = input.readNBytes((int) contentLength);
    if (body.length != contentLength) throw badRequest();
    return body;
  }

  private static byte[] readHeader(InputStream input, int maxHeaderBytes)
      throws IOException, HttpError {
    var buffer = new ByteArrayOutputStream();
    var matched = 0;
    while (true) {
      var value = input.read();
      if (value < 0) throw badRequest();
      buffer.write(value);
      if (buffer.size() > maxHeaderBytes) {
        throw new HttpError(RstreamHttpResponse.text(431, "Request headers too large."));
      }
      if (value == HEADER_END[matched]) {
        matched++;
        if (matched == HEADER_END.length) return buffer.toByteArray();
      } else {
        matched = value == HEADER_END[0] ? 1 : 0;
      }
    }
  }

  private static Map<String, List<String>> parseHeaders(String[] lines) throws HttpError {
    var headers = new LinkedHashMap<String, List<String>>();
    for (var index = 1; index < lines.length; index++) {
      var line = lines[index];
      if (line.isBlank()) continue;
      var colon = line.indexOf(':');
      if (colon < 1) throw badRequest();
      var name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
      if (!validHeaderName(name)) throw badRequest();
      var value = line.substring(colon + 1).trim();
      headers.computeIfAbsent(name, key -> new ArrayList<>()).add(value);
    }
    return headers;
  }

  private static long contentLength(Map<String, List<String>> headers) throws HttpError {
    var values = headers.getOrDefault("content-length", List.of());
    if (values.isEmpty()) return 0;
    var first = values.get(0);
    if (values.stream().anyMatch(value -> !value.equals(first))) throw badRequest();
    try {
      var length = Long.parseLong(first);
      if (length < 0) throw badRequest();
      return length;
    } catch (NumberFormatException error) {
      throw badRequest();
    }
  }

  private static List<String> transferEncodings(Map<String, List<String>> headers) {
    return headers.getOrDefault("transfer-encoding", List.of()).stream()
        .flatMap(value -> List.of(value.split(",")).stream())
        .map(value -> value.trim().toLowerCase(Locale.ROOT))
        .filter(value -> !value.isEmpty())
        .toList();
  }

  private static byte[] readChunkedBody(InputStream input, int maxBodyBytes, int maxTrailerBytes)
      throws IOException, HttpError {
    var body = new ByteArrayOutputStream();
    while (true) {
      var line = readLine(input, maxTrailerBytes);
      var sizeText = line.split(";", 2)[0].trim();
      long size;
      try {
        size = Long.parseLong(sizeText, 16);
      } catch (NumberFormatException error) {
        throw badRequest();
      }
      if (size < 0 || size > Integer.MAX_VALUE) throw badRequest();
      if (size == 0) {
        readTrailers(input, maxTrailerBytes);
        return body.toByteArray();
      }
      if (body.size() + size > maxBodyBytes) {
        throw new HttpError(RstreamHttpResponse.text(413, "Payload too large."));
      }
      var chunk = input.readNBytes((int) size);
      if (chunk.length != size) throw badRequest();
      body.writeBytes(chunk);
      if (input.read() != '\r' || input.read() != '\n') throw badRequest();
    }
  }

  private static void readTrailers(InputStream input, int maxTrailerBytes)
      throws IOException, HttpError {
    var read = 0;
    while (true) {
      var line = readLine(input, maxTrailerBytes - read);
      read += line.length() + 2;
      if (line.isEmpty()) return;
      var colon = line.indexOf(':');
      if (colon < 1 || !validHeaderName(line.substring(0, colon).trim())) {
        throw badRequest();
      }
    }
  }

  private static String readLine(InputStream input, int maxBytes) throws IOException, HttpError {
    if (maxBytes <= 0) {
      throw new HttpError(RstreamHttpResponse.text(431, "Request headers too large."));
    }
    var buffer = new ByteArrayOutputStream();
    while (true) {
      var value = input.read();
      if (value < 0) throw badRequest();
      if (buffer.size() >= maxBytes) {
        throw new HttpError(RstreamHttpResponse.text(431, "Request headers too large."));
      }
      if (value == '\n') {
        var bytes = buffer.toByteArray();
        if (bytes.length == 0 || bytes[bytes.length - 1] != '\r') throw badRequest();
        return new String(bytes, 0, bytes.length - 1, StandardCharsets.ISO_8859_1);
      }
      buffer.write(value);
    }
  }

  private static boolean validHeaderName(String name) {
    if (name.isBlank()) return false;
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
      if (!valid) return false;
    }
    return true;
  }

  private static String path(String target) {
    var uri = uri(target);
    if (uri != null && uri.getRawPath() != null && !uri.getRawPath().isBlank()) {
      return uri.getRawPath();
    }
    var queryIndex = target.indexOf('?');
    var path = queryIndex >= 0 ? target.substring(0, queryIndex) : target;
    return path.isBlank() ? "/" : path;
  }

  private static String query(String target) {
    var uri = uri(target);
    if (uri != null && uri.getRawQuery() != null) return uri.getRawQuery();
    var queryIndex = target.indexOf('?');
    return queryIndex >= 0 ? target.substring(queryIndex + 1) : "";
  }

  private static URI uri(String target) {
    try {
      return new URI(target);
    } catch (URISyntaxException error) {
      return null;
    }
  }

  private static void writeResponse(RstreamStream stream, RstreamHttpResponse response)
      throws IOException {
    var output = stream.outputStream();
    var body = response.body();
    output.write(
        ("HTTP/1.1 " + response.status() + " " + response.reason() + "\r\n")
            .getBytes(StandardCharsets.ISO_8859_1));
    for (var entry : response.headers().entrySet()) {
      var name = entry.getKey();
      if (name.equalsIgnoreCase("content-length") || name.equalsIgnoreCase("connection")) continue;
      for (var value : entry.getValue()) {
        writeHeader(output, name, value);
      }
    }
    writeHeader(output, "content-length", Integer.toString(body.length));
    writeHeader(output, "connection", "close");
    output.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
    output.write(body);
    output.flush();
  }

  private static void writeHeader(java.io.OutputStream output, String name, String value)
      throws IOException {
    if (name.indexOf('\r') >= 0 || name.indexOf('\n') >= 0) return;
    if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) return;
    output.write((name + ": " + value + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
  }

  private static void writeQuietly(RstreamStream stream, RstreamHttpResponse response) {
    try {
      writeResponse(stream, response);
    } catch (IOException ignored) {
    }
  }

  private static void applyReadTimeout(RstreamStream stream, RstreamHttpOptions options)
      throws IOException {
    var timeout = options.readTimeout();
    if (timeout.isZero()) return;
    var millis = timeout.toMillis();
    if (millis > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("readTimeout is too large");
    }
    stream.socket().setSoTimeout((int) Math.max(1, millis));
  }

  private static HttpError badRequest() {
    return new HttpError(RstreamHttpResponse.text(400, "Bad request."));
  }

  private static final class HttpError extends Exception {
    private final RstreamHttpResponse response;

    private HttpError(RstreamHttpResponse response) {
      this.response = response;
    }

    private RstreamHttpResponse response() {
      return response;
    }
  }
}
