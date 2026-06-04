package io.rstream;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Tunnel properties accepted and returned by the engine. */
public record TunnelProperties(
    String id,
    Instant creationDate,
    String name,
    TunnelType type,
    Boolean publish,
    TunnelProtocol protocol,
    Map<String, String> labels,
    List<String> geoIp,
    List<String> trustedIps,
    String host,
    TlsMode tlsMode,
    List<String> tlsAlpns,
    String tlsMinVersion,
    List<String> tlsCiphers,
    Boolean mtlsAuth,
    HttpVersion httpVersion,
    Boolean httpUseTls,
    Boolean tokenAuth,
    Boolean rstreamAuth,
    Boolean challengeMode,
    String hostname,
    Integer port,
    Boolean upstreamTls) {
  public TunnelProperties {
    labels = labels == null ? Map.of() : Map.copyOf(labels);
    geoIp = geoIp == null ? List.of() : List.copyOf(geoIp);
    trustedIps = trustedIps == null ? List.of() : List.copyOf(trustedIps);
    tlsAlpns = tlsAlpns == null ? List.of() : List.copyOf(tlsAlpns);
    tlsCiphers = tlsCiphers == null ? List.of() : List.copyOf(tlsCiphers);
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private String id;
    private Instant creationDate;
    private String name;
    private TunnelType type;
    private Boolean publish;
    private TunnelProtocol protocol;
    private Map<String, String> labels = Map.of();
    private List<String> geoIp = List.of();
    private List<String> trustedIps = List.of();
    private String host;
    private TlsMode tlsMode;
    private List<String> tlsAlpns = List.of();
    private String tlsMinVersion;
    private List<String> tlsCiphers = List.of();
    private Boolean mtlsAuth;
    private HttpVersion httpVersion;
    private Boolean httpUseTls;
    private Boolean tokenAuth;
    private Boolean rstreamAuth;
    private Boolean challengeMode;
    private String hostname;
    private Integer port;
    private Boolean upstreamTls;

    public Builder id(String id) {
      this.id = id;
      return this;
    }

    public Builder creationDate(Instant creationDate) {
      this.creationDate = creationDate;
      return this;
    }

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

    public Builder host(String host) {
      this.host = host;
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

    public Builder httpUseTls(Boolean httpUseTls) {
      this.httpUseTls = httpUseTls;
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

    public Builder port(Integer port) {
      this.port = port;
      return this;
    }

    public Builder upstreamTls(Boolean upstreamTls) {
      this.upstreamTls = upstreamTls;
      return this;
    }

    public TunnelProperties build() {
      return new TunnelProperties(
          id,
          creationDate,
          name,
          type,
          publish,
          protocol,
          labels,
          geoIp,
          trustedIps,
          host,
          tlsMode,
          tlsAlpns,
          tlsMinVersion,
          tlsCiphers,
          mtlsAuth,
          httpVersion,
          httpUseTls,
          tokenAuth,
          rstreamAuth,
          challengeMode,
          hostname,
          port,
          upstreamTls);
    }
  }
}
