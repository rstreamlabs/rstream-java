package io.rstream;

import java.util.List;
import java.util.Map;

/** Options accepted by {@link ControlChannel#createTunnel(CreateTunnelOptions)}. */
public record CreateTunnelOptions(
    String name,
    TunnelType type,
    Boolean publish,
    TunnelProtocol protocol,
    Map<String, String> labels,
    List<String> geoIp,
    List<String> trustedIps,
    TlsMode tlsMode,
    List<String> tlsAlpns,
    String tlsMinVersion,
    List<String> tlsCiphers,
    Boolean mtlsAuth,
    HttpVersion httpVersion,
    Boolean tokenAuth,
    Boolean rstreamAuth,
    Boolean challengeMode,
    String hostname,
    Boolean upstreamTls,
    TunnelAuth auth) {
  public CreateTunnelOptions {
    labels = labels == null ? Map.of() : Map.copyOf(labels);
    geoIp = geoIp == null ? List.of() : List.copyOf(geoIp);
    trustedIps = trustedIps == null ? List.of() : List.copyOf(trustedIps);
    tlsAlpns = tlsAlpns == null ? List.of() : List.copyOf(tlsAlpns);
    tlsCiphers = tlsCiphers == null ? List.of() : List.copyOf(tlsCiphers);
  }

  public static Builder builder() {
    return new Builder();
  }

  public static CreateTunnelOptions defaults() {
    return builder().build();
  }

  public static final class Builder {
    private String name;
    private TunnelType type;
    private Boolean publish;
    private TunnelProtocol protocol;
    private Map<String, String> labels = Map.of();
    private List<String> geoIp = List.of();
    private List<String> trustedIps = List.of();
    private TlsMode tlsMode;
    private List<String> tlsAlpns = List.of();
    private String tlsMinVersion;
    private List<String> tlsCiphers = List.of();
    private Boolean mtlsAuth;
    private HttpVersion httpVersion;
    private Boolean tokenAuth;
    private Boolean rstreamAuth;
    private Boolean challengeMode;
    private String hostname;
    private Boolean upstreamTls;
    private TunnelAuth auth;

    public Builder name(String name) {
      this.name = name;
      return this;
    }

    public Builder type(TunnelType type) {
      this.type = type;
      return this;
    }

    public Builder publish(Boolean publish) {
      this.publish = publish;
      return this;
    }

    public Builder protocol(TunnelProtocol protocol) {
      this.protocol = protocol;
      return this;
    }

    public Builder labels(Map<String, String> labels) {
      this.labels = labels;
      return this;
    }

    public Builder geoIp(List<String> geoIp) {
      this.geoIp = geoIp;
      return this;
    }

    public Builder trustedIps(List<String> trustedIps) {
      this.trustedIps = trustedIps;
      return this;
    }

    public Builder tlsMode(TlsMode tlsMode) {
      this.tlsMode = tlsMode;
      return this;
    }

    public Builder tlsAlpns(List<String> tlsAlpns) {
      this.tlsAlpns = tlsAlpns;
      return this;
    }

    public Builder tlsMinVersion(String tlsMinVersion) {
      this.tlsMinVersion = tlsMinVersion;
      return this;
    }

    public Builder tlsCiphers(List<String> tlsCiphers) {
      this.tlsCiphers = tlsCiphers;
      return this;
    }

    public Builder mtlsAuth(Boolean mtlsAuth) {
      this.mtlsAuth = mtlsAuth;
      return this;
    }

    public Builder httpVersion(HttpVersion httpVersion) {
      this.httpVersion = httpVersion;
      return this;
    }

    public Builder tokenAuth(Boolean tokenAuth) {
      this.tokenAuth = tokenAuth;
      return this;
    }

    public Builder rstreamAuth(Boolean rstreamAuth) {
      this.rstreamAuth = rstreamAuth;
      return this;
    }

    public Builder challengeMode(Boolean challengeMode) {
      this.challengeMode = challengeMode;
      return this;
    }

    public Builder hostname(String hostname) {
      this.hostname = hostname;
      return this;
    }

    public Builder upstreamTls(Boolean upstreamTls) {
      this.upstreamTls = upstreamTls;
      return this;
    }

    public Builder auth(TunnelAuth auth) {
      this.auth = auth;
      return this;
    }

    public CreateTunnelOptions build() {
      return new CreateTunnelOptions(
          name,
          type,
          publish,
          protocol,
          labels,
          geoIp,
          trustedIps,
          tlsMode,
          tlsAlpns,
          tlsMinVersion,
          tlsCiphers,
          mtlsAuth,
          httpVersion,
          tokenAuth,
          rstreamAuth,
          challengeMode,
          hostname,
          upstreamTls,
          auth);
    }
  }
}
