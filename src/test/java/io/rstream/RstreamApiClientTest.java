package io.rstream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

final class RstreamApiClientTest {
  @Test
  void resolvesEngineFromManagedProjectFields() throws Exception {
    try (var api =
        ApiServer.start(
            200,
            """
            {"endpoint":"project","domain":"t.localhost.rstream.io","enginePort":9443}
            """)) {
      var client = new RstreamApiClient(api.url(), "token");
      assertThat(client.resolveEngine("project endpoint"))
          .isEqualTo("project.t.localhost.rstream.io:9443");
      assertThat(api.path()).isEqualTo("/api/projects/tunnels/resolve/project%20endpoint");
      assertThat(api.authorization()).isEqualTo("Bearer token");
    }
  }

  @Test
  void resolvesEngineFromFallbackUrl() throws Exception {
    try (var api = ApiServer.start(200, "{\"url\":\"fallback.example.com:9443\"}")) {
      var client = new RstreamApiClient(api.url(), null);
      assertThat(client.resolveEngine("project")).isEqualTo("fallback.example.com:9443");
    }
  }

  @Test
  void rejectsUnresolvableControlPlaneResponses() throws Exception {
    try (var api = ApiServer.start(200, "{}")) {
      var client = new RstreamApiClient(api.url(), null);
      assertThatThrownBy(() -> client.resolveEngine("project"))
          .isInstanceOf(RstreamException.class)
          .hasMessageContaining("Failed to resolve");
    }
  }

  @Test
  void surfacesControlPlaneHttpErrors() throws Exception {
    try (var api = ApiServer.start(403, "forbidden")) {
      var client = new RstreamApiClient(api.url(), null);
      assertThatThrownBy(() -> client.resolveEngine("project"))
          .isInstanceOf(RstreamException.class)
          .hasMessageContaining("HTTP error 403")
          .hasMessageContaining("forbidden");
    }
  }

  @Test
  void surfacesInvalidJsonResponses() throws Exception {
    try (var api = ApiServer.start(200, "not-json")) {
      var client = new RstreamApiClient(api.url(), null);
      assertThatThrownBy(() -> client.resolveEngine("project"))
          .isInstanceOf(RstreamException.class)
          .hasMessageContaining("Control plane request failed");
    }
  }

  private static final class ApiServer implements Closeable {
    private final HttpServer server;
    private volatile String authorization;
    private volatile String path;

    private ApiServer(HttpServer server) {
      this.server = server;
    }

    static ApiServer start(int status, String body) throws IOException {
      var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      var api = new ApiServer(server);
      server.createContext(
          "/",
          exchange -> {
            api.path = exchange.getRequestURI().getRawPath();
            api.authorization = exchange.getRequestHeaders().getFirst("authorization");
            var bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) {
              output.write(bytes);
            }
          });
      server.start();
      return api;
    }

    String url() {
      return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    String authorization() {
      return authorization;
    }

    String path() {
      return path;
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }
}
