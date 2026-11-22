package io.rstream.examples.spring;

import io.rstream.BytestreamTunnel;
import io.rstream.ControlChannel;
import io.rstream.CreateTunnelOptions;
import io.rstream.RstreamClient;
import io.rstream.RstreamHttpResponse;
import io.rstream.TunnelProtocol;
import java.net.InetAddress;
import java.time.Instant;
import java.util.Map;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@SpringBootApplication
public class PublishedHttpApplication {
  public static void main(String[] args) {
    SpringApplication.run(PublishedHttpApplication.class, args);
  }

  @Bean(destroyMethod = "close")
  RstreamTunnelPublisher publishRstreamTunnel(
      HostnameService hostnameService, JsonMapper jsonMapper) {
    return new RstreamTunnelPublisher(hostnameService, jsonMapper);
  }

  @Service
  static final class HostnameService {
    HostnameSnapshot snapshot() throws Exception {
      return new HostnameSnapshot(
          true, "spring-boot", InetAddress.getLocalHost().getHostName(), Instant.now().toString());
    }
  }

  record HostnameSnapshot(boolean ok, String framework, String hostname, String servedAt) {}

  private static final class RstreamTunnelPublisher implements ApplicationRunner, AutoCloseable {
    private final HostnameService hostnameService;
    private final JsonMapper jsonMapper;
    private RstreamClient client;
    private ControlChannel control;
    private BytestreamTunnel tunnel;

    RstreamTunnelPublisher(HostnameService hostnameService, JsonMapper jsonMapper) {
      this.hostnameService = hostnameService;
      this.jsonMapper = jsonMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
      var tunnelName = System.getenv().getOrDefault("RSTREAM_TUNNEL_NAME", "spring-boot-http");
      client = RstreamClient.fromEnv();
      control = client.connect();
      tunnel =
          control.createTunnel(
              CreateTunnelOptions.builder()
                  .name(tunnelName)
                  .publish(true)
                  .protocol(TunnelProtocol.HTTP)
                  .labels(Map.of("example", "spring-boot", "service", "http"))
                  .build());
      var serving =
          tunnel.serveHttp(
              request -> {
                if (!request.method().equals("GET") || !request.path().equals("/")) {
                  return RstreamHttpResponse.text(404, "Not found.");
                }
                return RstreamHttpResponse.json(
                    200, jsonMapper.writeValueAsString(hostnameService.snapshot()));
              });
      System.out.println("rstream forwarding address: " + tunnel.forwardingAddress());
      serving.join();
    }

    @Override
    public void close() {
      try {
        if (tunnel != null) tunnel.close();
      } finally {
        try {
          if (control != null) control.close();
        } finally {
          if (client != null) client.close();
        }
      }
    }
  }
}
