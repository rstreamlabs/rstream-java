package io.rstream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class RstreamHttpServerTest {
  @Test
  void serveHttpDispatchesHttpRequestWithoutLocalHop() throws Exception {
    var executor = Executors.newCachedThreadPool();
    try {
      var tunnel = tunnel(executor);
      var serving =
          tunnel.serveHttp(
              request -> {
                assertThat(request.method()).isEqualTo("POST");
                assertThat(request.path()).isEqualTo("/devices");
                assertThat(request.query()).isEqualTo("online=true");
                assertThat(request.header("host")).contains("example.test");
                assertThat(request.bodyAsUtf8()).isEqualTo("device=cam-1");
                return RstreamHttpResponse.json(201, "{\"accepted\":true}");
              });
      try (var pair = StreamPair.open()) {
        assertThat(tunnel.deliver(new RstreamStream(pair.server()))).isTrue();
        pair.client()
            .getOutputStream()
            .write(
                ("POST /devices?online=true HTTP/1.1\r\n"
                        + "Host: example.test\r\n"
                        + "Content-Length: 12\r\n\r\n"
                        + "device=cam-1")
                    .getBytes(StandardCharsets.ISO_8859_1));
        pair.client().getOutputStream().flush();
        assertThat(response(pair.client()))
            .contains("HTTP/1.1 201 Created")
            .contains("content-type: application/json")
            .contains("{\"accepted\":true}");
      } finally {
        tunnel.onClose(null);
        serving.get(2, TimeUnit.SECONDS);
      }
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void serveHttpReturns500WhenHandlerFailsBeforeResponse() throws Exception {
    var executor = Executors.newCachedThreadPool();
    try {
      var tunnel = tunnel(executor);
      var serving =
          tunnel.serveHttp(
              request -> {
                throw new IllegalStateException("boom");
              });
      try (var pair = StreamPair.open()) {
        assertThat(tunnel.deliver(new RstreamStream(pair.server()))).isTrue();
        pair.client()
            .getOutputStream()
            .write("GET /broken HTTP/1.1\r\nHost: example.test\r\n\r\n".getBytes());
        pair.client().getOutputStream().flush();
        assertThat(response(pair.client()))
            .contains("HTTP/1.1 500 Internal Server Error")
            .contains("Internal server error.");
      } finally {
        tunnel.onClose(null);
        serving.get(2, TimeUnit.SECONDS);
      }
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void serveHttpRejectsMalformedAndUnsupportedRequests() throws Exception {
    var executor = Executors.newCachedThreadPool();
    try {
      var tunnel = tunnel(executor);
      var serving = tunnel.serveHttp(request -> RstreamHttpResponse.text(200, "ok"));
      try (var malformed = StreamPair.open()) {
        assertThat(tunnel.deliver(new RstreamStream(malformed.server()))).isTrue();
        malformed.client().getOutputStream().write("BAD\r\n\r\n".getBytes());
        malformed.client().getOutputStream().flush();
        assertThat(response(malformed.client())).contains("HTTP/1.1 400 Bad Request");
      }
      try (var transferEncoded = StreamPair.open()) {
        assertThat(tunnel.deliver(new RstreamStream(transferEncoded.server()))).isTrue();
        transferEncoded
            .client()
            .getOutputStream()
            .write(
                ("POST / HTTP/1.1\r\n"
                        + "Host: example.test\r\n"
                        + "Transfer-Encoding: gzip\r\n\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
        transferEncoded.client().getOutputStream().flush();
        assertThat(response(transferEncoded.client())).contains("HTTP/1.1 501 Not Implemented");
      }
      try (var unsupportedVersion = StreamPair.open()) {
        assertThat(tunnel.deliver(new RstreamStream(unsupportedVersion.server()))).isTrue();
        unsupportedVersion
            .client()
            .getOutputStream()
            .write("GET / HTTP/2.0\r\nHost: example.test\r\n\r\n".getBytes());
        unsupportedVersion.client().getOutputStream().flush();
        assertThat(response(unsupportedVersion.client()))
            .contains("HTTP/1.1 505 HTTP Version Not Supported");
      }
      tunnel.onClose(null);
      serving.get(2, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void serveHttpAcceptsChunkedRequestBody() throws Exception {
    var executor = Executors.newCachedThreadPool();
    try {
      var tunnel = tunnel(executor);
      var serving =
          tunnel.serveHttp(
              request -> {
                assertThat(request.bodyAsUtf8()).isEqualTo("hello world");
                return RstreamHttpResponse.text(200, request.bodyAsUtf8().toUpperCase());
              });
      try (var pair = StreamPair.open()) {
        assertThat(tunnel.deliver(new RstreamStream(pair.server()))).isTrue();
        pair.client()
            .getOutputStream()
            .write(
                ("POST /chunked HTTP/1.1\r\n"
                        + "Host: example.test\r\n"
                        + "Transfer-Encoding: chunked\r\n\r\n"
                        + "5\r\nhello\r\n"
                        + "6\r\n world\r\n"
                        + "0\r\nX-Trailer: ok\r\n\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
        pair.client().getOutputStream().flush();
        assertThat(response(pair.client())).contains("HTTP/1.1 200 OK").contains("HELLO WORLD");
      } finally {
        tunnel.onClose(null);
        serving.get(2, TimeUnit.SECONDS);
      }
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void serveHttpAppliesConfiguredLimitsAndTimeouts() throws Exception {
    var executor = Executors.newCachedThreadPool();
    try {
      var tunnel = tunnel(executor);
      var options =
          RstreamHttpOptions.builder()
              .maxHeaderBytes(80)
              .maxBodyBytes(4)
              .readTimeout(Duration.ofMillis(50))
              .build();
      var serving = tunnel.serveHttp(request -> RstreamHttpResponse.text(200, "ok"), options);
      try (var headers = StreamPair.open()) {
        assertThat(tunnel.deliver(new RstreamStream(headers.server()))).isTrue();
        headers
            .client()
            .getOutputStream()
            .write(
                ("GET / HTTP/1.1\r\n"
                        + "Host: example.test\r\n"
                        + "X-Long-Header: 012345678901234567890123456789\r\n\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
        headers.client().getOutputStream().flush();
        assertThat(response(headers.client()))
            .contains("HTTP/1.1 431 Request Header Fields Too Large");
      }
      try (var body = StreamPair.open()) {
        assertThat(tunnel.deliver(new RstreamStream(body.server()))).isTrue();
        body.client()
            .getOutputStream()
            .write(
                ("POST / HTTP/1.1\r\n"
                        + "Host: example.test\r\n"
                        + "Content-Length: 5\r\n\r\n"
                        + "abcde")
                    .getBytes(StandardCharsets.ISO_8859_1));
        body.client().getOutputStream().flush();
        assertThat(response(body.client())).contains("HTTP/1.1 413 Payload Too Large");
      }
      try (var timeout = StreamPair.open()) {
        assertThat(tunnel.deliver(new RstreamStream(timeout.server()))).isTrue();
        timeout.client().getOutputStream().write("GET /slow HTTP/1.1\r\n".getBytes());
        timeout.client().getOutputStream().flush();
        assertThat(response(timeout.client())).contains("HTTP/1.1 408 Request Timeout");
      }
      tunnel.onClose(null);
      serving.get(2, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void serveHttpRejectsConflictingContentLengthHeaders() throws Exception {
    var executor = Executors.newCachedThreadPool();
    try {
      var tunnel = tunnel(executor);
      var serving = tunnel.serveHttp(request -> RstreamHttpResponse.text(200, "ok"));
      try (var pair = StreamPair.open()) {
        assertThat(tunnel.deliver(new RstreamStream(pair.server()))).isTrue();
        pair.client()
            .getOutputStream()
            .write(
                ("POST / HTTP/1.1\r\n"
                        + "Host: example.test\r\n"
                        + "Content-Length: 1\r\n"
                        + "Content-Length: 2\r\n\r\n"
                        + "ab")
                    .getBytes(StandardCharsets.ISO_8859_1));
        pair.client().getOutputStream().flush();
        assertThat(response(pair.client())).contains("HTTP/1.1 400 Bad Request");
      } finally {
        tunnel.onClose(null);
        serving.get(2, TimeUnit.SECONDS);
      }
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void serveHttpHandlesConcurrentStreams() throws Exception {
    var executor = Executors.newCachedThreadPool();
    try {
      var tunnel = tunnel(executor);
      var serving = tunnel.serveHttp(request -> RstreamHttpResponse.text(200, request.path()));
      var pairs = new ArrayList<StreamPair>();
      try {
        for (var index = 0; index < 16; index++) {
          var pair = StreamPair.open();
          pairs.add(pair);
          assertThat(tunnel.deliver(new RstreamStream(pair.server()))).isTrue();
        }
        var requests = new ArrayList<CompletableFuture<String>>();
        for (var index = 0; index < pairs.size(); index++) {
          var pair = pairs.get(index);
          var path = "/request-" + index;
          requests.add(
              CompletableFuture.supplyAsync(
                  () -> {
                    try {
                      pair.client()
                          .getOutputStream()
                          .write(
                              ("GET " + path + " HTTP/1.1\r\nHost: example.test\r\n\r\n")
                                  .getBytes(StandardCharsets.ISO_8859_1));
                      pair.client().getOutputStream().flush();
                      return response(pair.client());
                    } catch (IOException error) {
                      throw new RstreamException(
                          "Failed to exchange test HTTP request.", "ERR_RSTREAM_TEST", error);
                    }
                  },
                  executor));
        }
        for (var index = 0; index < requests.size(); index++) {
          assertThat(requests.get(index).get(2, TimeUnit.SECONDS))
              .contains("HTTP/1.1 200 OK")
              .contains("/request-" + index);
        }
      } finally {
        for (var pair : pairs) pair.close();
        tunnel.onClose(null);
        serving.get(2, TimeUnit.SECONDS);
      }
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void requestAndResponseDefensivelyCopyMutableInputs() {
    var body = "abc".getBytes(StandardCharsets.UTF_8);
    var request =
        new RstreamHttpRequest("GET", "/", "/", "", "1.1", Map.of("x-test", List.of("one")), body);
    body[0] = 'z';
    assertThat(request.bodyAsUtf8()).isEqualTo("abc");
    assertThat(request.header("X-Test")).contains("one");

    var response = RstreamHttpResponse.text(200, "ok");
    assertThatThrownBy(() -> response.headers().put("x", List.of("y")))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(
            () -> new RstreamHttpResponse(200, null, Map.of("bad header", List.of("ok")), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("header name");
    assertThatThrownBy(
            () -> new RstreamHttpResponse(200, null, Map.of("x-test", List.of("bad\r\n")), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("header value");
    assertThatThrownBy(() -> new RstreamHttpResponse(200, "OK\r\nbad", Map.of(), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("reason");
  }

  @Test
  void acceptTimeoutStillWorksWithHttpServerSurface() throws Exception {
    var executor = Executors.newSingleThreadExecutor();
    try {
      var tunnel = tunnel(executor);
      assertThat(tunnel.accept(Duration.ofMillis(10))).isNull();
      assertThatThrownBy(() -> tunnel.serveHttp(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("handler");
      assertThatThrownBy(
              () -> tunnel.serveHttp(request -> RstreamHttpResponse.text(200, "ok"), null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("options");
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void httpOptionsValidateLimits() {
    assertThat(RstreamHttpOptions.defaults().maxHeaderBytes()).isGreaterThan(0);
    assertThatThrownBy(() -> RstreamHttpOptions.builder().maxHeaderBytes(0).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxHeaderBytes");
    assertThatThrownBy(() -> RstreamHttpOptions.builder().maxBodyBytes(-1).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxBodyBytes");
    assertThatThrownBy(
            () -> RstreamHttpOptions.builder().readTimeout(Duration.ofMillis(-1)).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("readTimeout");
    assertThatThrownBy(() -> RstreamHttpOptions.builder().readTimeout(Duration.ofDays(30)).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("readTimeout");
  }

  private static BytestreamTunnel tunnel(java.util.concurrent.ExecutorService executor) {
    return new BytestreamTunnel(
        null, TunnelProperties.builder().id("tun_1").name("web").build(), executor);
  }

  private static String response(Socket socket) throws IOException {
    var buffer = new byte[4096];
    var output = new java.io.ByteArrayOutputStream();
    var input = socket.getInputStream();
    while (true) {
      try {
        var read = input.read(buffer);
        if (read < 0) break;
        output.write(buffer, 0, read);
      } catch (java.net.SocketException error) {
        if (output.size() == 0) throw error;
        break;
      }
    }
    return output.toString(StandardCharsets.ISO_8859_1);
  }

  private record StreamPair(Socket client, Socket server) implements Closeable {
    static StreamPair open() throws Exception {
      try (var listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
        var accepted = CompletableFuture.supplyAsync(() -> accept(listener));
        var client = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort());
        var server = accepted.get(2, TimeUnit.SECONDS);
        return new StreamPair(client, server);
      }
    }

    @Override
    public void close() throws IOException {
      client.close();
      server.close();
    }

    private static Socket accept(ServerSocket listener) {
      try {
        return listener.accept();
      } catch (IOException error) {
        throw new RstreamException("Failed to accept test socket.", "ERR_RSTREAM_TEST", error);
      }
    }
  }
}
