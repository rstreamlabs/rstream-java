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
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

final class RstreamApiClient {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final String apiUrl;
  private final Map<String, String> controlPlaneHeaders;
  private final String token;
  private final HttpClient client;

  RstreamApiClient(String apiUrl, String token) {
    this(apiUrl, token, Map.of());
  }

  RstreamApiClient(String apiUrl, String token, Map<String, String> controlPlaneHeaders) {
    this.apiUrl = apiUrl == null ? ConfigResolver.DEFAULT_API_URL : apiUrl.replaceAll("/+$", "");
    this.controlPlaneHeaders = ConfigResolver.normalizeControlPlaneHeaders(controlPlaneHeaders);
    this.token = token;
    this.client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  String resolveEngine(String projectEndpoint) {
    return resolveEngine(projectEndpoint, null);
  }

  String resolveEngine(String projectEndpoint, String region) {
    var endpoint = projectEndpoint == null ? "" : projectEndpoint.trim();
    if (endpoint.isEmpty()) {
      throw new ConfigurationException(
          "Project endpoint is required.", "ERR_RSTREAM_PROJECT_ENDPOINT_REQUIRED");
    }
    var encoded = URLEncoder.encode(endpoint, StandardCharsets.UTF_8).replace("+", "%20");
    var project = requestJson("/api/projects/tunnels/resolve/" + encoded);
    return engineFromProject(project, region);
  }

  private JsonNode requestJson(String path) {
    if (!path.startsWith("/") || path.startsWith("//")) {
      throw new RstreamException(
          "API request path must be a relative absolute path.", "ERR_RSTREAM_INVALID_API_PATH");
    }
    var builder =
        HttpRequest.newBuilder(URI.create(apiUrl + path)).timeout(Duration.ofSeconds(15)).GET();
    controlPlaneHeaders.forEach(builder::header);
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

  private static String engineFromProject(JsonNode project, String region) {
    if (region != null) return regionalEngineFromProject(project, region);
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

  private static String regionalEngineFromProject(JsonNode project, String region) {
    var requested = region.toLowerCase(Locale.ROOT);
    var projectEndpoint = optionalString(project, "endpoint");
    if (projectEndpoint == null) {
      throw new RstreamException(
          "Managed tunnels project response is missing its endpoint.",
          "ERR_RSTREAM_API_INVALID_RESPONSE");
    }
    var endpoints = project.get("regionalEndpoints");
    if (endpoints == null || !endpoints.isArray()) {
      throw new RstreamException(
          "Control plane response has invalid 'regionalEndpoints'.",
          "ERR_RSTREAM_API_INVALID_RESPONSE");
    }
    var matches = new ArrayList<JsonNode>();
    var available = new TreeSet<String>();
    for (var endpoint : endpoints) {
      if (!endpoint.isObject()) {
        throw new RstreamException(
            "Control plane response has invalid 'regionalEndpoints'.",
            "ERR_RSTREAM_API_INVALID_RESPONSE");
      }
      var endpointRegion = requiredString(endpoint, "region").toLowerCase(Locale.ROOT);
      requiredString(endpoint, "provider");
      requiredString(endpoint, "domain");
      requiredPort(endpoint, "enginePort");
      available.add(endpointRegion);
      if (endpointRegion.equals(requested)) matches.add(endpoint);
    }
    if (matches.isEmpty()) {
      var suffix =
          available.isEmpty() ? "" : " Available regions: " + String.join(", ", available) + ".";
      throw new ConfigurationException(
          "Region '" + requested + "' is not available for this project." + suffix,
          "ERR_RSTREAM_REGION_UNAVAILABLE");
    }
    if (matches.size() > 1) {
      throw new ConfigurationException(
          "Region '" + requested + "' is ambiguous for this project.",
          "ERR_RSTREAM_REGION_AMBIGUOUS");
    }
    var selected = matches.get(0);
    return ConfigResolver.normalizeEngine(
        projectEndpoint
            + "."
            + requiredString(selected, "domain")
            + ":"
            + requiredPort(selected, "enginePort"));
  }

  private static String requiredString(JsonNode data, String key) {
    var value = optionalString(data, key);
    if (value != null) return value;
    throw new RstreamException(
        "Control plane response has invalid '" + key + "'.", "ERR_RSTREAM_API_INVALID_RESPONSE");
  }

  private static int requiredPort(JsonNode data, String key) {
    var value = optionalInt(data, key);
    if (value != null) return value;
    throw new RstreamException(
        "Control plane response has invalid '" + key + "'.", "ERR_RSTREAM_API_INVALID_RESPONSE");
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
