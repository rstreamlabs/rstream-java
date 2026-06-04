package io.rstream.examples.quarkus;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.rstream.BytestreamTunnel;
import io.rstream.ControlChannel;
import io.rstream.CreateTunnelOptions;
import io.rstream.RstreamClient;
import io.rstream.RstreamHttpResponse;
import io.rstream.TunnelProtocol;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@ApplicationScoped
public final class RstreamTunnelLifecycle {
  @Inject HostnameService hostnameService;
  @Inject ObjectMapper objectMapper;

  private RstreamClient client;
  private ControlChannel control;
  private BytestreamTunnel tunnel;
  private CompletableFuture<Void> serving;

  void start(@Observes StartupEvent event) {
    var tunnelName = env("RSTREAM_TUNNEL_NAME", "quarkus-http");
    client = RstreamClient.fromEnv();
    control = client.connect();
    tunnel =
        control.createTunnel(
            CreateTunnelOptions.builder()
                .name(tunnelName)
                .publish(true)
                .protocol(TunnelProtocol.HTTP)
                .labels(Map.of("example", "quarkus", "service", "http"))
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
  }

  void stop(@Observes ShutdownEvent event) {
    if (serving != null) serving.cancel(true);
    if (tunnel != null) tunnel.close();
    if (control != null) control.close();
    if (client != null) client.close();
  }

  private static String env(String key, String fallback) {
    var value = System.getenv(key);
    return value == null || value.isBlank() ? fallback : value;
  }
}
