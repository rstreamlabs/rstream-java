package io.rstream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
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
  void resolvesOnlyAuthorizedRegionalEngines() throws Exception {
    try (var api =
        ApiServer.start(
            200,
            """
            {
              "endpoint":"project",
              "domain":"global.example.test",
              "enginePort":443,
              "placement":"global",
              "regionalEndpoints":[
                {
                  "provider":"aws",
                  "region":"eu-west-3",
                  "domain":"eu.example.test",
                  "enginePort":8443
                },
                {
                  "provider":"aws",
                  "region":"us-east-1",
                  "domain":"us.example.test",
                  "enginePort":443
                }
              ]
            }
            """)) {
      var client = new RstreamApiClient(api.url(), null);
      assertThat(client.resolveEngine("project", "US-EAST-1"))
          .isEqualTo("project.us.example.test:443");
      assertThatThrownBy(() -> client.resolveEngine("project", "ap-southeast-1"))
          .isInstanceOf(ConfigurationException.class)
          .hasMessageContaining("Available regions: eu-west-3, us-east-1");
    }
  }

  @Test
  void rejectsAmbiguousRegionalEngines() throws Exception {
    try (var api =
        ApiServer.start(
            200,
            """
            {
              "endpoint":"project",
              "regionalEndpoints":[
                {
                  "provider":"aws",
                  "region":"eu-west-3",
                  "domain":"eu.example.test",
                  "enginePort":443
                },
                {
                  "provider":"aws",
                  "region":"eu-west-3",
                  "domain":"eu-alt.example.test",
                  "enginePort":443
                }
              ]
            }
            """)) {
      var client = new RstreamApiClient(api.url(), null);
      assertThatThrownBy(() -> client.resolveEngine("project", "eu-west-3"))
          .isInstanceOf(ConfigurationException.class)
          .hasMessageContaining("ambiguous");
    }
  }

  @Test
  void sendsValidatedControlPlaneHeaders() throws Exception {
    try (var api =
        ApiServer.start(
            200,
            """
            {"endpoint":"project","domain":"t.localhost.rstream.io","enginePort":9443}
            """)) {
      var client =
          new RstreamApiClient(
              api.url(), null, Map.of("x-vercel-protection-bypass", "test-secret"));
      client.resolveEngine("project");
      assertThat(api.controlPlaneHeader()).isEqualTo("test-secret");
    }
  }

  @Test
  void rejectsReservedControlPlaneHeaders() {
    assertThatThrownBy(
            () -> new RstreamApiClient("https://rstream.io", null, Map.of("Host", "evil.test")))
        .isInstanceOf(ConfigurationException.class)
        .hasMessageContaining("reserved");
  }

  @Test
  void doesNotForwardControlPlaneHeadersAcrossRedirects() throws Exception {
    try (var target =
            ApiServer.start(
                200,
                """
                {"endpoint":"project","domain":"t.localhost.rstream.io","enginePort":9443}
                """);
        var redirect = ApiServer.redirectTo(target.url())) {
      var client =
          new RstreamApiClient(redirect.url(), null, Map.of("X-Deployment-Bypass", "test-secret"));
      assertThatThrownBy(() -> client.resolveEngine("project"))
          .isInstanceOf(RstreamException.class)
          .hasMessageContaining("HTTP error 302");
      assertThat(target.requests()).isZero();
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
    private volatile String controlPlaneHeader;
    private volatile String path;
    private volatile int requests;

    private ApiServer(HttpServer server) {
      this.server = server;
    }

    static ApiServer start(int status, String body) throws IOException {
      return start(status, body, null);
    }

    static ApiServer redirectTo(String location) throws IOException {
      return start(302, "", location);
    }

    private static ApiServer start(int status, String body, String location) throws IOException {
      var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      var api = new ApiServer(server);
      server.createContext(
          "/",
          exchange -> {
            api.requests++;
            api.path = exchange.getRequestURI().getRawPath();
            api.authorization = exchange.getRequestHeaders().getFirst("authorization");
            api.controlPlaneHeader =
                exchange.getRequestHeaders().getFirst("x-vercel-protection-bypass");
            var bytes = body.getBytes(StandardCharsets.UTF_8);
            if (location != null) exchange.getResponseHeaders().set("Location", location);
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

    String controlPlaneHeader() {
      return controlPlaneHeader;
    }

    String path() {
      return path;
    }

    int requests() {
      return requests;
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }
}
