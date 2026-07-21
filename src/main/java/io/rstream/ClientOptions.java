package io.rstream;

import java.time.Duration;
import java.util.Map;

/** Options accepted by {@link RstreamClient}. */
public record ClientOptions(
    String apiUrl,
    String configPath,
    String context,
    Map<String, String> controlPlaneHeaders,
    String engine,
    boolean heartbeat,
    Duration heartbeatInterval,
    Duration connectTimeout,
    Duration operationTimeout,
    Boolean noToken,
    String projectEndpoint,
    boolean readConfigFile,
    String region,
    boolean requireToken,
    String token,
    TlsOptions tls,
    boolean zeroRtt) {
  public ClientOptions {
    controlPlaneHeaders = controlPlaneHeaders == null ? Map.of() : Map.copyOf(controlPlaneHeaders);
    heartbeatInterval = heartbeatInterval == null ? Duration.ofSeconds(5) : heartbeatInterval;
    connectTimeout = connectTimeout == null ? Duration.ofSeconds(15) : connectTimeout;
    operationTimeout = operationTimeout == null ? Duration.ofSeconds(30) : operationTimeout;
    if (connectTimeout.isZero() || connectTimeout.isNegative()) {
      throw new IllegalArgumentException("connectTimeout must be positive");
    }
    if (operationTimeout.isZero() || operationTimeout.isNegative()) {
      throw new IllegalArgumentException("operationTimeout must be positive");
    }
  }

  public static Builder builder() {
    return new Builder();
  }

  public static ClientOptions defaults() {
    return builder().build();
  }

  public static final class Builder {
    private String apiUrl;
    private String configPath;
    private String context;
    private Map<String, String> controlPlaneHeaders = Map.of();
    private String engine;
    private boolean heartbeat = true;
    private Duration heartbeatInterval = Duration.ofSeconds(5);
    private Duration connectTimeout = Duration.ofSeconds(15);
    private Duration operationTimeout = Duration.ofSeconds(30);
    private Boolean noToken;
    private String projectEndpoint;
    private boolean readConfigFile = true;
    private String region;
    private boolean requireToken;
    private String token;
    private TlsOptions tls;
    private boolean zeroRtt = true;

    public Builder apiUrl(String apiUrl) {
      this.apiUrl = apiUrl;
      return this;
    }

    public Builder configPath(String configPath) {
      this.configPath = configPath;
      return this;
    }

    public Builder context(String context) {
      this.context = context;
      return this;
    }

    public Builder controlPlaneHeaders(Map<String, String> controlPlaneHeaders) {
      this.controlPlaneHeaders = Map.copyOf(controlPlaneHeaders);
      return this;
    }

    public Builder engine(String engine) {
      this.engine = engine;
      return this;
    }

    public Builder heartbeat(boolean heartbeat) {
      this.heartbeat = heartbeat;
      return this;
    }

    public Builder heartbeatInterval(Duration heartbeatInterval) {
      this.heartbeatInterval = heartbeatInterval;
      return this;
    }

    public Builder connectTimeout(Duration connectTimeout) {
      this.connectTimeout = connectTimeout;
      return this;
    }

    public Builder operationTimeout(Duration operationTimeout) {
      this.operationTimeout = operationTimeout;
      return this;
    }

    public Builder noToken(Boolean noToken) {
      this.noToken = noToken;
      return this;
    }

    public Builder projectEndpoint(String projectEndpoint) {
      this.projectEndpoint = projectEndpoint;
      return this;
    }

    public Builder readConfigFile(boolean readConfigFile) {
      this.readConfigFile = readConfigFile;
      return this;
    }

    public Builder region(String region) {
      this.region = region;
      return this;
    }

    public Builder requireToken(boolean requireToken) {
      this.requireToken = requireToken;
      return this;
    }

    public Builder token(String token) {
      this.token = token;
      return this;
    }

    public Builder tls(TlsOptions tls) {
      this.tls = tls;
      return this;
    }

    public Builder zeroRtt(boolean zeroRtt) {
      this.zeroRtt = zeroRtt;
      return this;
    }

    public ClientOptions build() {
      return new ClientOptions(
          apiUrl,
          configPath,
          context,
          controlPlaneHeaders,
          engine,
          heartbeat,
          heartbeatInterval,
          connectTimeout,
          operationTimeout,
          noToken,
          projectEndpoint,
          readConfigFile,
          region,
          requireToken,
          token,
          tls,
          zeroRtt);
    }
  }
}
