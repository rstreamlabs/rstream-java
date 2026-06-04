package io.rstream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

final class RstreamApiClient {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final String apiUrl;
  private final String token;
  private final HttpClient client;

  RstreamApiClient(String apiUrl, String token) {
    this.apiUrl = apiUrl == null ? ConfigResolver.DEFAULT_API_URL : apiUrl.replaceAll("/+$", "");
    this.token = token;
    this.client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  String resolveEngine(String projectEndpoint) {
    var endpoint = projectEndpoint == null ? "" : projectEndpoint.trim();
    if (endpoint.isEmpty()) {
      throw new ConfigurationException(
          "Project endpoint is required.", "ERR_RSTREAM_PROJECT_ENDPOINT_REQUIRED");
    }
    var encoded = URLEncoder.encode(endpoint, StandardCharsets.UTF_8).replace("+", "%20");
    var project = requestJson("/api/projects/tunnels/resolve/" + encoded);
    return engineFromProject(project);
  }

  private JsonNode requestJson(String path) {
    if (!path.startsWith("/") || path.startsWith("//")) {
      throw new RstreamException(
          "API request path must be a relative absolute path.", "ERR_RSTREAM_INVALID_API_PATH");
    }
    var builder =
        HttpRequest.newBuilder(URI.create(apiUrl + path)).timeout(Duration.ofSeconds(15)).GET();
    if (token != null) builder.header("Authorization", "Bearer " + token);
    try {
      var response =
          client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new RstreamException(
            "HTTP error " + response.statusCode() + ": " + response.body(), "ERR_RSTREAM_API_HTTP");
      }
      var json = JSON.readTree(response.body());
      if (json == null || !json.isObject()) {
        throw new RstreamException(
            "Control plane response must be a JSON object.", "ERR_RSTREAM_API_HTTP");
      }
      return json;
    } catch (IOException error) {
      throw new RstreamException("Control plane request failed.", "ERR_RSTREAM_API_HTTP", error);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new RstreamException(
          "Control plane request interrupted.", "ERR_RSTREAM_API_HTTP", error);
    }
  }

  private static String engineFromProject(JsonNode project) {
    var endpoint = optionalString(project, "endpoint");
    var domain = optionalString(project, "domain");
    var port = optionalInt(project, "enginePort");
    if (endpoint != null && domain != null && port != null)
      return ConfigResolver.normalizeEngine(endpoint + "." + domain + ":" + port);
    var url = optionalString(project, "url");
    if (url != null) return ConfigResolver.normalizeEngine(url);
    throw new RstreamException(
        "Failed to resolve the engine address from the managed tunnels project.",
        "ERR_RSTREAM_ENGINE_RESOLUTION");
  }

  private static String optionalString(JsonNode data, String key) {
    var value = data.get(key);
    if (value != null && value.isTextual() && !value.asText().isBlank()) return value.asText();
    return null;
  }

  private static Integer optionalInt(JsonNode data, String key) {
    var value = data.get(key);
    if (value != null && value.canConvertToInt()) {
      var port = value.intValue();
      if (port >= 1 && port <= 65_535) return port;
    }
    return null;
  }
}
