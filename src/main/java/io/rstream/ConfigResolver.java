package io.rstream;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

final class ConfigResolver {
  static final String DEFAULT_API_URL = "https://rstream.io";

  private ConfigResolver() {}

  static ResolvedClientOptions resolve(ClientOptions options) {
    return resolve(options, System.getenv());
  }

  static ResolvedClientOptions resolve(ClientOptions options, Map<String, String> environment) {
    var env = EnvironmentSettings.read(environment);
    var config = resolveConfig(options, env);
    var explicitMtls = env.hasMtls() || hasClientCertificate(options.tls());
    var token =
        options.noToken() != null && options.noToken()
            ? null
            : normalizeOptional(
                firstDefined(options.token(), env.token(), explicitMtls ? null : config.token()));
    var tls = mergeTls(config.tls(), options.tls());
    if (token != null && hasClientCertificate(tls)) {
      throw new ConfigurationException(
          "Token authentication and mTLS authentication cannot be used together.",
          "ERR_RSTREAM_AUTH_CONFLICT");
    }
    if (token != null) validateTokenExpiry(token);
    if (env.useQuic()) {
      throw new UnsupportedFeatureException(
          "RSTREAM_QUIC_TRANSPORT is not supported by rstream-java.",
          "ERR_RSTREAM_UNSUPPORTED_TRANSPORT");
    }
    if (options.requireToken() && token == null && !hasClientCertificate(tls)) {
      throw new ConfigurationException(
          "Authentication is required but not configured.", "ERR_RSTREAM_AUTH_REQUIRED");
    }
    var engine =
        normalizeEngine(
            firstDefined(options.engine(), env.engine(), config.contextEngine(), config.engine()));
    return new ResolvedClientOptions(
        firstDefined(options.apiUrl(), env.apiUrl(), config.apiUrl(), DEFAULT_API_URL),
        engine,
        options.heartbeat(),
        options.heartbeatInterval(),
        options.connectTimeout(),
        options.operationTimeout(),
        options.noToken() != null ? options.noToken() : token == null && !hasClientCertificate(tls),
        normalizeOptional(firstDefined(options.projectEndpoint(), config.projectEndpoint())),
        tls,
        token,
        options.zeroRtt());
  }

  static String defaultConfigPath() {
    return Path.of(System.getProperty("user.home"), ".rstream", "config.yaml").toString();
  }

  static String normalizeEngine(String value) {
    if (normalizeOptional(value) == null) return null;
    return EngineAddress.parse(value).authority();
  }

  private static ResolvedConfig resolveConfig(ClientOptions options, EnvironmentSettings env) {
    if (!options.readConfigFile()) {
      return new ResolvedConfig(
          firstDefined(options.apiUrl(), env.apiUrl(), DEFAULT_API_URL),
          null,
          null,
          null,
          resolveMtls(env, null, null),
          null);
    }
    var path = normalizeOptional(firstDefined(options.configPath(), env.configPath()));
    var file = loadConfig(path == null ? defaultConfigPath() : path);
    var explicitApiUrl = normalizeApiUrl(firstDefined(options.apiUrl(), env.apiUrl()));
    var contextName =
        normalizeOptional(firstDefined(options.context(), env.context(), file.defaultContext()));
    var context = findContext(file, contextName, explicitApiUrl);
    var contextApiUrl = context == null ? null : context.apiUrl();
    var apiUrl = firstDefined(explicitApiUrl, contextApiUrl, DEFAULT_API_URL);
    var environment =
        context != null && context.apiUrl() != null ? findEnvironment(file, apiUrl) : null;
    var explicitEngine = normalizeOptional(firstDefined(options.engine(), env.engine()));
    var token = options.noToken() != null && options.noToken() ? null : env.token();
    if (token == null
        && !(options.noToken() != null && options.noToken())
        && !env.hasMtls()
        && !hasClientCertificate(options.tls())) {
      token = storedToken(context, environment);
    }
    if (explicitEngine != null
        && token != null
        && options.token() == null
        && env.token() == null
        && engineOverrideUsesStoredAuth(explicitEngine, context)) {
      throw new ConfigurationException(
          "Refusing to use a stored token with an explicit engine override.",
          "ERR_RSTREAM_STORED_TOKEN_ENGINE_OVERRIDE");
    }
    var tls =
        mergeTls(
            mergeTls(
                transportTls(environment == null ? null : environment.transport()),
                transportTls(context == null ? null : context.transport())),
            resolveMtls(env, context, environment));
    if (explicitEngine != null
        && hasClientCertificate(tls)
        && !env.hasMtls()
        && !hasClientCertificate(options.tls())
        && engineOverrideUsesStoredAuth(explicitEngine, context)) {
      throw new ConfigurationException(
          "Refusing to use stored mTLS credentials with an explicit engine override.",
          "ERR_RSTREAM_STORED_MTLS_ENGINE_OVERRIDE");
    }
    rejectUnsupportedTransport(environment == null ? null : environment.transport());
    rejectUnsupportedTransport(context == null ? null : context.transport());
    return new ResolvedConfig(
        apiUrl,
        context == null ? null : context.engine(),
        explicitEngine,
        context == null ? null : context.projectEndpoint(),
        tls,
        token);
  }

  private static ConfigFile loadConfig(String path) {
    try {
      if (!Files.exists(Path.of(path))) return new ConfigFile(null, List.of(), List.of());
      var content = Files.readString(Path.of(path), StandardCharsets.UTF_8).trim();
      if (content.isEmpty()) return new ConfigFile(null, List.of(), List.of());
      var loaded = new Load(LoadSettings.builder().build()).loadFromString(content);
      return normalizeConfig(map(loaded));
    } catch (IOException error) {
      throw new ConfigurationException(
          "Failed to read rstream config file.", "ERR_RSTREAM_CONFIG_READ", error);
    } catch (RuntimeException error) {
      if (error instanceof RstreamException runtime) throw runtime;
      throw new ConfigurationException(
          "Failed to parse rstream config file.", "ERR_RSTREAM_INVALID_CONFIG", error);
    }
  }

  private static ConfigFile normalizeConfig(Map<String, Object> value) {
    if (value == null) return new ConfigFile(null, List.of(), List.of());
    var defaults = map(value.get("defaults"));
    var defaultContext = defaults == null ? null : map(defaults.get("context"));
    var contexts =
        records(value.get("contexts")).stream()
            .map(ConfigResolver::contextConfig)
            .filter(item -> item.name() != null && !item.name().isBlank())
            .toList();
    var environments =
        records(value.get("environments")).stream()
            .map(ConfigResolver::environmentConfig)
            .filter(item -> item.apiUrl() != null && !item.apiUrl().isBlank())
            .toList();
    return new ConfigFile(
        defaultContext == null ? null : normalizeOptional(string(defaultContext.get("name"))),
        contexts,
        environments);
  }

  private static ContextConfig contextConfig(Map<String, Object> value) {
    return new ContextConfig(
        string(value.get("name")),
        normalizeApiUrl(string(value.get("apiUrl"))),
        authConfig(value.get("auth")),
        normalizeOptional(string(value.get("engine"))),
        normalizeOptional(string(value.get("projectEndpoint"))),
        transportConfig(value.get("transport")));
  }

  private static EnvironmentConfig environmentConfig(Map<String, Object> value) {
    return new EnvironmentConfig(
        normalizeApiUrl(string(value.get("apiUrl"))),
        authConfig(value.get("auth")),
        transportConfig(value.get("transport")));
  }

  private static AuthConfig authConfig(Object value) {
    var auth = map(value);
    if (auth == null) return null;
    var token = map(auth.get("token"));
    var tokenStorage = token == null ? null : map(token.get("storage"));
    var mtls = map(auth.get("mtls"));
    var mtlsStorage = mtls == null ? null : map(mtls.get("storage"));
    return new AuthConfig(
        tokenStorage == null
            ? null
            : new TokenConfig(
                string(tokenStorage.get("value")),
                string(tokenStorage.get("kind")),
                string(tokenStorage.get("provider"))),
        mtls == null
            ? null
            : new MtlsConfig(
                string(mtls.get("certificateFile")),
                string(mtls.get("keyFile")),
                string(mtls.get("certificate")),
                string(mtls.get("key")),
                mtlsStorage));
  }

  private static TransportConfig transportConfig(Object value) {
    var transport = map(value);
    return transport == null ? null : new TransportConfig(transport);
  }

  private static ContextConfig findContext(ConfigFile config, String name, String apiUrl) {
    if (name == null) return null;
    var matches =
        config.contexts().stream()
            .filter(
                context ->
                    context.name().equals(name)
                        && (apiUrl == null
                            || context.apiUrl() == null
                            || context.apiUrl().equals(apiUrl)))
            .toList();
    if (matches.isEmpty()) {
      throw new ConfigurationException(
          "Context '" + name + "' was not found.", "ERR_RSTREAM_CONTEXT_NOT_FOUND");
    }
    if (matches.size() > 1) {
      throw new ConfigurationException(
          "Context '" + name + "' is ambiguous for the selected API URL.",
          "ERR_RSTREAM_CONTEXT_AMBIGUOUS");
    }
    return matches.get(0);
  }

  private static EnvironmentConfig findEnvironment(ConfigFile config, String apiUrl) {
    return config.environments().stream()
        .filter(environment -> apiUrl.equals(environment.apiUrl()))
        .findFirst()
        .orElse(null);
  }

  private static String storedToken(ContextConfig context, EnvironmentConfig environment) {
    var token = context != null && context.auth() != null ? context.auth().token() : null;
    if (token == null && environment != null && environment.auth() != null)
      token = environment.auth().token();
    if (token == null) return null;
    var provider = normalizeOptional(firstDefined(token.provider(), token.kind()));
    if (provider != null && !provider.equals("inline") && !provider.equals("env")) {
      throw new UnsupportedFeatureException(
          "Token storage provider '" + provider + "' is not supported by rstream-java.",
          "ERR_RSTREAM_UNSUPPORTED_TOKEN_STORAGE");
    }
    return normalizeOptional(token.value());
  }

  private static TlsOptions resolveMtls(
      EnvironmentSettings env, ContextConfig context, EnvironmentConfig environment) {
    if (env.hasMtls()) {
      if (env.mtlsCert() == null || env.mtlsKey() == null) {
        throw new ConfigurationException(
            "Both RSTREAM_MTLS_CERT_FILE and RSTREAM_MTLS_KEY_FILE are required.",
            "ERR_RSTREAM_MTLS_INCOMPLETE");
      }
      return TlsOptions.builder().certFile(env.mtlsCert()).keyFile(env.mtlsKey()).build();
    }
    var mtls = context != null && context.auth() != null ? context.auth().mtls() : null;
    if (mtls == null && environment != null && environment.auth() != null)
      mtls = environment.auth().mtls();
    if (mtls == null) return null;
    if (mtls.storage() != null) {
      var provider =
          normalizeOptional(
              firstDefined(
                  string(mtls.storage().get("provider")),
                  string(mtls.storage().get("kind")),
                  "configured"));
      throw new UnsupportedFeatureException(
          "mTLS storage provider '" + provider + "' is not supported by rstream-java.",
          "ERR_RSTREAM_UNSUPPORTED_MTLS_STORAGE");
    }
    if (mtls.certificateFile() != null || mtls.keyFile() != null) {
      if (mtls.certificateFile() == null || mtls.keyFile() == null) {
        throw new ConfigurationException(
            "Both certificateFile and keyFile are required for mTLS.",
            "ERR_RSTREAM_MTLS_INCOMPLETE");
      }
      return TlsOptions.builder().certFile(mtls.certificateFile()).keyFile(mtls.keyFile()).build();
    }
    if (mtls.certificate() != null || mtls.key() != null) {
      throw new UnsupportedFeatureException(
          "Inline mTLS certificates are not supported by rstream-java. Use certificateFile and keyFile.",
          "ERR_RSTREAM_UNSUPPORTED_MTLS_STORAGE");
    }
    return null;
  }

  private static TlsOptions transportTls(TransportConfig transport) {
    if (transport == null) return null;
    var tls = map(transport.raw().get("tls"));
    if (tls == null) return null;
    var insecure = tls.get("insecureSkipVerify");
    if (insecure != null && !(insecure instanceof Boolean)) {
      throw new ConfigurationException(
          "transport.tls.insecureSkipVerify must be a boolean.", "ERR_RSTREAM_INVALID_CONFIG");
    }
    return TlsOptions.builder()
        .caFile(normalizeOptional(string(tls.get("caFile"))))
        .serverName(normalizeOptional(string(tls.get("serverName"))))
        .insecureSkipVerify(Boolean.TRUE.equals(insecure))
        .build();
  }

  private static void rejectUnsupportedTransport(TransportConfig transport) {
    if (transport == null || transport.raw().isEmpty()) return;
    if (Boolean.TRUE.equals(transport.raw().get("useQuic"))) {
      throw new UnsupportedFeatureException(
          "QUIC transport is not supported by rstream-java.", "ERR_RSTREAM_UNSUPPORTED_TRANSPORT");
    }
    for (var key : List.of("bind", "dns", "ipFamily", "mptcp", "proxy")) {
      if (transport.raw().containsKey(key)) {
        throw new UnsupportedFeatureException(
            "Transport option(s) not supported by rstream-java: " + key + ".",
            "ERR_RSTREAM_UNSUPPORTED_TRANSPORT");
      }
    }
  }

  private static TlsOptions mergeTls(TlsOptions inherited, TlsOptions explicit) {
    if (inherited == null) return explicit;
    if (explicit == null) return inherited;
    return TlsOptions.builder()
        .caFile(firstDefined(explicit.caFile(), inherited.caFile()))
        .certFile(firstDefined(explicit.certFile(), inherited.certFile()))
        .keyFile(firstDefined(explicit.keyFile(), inherited.keyFile()))
        .serverName(firstDefined(explicit.serverName(), inherited.serverName()))
        .insecureSkipVerify(explicit.insecureSkipVerify() || inherited.insecureSkipVerify())
        .build();
  }

  private static void validateTokenExpiry(String token) {
    var parts = token.split("\\.");
    if (parts.length < 2) return;
    try {
      var padded = parts[1] + "=".repeat((4 - parts[1].length() % 4) % 4);
      var decoded = new String(Base64.getUrlDecoder().decode(padded), StandardCharsets.UTF_8);
      var loaded = new Load(LoadSettings.builder().build()).loadFromString(decoded);
      var payload = map(loaded);
      var exp = payload == null ? null : payload.get("exp");
      if (exp instanceof Number number && number.longValue() <= Instant.now().getEpochSecond()) {
        throw new ConfigurationException(
            "Authentication token has expired.", "ERR_RSTREAM_TOKEN_EXPIRED");
      }
    } catch (IllegalArgumentException ignored) {
    }
  }

  private static boolean engineOverrideUsesStoredAuth(
      String explicitEngine, ContextConfig context) {
    if (context == null) return true;
    var contextEngine = normalizeEngine(context.engine());
    var explicit = normalizeEngine(explicitEngine);
    return contextEngine == null || !contextEngine.equals(explicit);
  }

  private static boolean hasClientCertificate(TlsOptions tls) {
    return tls != null
        && normalizeOptional(tls.certFile()) != null
        && normalizeOptional(tls.keyFile()) != null;
  }

  private static String normalizeApiUrl(String value) {
    var normalized = normalizeOptional(value);
    if (normalized == null) return null;
    try {
      var uri = new URI(normalized);
      if ((!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme()))
          || uri.getHost() == null) {
        throw invalidApiUrl();
      }
      return normalized.endsWith("/")
          ? normalized.substring(0, normalized.length() - 1)
          : normalized;
    } catch (URISyntaxException error) {
      throw invalidApiUrl();
    }
  }

  private static ConfigurationException invalidApiUrl() {
    return new ConfigurationException(
        "API URL must be an absolute HTTP(S) URL.", "ERR_RSTREAM_INVALID_API_URL");
  }

  private static String normalizeOptional(String value) {
    if (value == null) return null;
    var normalized = value.trim();
    return normalized.isEmpty() ? null : normalized;
  }

  private static Map<String, Object> map(Object value) {
    if (!(value instanceof Map<?, ?> input)) return null;
    return input.entrySet().stream()
        .collect(
            java.util.stream.Collectors.toMap(
                entry -> String.valueOf(entry.getKey()), Map.Entry::getValue));
  }

  private static List<Map<String, Object>> records(Object value) {
    if (!(value instanceof List<?> items)) return List.of();
    return items.stream().map(ConfigResolver::map).filter(item -> item != null).toList();
  }

  private static String string(Object value) {
    return value instanceof String text ? text : null;
  }

  private static String firstDefined(String... values) {
    for (var value : values) if (value != null) return value;
    return null;
  }

  private record EnvironmentSettings(
      String apiUrl,
      String configPath,
      String context,
      String engine,
      String mtlsCert,
      String mtlsKey,
      String token,
      boolean useQuic) {
    static EnvironmentSettings read(Map<String, String> environment) {
      return new EnvironmentSettings(
          normalizeApiUrl(environment.get("RSTREAM_API_URL")),
          normalizeOptional(environment.get("RSTREAM_CONFIG")),
          normalizeOptional(environment.get("RSTREAM_CONTEXT")),
          normalizeOptional(
              firstDefined(
                  environment.get("RSTREAM_ENGINE"), environment.get("RSTREAM_ENGINE_ADDRESS"))),
          normalizeOptional(environment.get("RSTREAM_MTLS_CERT_FILE")),
          normalizeOptional(environment.get("RSTREAM_MTLS_KEY_FILE")),
          normalizeOptional(environment.get("RSTREAM_AUTHENTICATION_TOKEN")),
          "1".equals(environment.get("RSTREAM_QUIC_TRANSPORT")));
    }

    boolean hasMtls() {
      return mtlsCert != null || mtlsKey != null;
    }
  }

  private record ConfigFile(
      String defaultContext, List<ContextConfig> contexts, List<EnvironmentConfig> environments) {}

  private record ContextConfig(
      String name,
      String apiUrl,
      AuthConfig auth,
      String engine,
      String projectEndpoint,
      TransportConfig transport) {}

  private record EnvironmentConfig(String apiUrl, AuthConfig auth, TransportConfig transport) {}

  private record AuthConfig(TokenConfig token, MtlsConfig mtls) {}

  private record TokenConfig(String value, String kind, String provider) {}

  private record MtlsConfig(
      String certificateFile,
      String keyFile,
      String certificate,
      String key,
      Map<String, Object> storage) {}

  private record TransportConfig(Map<String, Object> raw) {}

  private record ResolvedConfig(
      String apiUrl,
      String contextEngine,
      String engine,
      String projectEndpoint,
      TlsOptions tls,
      String token) {}
}
