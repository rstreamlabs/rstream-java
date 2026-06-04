package io.rstream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.Test;

final class RuntimeRealEngineIT {
  @Test
  void privateTunnelCanBeDialedByNameAndIdWithHandshakeModes() throws Exception {
    assumeRealEngine();
    var name = "java-e2e-" + UUID.randomUUID().toString().substring(0, 8);
    try (var http = LocalHttpServer.start();
        var client = client();
        var control = client.connect()) {
      var tunnel =
          control.createTunnel(CreateTunnelOptions.builder().name(name).publish(false).build());
      var forwarding = tunnel.forwardTo(http.host(), http.port());
      try {
        for (var target : new String[] {name, tunnel.id()}) {
          for (var zeroRtt : new boolean[] {false, true}) {
            assertPrivateHttpDial(target, zeroRtt, http.responseBody());
          }
        }
      } finally {
        control.closeTunnel(tunnel.id());
        forwarding.get(10, TimeUnit.SECONDS);
      }
    }
  }

  @Test
  void privateTunnelHandlesConcurrentDialers() throws Exception {
    assumeRealEngine();
    var name = "java-concurrent-" + UUID.randomUUID().toString().substring(0, 8);
    try (var http = LocalHttpServer.start();
        var client = client();
        var control = client.connect()) {
      var tunnel =
          control.createTunnel(CreateTunnelOptions.builder().name(name).publish(false).build());
      var forwarding = tunnel.forwardTo(http.host(), http.port());
      try {
        var requests =
            java.util.stream.IntStream.range(0, 12)
                .mapToObj(
                    index ->
                        CompletableFuture.runAsync(
                            () -> {
                              try {
                                var target = index % 2 == 0 ? name : tunnel.id();
                                assertPrivateHttpDial(target, index % 3 == 0, http.responseBody());
                              } catch (IOException error) {
                                throw new RstreamException(
                                    "Concurrent real-engine dial failed.",
                                    "ERR_RSTREAM_E2E_DIAL",
                                    error);
                              }
                            }))
                .toList();
        for (var request : requests) request.get(20, TimeUnit.SECONDS);
      } finally {
        control.closeTunnel(tunnel.id());
        forwarding.get(10, TimeUnit.SECONDS);
      }
    }
  }

  @Test
  void privateTunnelServeHttpHandlesDialedRequests() throws Exception {
    assumeRealEngine();
    var name = "java-http-" + UUID.randomUUID().toString().substring(0, 8);
    var responseBody = "java-serve-http:" + name;
    try (var client = client();
        var control = client.connect()) {
      var tunnel =
          control.createTunnel(CreateTunnelOptions.builder().name(name).publish(false).build());
      var serving = tunnel.serveHttp(request -> RstreamHttpResponse.text(200, responseBody));
      try {
        for (var target : new String[] {name, tunnel.id()}) {
          for (var zeroRtt : new boolean[] {false, true}) {
            assertPrivateHttpDial(target, zeroRtt, responseBody);
          }
        }
      } finally {
        control.closeTunnel(tunnel.id());
        serving.get(10, TimeUnit.SECONDS);
      }
    }
  }

  @Test
  void connectAsyncOpensAControlChannel() throws Exception {
    assumeRealEngine();
    try (var client = client();
        var control = client.connectAsync().get(20, TimeUnit.SECONDS)) {
      assertThat(control.serverDetails()).isNotNull();
      assertThat(control.closed()).isFalse();
    }
  }

  @Test
  void manualPrivateTunnelAcceptRoundTripsBytes() throws Exception {
    assumeRealEngine();
    var name = "java-manual-" + UUID.randomUUID().toString().substring(0, 8);
    var executor = Executors.newSingleThreadExecutor();
    try (var client = client();
        var control = client.connect()) {
      var tunnel =
          control.createTunnel(CreateTunnelOptions.builder().name(name).publish(false).build());
      var dial =
          CompletableFuture.supplyAsync(
              () -> client.dial(name, DialOptions.builder().zeroRtt(false).build()), executor);
      try (var accepted = tunnel.accept(Duration.ofSeconds(15));
          var stream = dial.get(15, TimeUnit.SECONDS)) {
        stream.outputStream().write("ping".getBytes(StandardCharsets.UTF_8));
        stream.outputStream().flush();
        assertThat(accepted.inputStream().readNBytes(4))
            .isEqualTo("ping".getBytes(StandardCharsets.UTF_8));
        accepted.outputStream().write("pong".getBytes(StandardCharsets.UTF_8));
        accepted.outputStream().flush();
        assertThat(stream.inputStream().readNBytes(4))
            .isEqualTo("pong".getBytes(StandardCharsets.UTF_8));
      } finally {
        control.closeTunnel(tunnel.id());
      }
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void publishedHttpTunnelServesLocalEndpointWhenEnabled() throws Exception {
    assumeRealEngine();
    assumeTrue("1".equals(System.getenv("RSTREAM_JAVA_E2E_PUBLISHED")));
    var name = "java-published-" + UUID.randomUUID().toString().substring(0, 8);
    try (var http = LocalHttpServer.start();
        var client = client();
        var control = client.connect()) {
      var tunnel =
          control.createTunnel(
              CreateTunnelOptions.builder()
                  .name(name)
                  .publish(true)
                  .protocol(TunnelProtocol.HTTP)
                  .httpVersion(HttpVersion.HTTP_1_1)
                  .build());
      var forwarding = tunnel.forwardTo(http.host(), http.port());
      try {
        var response = publishedGet(tunnel.forwardingAddress());
        assertPublishedResponse(response, http.responseBody());
      } finally {
        control.closeTunnel(tunnel.id());
        forwarding.get(10, TimeUnit.SECONDS);
      }
    }
  }

  @Test
  void publishedHttpTunnelServeHttpWhenEnabled() throws Exception {
    assumeRealEngine();
    assumeTrue("1".equals(System.getenv("RSTREAM_JAVA_E2E_PUBLISHED")));
    var name = "java-published-direct-" + UUID.randomUUID().toString().substring(0, 8);
    var responseBody = "java-published-serve-http:" + name;
    try (var client = client();
        var control = client.connect()) {
      var tunnel =
          control.createTunnel(
              CreateTunnelOptions.builder()
                  .name(name)
                  .publish(true)
                  .protocol(TunnelProtocol.HTTP)
                  .httpVersion(HttpVersion.HTTP_1_1)
                  .build());
      var serving = tunnel.serveHttp(request -> RstreamHttpResponse.text(200, responseBody));
      try {
        var response = publishedGet(tunnel.forwardingAddress());
        assertPublishedResponse(response, responseBody);
      } finally {
        control.closeTunnel(tunnel.id());
        serving.get(10, TimeUnit.SECONDS);
      }
    }
  }

  private static String publishedGet(String forwardingAddress) throws Exception {
    var uri = URI.create(forwardingAddress);
    if ("1".equals(System.getenv("RSTREAM_JAVA_E2E_PUBLISHED_TLS_INSECURE"))) {
      return insecureTlsGet(uri);
    }
    var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).GET().build();
    var response = HttpClient.newHttpClient().send(request, BodyHandlers.ofString());
    return "HTTP " + response.statusCode() + "\n" + response.body();
  }

  private static void assertPublishedResponse(String response, String expectedBody) {
    assertThat(response).contains(expectedBody);
    assertThat(response.contains("HTTP 200") || response.contains("HTTP/1.1 200")).isTrue();
  }

  private static String insecureTlsGet(URI uri) throws Exception {
    var port = uri.getPort() > 0 ? uri.getPort() : 443;
    var connectHost = envOrDefault("RSTREAM_JAVA_E2E_PUBLISHED_CONNECT_HOST", uri.getHost());
    var context = SSLContext.getInstance("TLS");
    context.init(null, new TrustManager[] {trustAll()}, null);
    try (var raw = new Socket(connectHost, port);
        var socket =
            (SSLSocket) context.getSocketFactory().createSocket(raw, uri.getHost(), port, true)) {
      socket.setSoTimeout(10_000);
      var parameters = socket.getSSLParameters();
      parameters.setServerNames(List.of(new SNIHostName(uri.getHost())));
      socket.setSSLParameters(parameters);
      socket.startHandshake();
      var path = uri.getRawPath() == null || uri.getRawPath().isBlank() ? "/" : uri.getRawPath();
      if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();
      socket
          .getOutputStream()
          .write(
              ("GET "
                      + path
                      + " HTTP/1.1\r\nHost: "
                      + uri.getHost()
                      + "\r\nConnection: close\r\n\r\n")
                  .getBytes(StandardCharsets.UTF_8));
      socket.getOutputStream().flush();
      return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static X509TrustManager trustAll() {
    return new X509TrustManager() {
      @Override
      public void checkClientTrusted(X509Certificate[] chain, String authType) {}

      @Override
      public void checkServerTrusted(X509Certificate[] chain, String authType) {}

      @Override
      public X509Certificate[] getAcceptedIssuers() {
        return new X509Certificate[0];
      }
    };
  }

  private static void assertPrivateHttpDial(String target, boolean zeroRtt, String expected)
      throws IOException {
    try (var client = client();
        var stream = client.dial(target, DialOptions.builder().zeroRtt(zeroRtt).build())) {
      var request =
          ("GET / HTTP/1.1\r\nHost: " + target + "\r\nConnection: close\r\n\r\n")
              .getBytes(StandardCharsets.UTF_8);
      stream.outputStream().write(request);
      stream.outputStream().flush();
      stream.socket().setSoTimeout(5_000);
      var body = readUntilContains(stream, expected);
      assertThat(body).contains(expected);
    }
  }

  private static String readUntilContains(RstreamStream stream, String expected)
      throws IOException {
    var buffer = new byte[4096];
    var output = new ByteArrayOutputStream();
    var input = stream.inputStream();
    while (true) {
      var read = input.read(buffer);
      if (read < 0) return output.toString(StandardCharsets.UTF_8);
      output.write(buffer, 0, read);
      var body = output.toString(StandardCharsets.UTF_8);
      if (body.contains(expected)) return body;
    }
  }

  private static String envOrDefault(String key, String fallback) {
    var value = System.getenv(key);
    return value == null || value.isBlank() ? fallback : value;
  }

  private static RstreamClient client() {
    var builder = ClientOptions.builder().heartbeat(false);
    var engine = System.getenv("RSTREAM_JAVA_E2E_ENGINE");
    if (engine != null && !engine.isBlank()) builder.engine(engine);
    if ("1".equals(System.getenv("RSTREAM_JAVA_E2E_NO_TOKEN"))) builder.noToken(true);
    var tls = TlsOptions.builder();
    var caFile = System.getenv("RSTREAM_JAVA_E2E_CA_FILE");
    var serverName = System.getenv("RSTREAM_JAVA_E2E_SERVER_NAME");
    if (caFile != null && !caFile.isBlank()) tls.caFile(caFile);
    if (serverName != null && !serverName.isBlank()) tls.serverName(serverName);
    if ("1".equals(System.getenv("RSTREAM_JAVA_E2E_TLS_INSECURE"))) tls.insecureSkipVerify(true);
    return RstreamClient.fromEnv(builder.tls(tls.build()).build());
  }

  private static void assumeRealEngine() {
    assumeTrue("1".equals(System.getenv("RSTREAM_JAVA_E2E")));
  }

  private static final class LocalHttpServer implements Closeable {
    private final HttpServer server;
    private final String responseBody;

    private LocalHttpServer(HttpServer server, String responseBody) {
      this.server = server;
      this.responseBody = responseBody;
    }

    static LocalHttpServer start() throws IOException {
      var hostname = InetAddress.getLocalHost().getHostName().toLowerCase(Locale.ROOT);
      var responseBody = "java-sdk:" + hostname;
      var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      server.createContext(
          "/",
          exchange -> {
            var body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("content-type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
              output.write(body);
            }
          });
      server.start();
      return new LocalHttpServer(server, responseBody);
    }

    String host() {
      return "127.0.0.1";
    }

    int port() {
      return server.getAddress().getPort();
    }

    String responseBody() {
      return responseBody;
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }
}
