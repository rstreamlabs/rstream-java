package io.rstream.examples.micronaut;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.rstream.BytestreamTunnel;
import io.rstream.ControlChannel;
import io.rstream.CreateTunnelOptions;
import io.rstream.RstreamClient;
import io.rstream.RstreamHttpResponse;
import io.rstream.TunnelProtocol;
import jakarta.inject.Singleton;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Singleton
public final class RstreamTunnelPublisher implements AutoCloseable {
  private final HostnameService hostnameService;
  private final ObjectMapper objectMapper = new ObjectMapper();
  private RstreamClient client;
  private ControlChannel control;
  private BytestreamTunnel tunnel;
  private CompletableFuture<Void> serving;

  RstreamTunnelPublisher(HostnameService hostnameService) {
    this.hostnameService = hostnameService;
  }

  void run() {
    var tunnelName = System.getenv().getOrDefault("RSTREAM_TUNNEL_NAME", "micronaut-http");
    client = RstreamClient.fromEnv();
    control = client.connect();
    tunnel =
        control.createTunnel(
            CreateTunnelOptions.builder()
                .name(tunnelName)
                .publish(true)
                .protocol(TunnelProtocol.HTTP)
                .labels(Map.of("example", "micronaut", "service", "http"))
                .build());
    serving =
        tunnel.serveHttp(
            request -> {
              if (!request.method().equals("GET") || !request.path().equals("/")) {
                return RstreamHttpResponse.text(404, "Not found.");
              }
              return RstreamHttpResponse.json(
                  200, objectMapper.writeValueAsString(hostnameService.snapshot()));
            });
    System.out.println("rstream forwarding address: " + tunnel.forwardingAddress());
    serving.join();
  }

  @Override
  public void close() {
    if (serving != null) serving.cancel(true);
    if (tunnel != null) tunnel.close();
    if (control != null) control.close();
    if (client != null) client.close();
  }
}
