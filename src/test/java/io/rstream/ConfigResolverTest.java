package io.rstream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ConfigResolverTest {
  @TempDir Path temp;

  @Test
  void explicitOptionsWinOverConfigAndEnvironment() throws Exception {
    var configPath =
        config(
            """
            defaults:
              context:
                name: prod
            contexts:
              - name: prod
                apiUrl: https://api.example.com
                engine: engine.example.com:443
                projectEndpoint: prod-project
                auth:
                  token:
                    storage:
                      kind: inline
                      value: stored-token
            """);
    var options =
        ClientOptions.builder()
            .configPath(configPath.toString())
            .engine("local.example.com:8443")
            .token("explicit-token")
            .projectEndpoint("explicit-project")
            .connectTimeout(Duration.ofSeconds(2))
            .build();
    var resolved =
        ConfigResolver.resolve(
            options,
            Map.of(
                "RSTREAM_ENGINE",
                "env.example.com:443",
                "RSTREAM_AUTHENTICATION_TOKEN",
                "env-token"));
    assertThat(resolved.engine()).isEqualTo("local.example.com:8443");
    assertThat(resolved.token()).isEqualTo("explicit-token");
    assertThat(resolved.projectEndpoint()).isEqualTo("explicit-project");
    assertThat(resolved.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
  }

  @Test
  void defaultConnectTimeoutIsConfigured() {
    var resolved =
        ConfigResolver.resolve(
            ClientOptions.builder().engine("engine.example.com:443").readConfigFile(false).build(),
            Map.of());
    assertThat(resolved.connectTimeout()).isEqualTo(Duration.ofSeconds(15));
    assertThat(resolved.operationTimeout()).isEqualTo(Duration.ofSeconds(30));
  }

  @Test
  void invalidTimeoutsAreRejectedByOptions() {
    assertThatThrownBy(() -> ClientOptions.builder().connectTimeout(Duration.ZERO).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("connectTimeout");
    assertThatThrownBy(() -> ClientOptions.builder().operationTimeout(Duration.ZERO).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("operationTimeout");
  }

  @Test
  void selectedContextLoadsEngineProjectTokenAndTls() throws Exception {
    var configPath =
        config(
            """
            defaults:
              context:
                name: prod
            contexts:
              - name: prod
                apiUrl: https://api.example.com
                engine: engine.example.com:443
                projectEndpoint: prod-project
                auth:
                  token:
                    storage:
                      kind: inline
                      value: stored-token
                transport:
                  tls:
                    caFile: /tmp/ca.pem
                    serverName: engine.internal
                    insecureSkipVerify: true
            """);
    var resolved =
        ConfigResolver.resolve(
            ClientOptions.builder().configPath(configPath.toString()).build(), Map.of());
    assertThat(resolved.apiUrl()).isEqualTo("https://api.example.com");
    assertThat(resolved.engine()).isEqualTo("engine.example.com:443");
    assertThat(resolved.projectEndpoint()).isEqualTo("prod-project");
    assertThat(resolved.token()).isEqualTo("stored-token");
    assertThat(resolved.tls().caFile()).isEqualTo("/tmp/ca.pem");
    assertThat(resolved.tls().serverName()).isEqualTo("engine.internal");
    assertThat(resolved.tls().insecureSkipVerify()).isTrue();
  }

  @Test
  void environmentContextSelectionLoadsEnvironmentCredentials() throws Exception {
    var configPath =
        config(
            """
            contexts:
              - name: staging
                apiUrl: https://staging.example.com
                engine: staging-engine.example.com:443
              - name: prod
                apiUrl: https://api.example.com
                engine: prod-engine.example.com:443
            environments:
              - apiUrl: https://api.example.com
                auth:
                  token:
                    storage:
                      kind: inline
                      value: prod-token
                transport:
                  tls:
                    serverName: prod-engine.internal
            """);
    var resolved =
        ConfigResolver.resolve(
            ClientOptions.builder().configPath(configPath.toString()).build(),
            Map.of("RSTREAM_CONTEXT", "prod"));
    assertThat(resolved.apiUrl()).isEqualTo("https://api.example.com");
    assertThat(resolved.engine()).isEqualTo("prod-engine.example.com:443");
    assertThat(resolved.token()).isEqualTo("prod-token");
    assertThat(resolved.tls().serverName()).isEqualTo("prod-engine.internal");
  }

  @Test
  void ambiguousContextRequiresApiUrl() throws Exception {
    var configPath =
        config(
            """
            contexts:
              - name: prod
                apiUrl: https://api-a.example.com
                engine: engine-a.example.com:443
              - name: prod
                apiUrl: https://api-b.example.com
                engine: engine-b.example.com:443
            """);
    assertThatThrownBy(
            () ->
                ConfigResolver.resolve(
                    ClientOptions.builder()
                        .configPath(configPath.toString())
                        .context("prod")
                        .build(),
                    Map.of()))
        .isInstanceOf(ConfigurationException.class)
        .hasMessageContaining("ambiguous");
    var resolved =
        ConfigResolver.resolve(
            ClientOptions.builder()
                .configPath(configPath.toString())
                .context("prod")
                .apiUrl("https://api-b.example.com")
                .build(),
            Map.of());
    assertThat(resolved.engine()).isEqualTo("engine-b.example.com:443");
  }

  @Test
  void environmentEngineAliasAndMtlsFilesAreSupported() {
    var resolved =
        ConfigResolver.resolve(
            ClientOptions.builder().readConfigFile(false).build(),
            Map.of(
                "RSTREAM_ENGINE_ADDRESS",
                "Engine.Example.Com:9443",
                "RSTREAM_MTLS_CERT_FILE",
                "/tmp/client.crt",
                "RSTREAM_MTLS_KEY_FILE",
                "/tmp/client.key"));
    assertThat(resolved.engine()).isEqualTo("engine.example.com:9443");
    assertThat(resolved.token()).isNull();
    assertThat(resolved.tls().certFile()).isEqualTo("/tmp/client.crt");
    assertThat(resolved.tls().keyFile()).isEqualTo("/tmp/client.key");
  }

  @Test
  void noTokenSkipsStoredCredentials() throws Exception {
    var configPath =
        config(
            """
            defaults:
              context:
                name: local
            contexts:
              - name: local
                engine: localhost:9443
                auth:
                  token:
                    storage:
                      value: stored-token
            """);
    var resolved =
        ConfigResolver.resolve(
            ClientOptions.builder().configPath(configPath.toString()).noToken(true).build(),
            Map.of());
    assertThat(resolved.noToken()).isTrue();
    assertThat(resolved.token()).isNull();
  }

  @Test
  void explicitTokenAllowsExplicitEngineOverride() throws Exception {
    var configPath =
        config(
            """
            defaults:
              context:
                name: prod
            contexts:
              - name: prod
                engine: engine.example.com:443
                auth:
                  token:
                    storage:
                      value: stored-token
            """);
    var resolved =
        ConfigResolver.resolve(
            ClientOptions.builder()
                .configPath(configPath.toString())
                .engine("other.example.com:443")
                .token("explicit-token")
                .build(),
            Map.of());
    assertThat(resolved.engine()).isEqualTo("other.example.com:443");
    assertThat(resolved.token()).isEqualTo("explicit-token");
  }

  @Test
  void invalidEngineIsRejectedBeforeDialing() {
    var options = ClientOptions.builder().engine("https://engine.example.com").build();
    assertThatThrownBy(() -> ConfigResolver.resolve(options, Map.of()))
        .isInstanceOf(ConfigurationException.class)
        .hasMessageContaining("host[:port]");
  }

  @Test
  void invalidApiUrlIsRejected() {
    assertThatThrownBy(
            () ->
                ConfigResolver.resolve(
                    ClientOptions.builder().apiUrl("ftp://api.example.com").build(), Map.of()))
        .isInstanceOf(ConfigurationException.class)
        .hasMessageContaining("HTTP(S)");
  }

  @Test
  void storedTokenWithMismatchedExplicitEngineIsRejected() throws Exception {
    var configPath =
        config(
            """
            defaults:
              context:
                name: prod
            contexts:
              - name: prod
                engine: engine.example.com:443
                auth:
                  token:
                    storage:
                      kind: inline
                      value: stored-token
            """);
    var options =
        ClientOptions.builder()
            .configPath(configPath.toString())
            .engine("other.example.com:443")
            .build();
    assertThatThrownBy(() -> ConfigResolver.resolve(options, Map.of()))
        .isInstanceOf(ConfigurationException.class)
        .hasMessageContaining("stored token");
  }

  @Test
  void expiredJwtLikeTokenIsRejected() {
    var expired =
        "x."
            + Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                    ("{\"exp\":" + (Instant.now().getEpochSecond() - 60) + "}")
                        .getBytes(StandardCharsets.UTF_8))
            + ".x";
    assertThatThrownBy(
            () ->
                ConfigResolver.resolve(
                    ClientOptions.builder().engine("engine.example.com:443").token(expired).build(),
                    Map.of()))
        .isInstanceOf(ConfigurationException.class)
        .hasMessageContaining("expired");
  }

  @Test
  void tokenAndMtlsCannotBeUsedTogether() {
    var options =
        ClientOptions.builder()
            .engine("engine.example.com:443")
            .token("token")
            .tls(TlsOptions.builder().certFile("client.crt").keyFile("client.key").build())
            .build();
    assertThatThrownBy(() -> ConfigResolver.resolve(options, Map.of()))
        .isInstanceOf(ConfigurationException.class)
        .hasMessageContaining("cannot be used together");
  }

  @Test
  void incompleteMtlsEnvironmentIsRejected() {
    assertThatThrownBy(
            () ->
                ConfigResolver.resolve(
                    ClientOptions.builder().readConfigFile(false).build(),
                    Map.of("RSTREAM_MTLS_CERT_FILE", "/tmp/client.crt")))
        .isInstanceOf(ConfigurationException.class)
        .hasMessageContaining("Both RSTREAM_MTLS_CERT_FILE");
  }

  @Test
  void storedMtlsFilesAreLoadedAndProtectedFromEngineOverride() throws Exception {
    var configPath =
        config(
            """
            defaults:
              context:
                name: prod
            contexts:
              - name: prod
                engine: engine.example.com:443
                auth:
                  mtls:
                    certificateFile: /tmp/client.crt
                    keyFile: /tmp/client.key
            """);
    var resolved =
        ConfigResolver.resolve(
            ClientOptions.builder().configPath(configPath.toString()).build(), Map.of());
    assertThat(resolved.tls().certFile()).isEqualTo("/tmp/client.crt");
    assertThat(resolved.tls().keyFile()).isEqualTo("/tmp/client.key");
    assertThatThrownBy(
            () ->
                ConfigResolver.resolve(
                    ClientOptions.builder()
                        .configPath(configPath.toString())
                        .engine("other.example.com:443")
                        .build(),
                    Map.of()))
        .isInstanceOf(ConfigurationException.class)
        .hasMessageContaining("stored mTLS");
  }

  @Test
  void malformedYamlConfigIsRejectedAsConfigurationError() throws Exception {
    var configPath = config("contexts: [");
    assertThatThrownBy(
            () ->
                ConfigResolver.resolve(
                    ClientOptions.builder().configPath(configPath.toString()).build(), Map.of()))
        .isInstanceOf(ConfigurationException.class)
        .hasMessageContaining("parse rstream config");
  }

  @Test
  void unsupportedTransportOptionsAreRejected() throws Exception {
    var configPath =
        config(
            """
            defaults:
              context:
                name: prod
            contexts:
              - name: prod
                engine: engine.example.com:443
                transport:
                  proxy:
                    http: http://proxy.example
            """);
    assertThatThrownBy(
            () ->
                ConfigResolver.resolve(
                    ClientOptions.builder().configPath(configPath.toString()).build(), Map.of()))
        .isInstanceOf(UnsupportedFeatureException.class)
        .hasMessageContaining("proxy");
  }

  @Test
  void unsupportedTokenStorageIsRejected() throws Exception {
    var configPath =
        config(
            """
            defaults:
              context:
                name: prod
            contexts:
              - name: prod
                engine: engine.example.com:443
                auth:
                  token:
                    storage:
                      provider: keychain
                      value: stored-token
            """);
    assertThatThrownBy(
            () ->
                ConfigResolver.resolve(
                    ClientOptions.builder().configPath(configPath.toString()).build(), Map.of()))
        .isInstanceOf(UnsupportedFeatureException.class)
        .hasMessageContaining("keychain");
  }

  @Test
  void requireTokenRejectsUnauthenticatedRuntime() {
    var options =
        ClientOptions.builder()
            .engine("engine.example.com:443")
            .readConfigFile(false)
            .requireToken(true)
            .build();
    assertThatThrownBy(() -> ConfigResolver.resolve(options, Map.of()))
        .isInstanceOf(ConfigurationException.class)
        .hasMessageContaining("Authentication is required");
  }

  private Path config(String content) throws Exception {
    var path = temp.resolve("config.yaml");
    Files.writeString(path, content);
    return path;
  }
}
