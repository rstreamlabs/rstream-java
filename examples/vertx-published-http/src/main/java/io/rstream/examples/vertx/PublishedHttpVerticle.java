package io.rstream.examples.vertx;

import io.rstream.BytestreamTunnel;
import io.rstream.ControlChannel;
import io.rstream.CreateTunnelOptions;
import io.rstream.RstreamClient;
import io.rstream.RstreamHttpResponse;
import io.rstream.TunnelProtocol;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import java.net.InetAddress;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class PublishedHttpVerticle extends AbstractVerticle {
  private RstreamClient client;
  private ControlChannel control;
  private BytestreamTunnel tunnel;
  private CompletableFuture<Void> serving;

  public static void main(String[] args) {
    var vertx = Vertx.vertx();
    vertx.deployVerticle(new PublishedHttpVerticle()).onFailure(error -> {
      error.printStackTrace();
      vertx.close();
    });
  }

  @Override
  public void start(Promise<Void> startPromise) {
    vertx
        .executeBlocking(
            () -> {
              var tunnelName = env("RSTREAM_TUNNEL_NAME", "vertx-http");
              client = RstreamClient.fromEnv();
              control = client.connect();
              tunnel =
                  control.createTunnel(
                      CreateTunnelOptions.builder()
                          .name(tunnelName)
                          .publish(true)
                          .protocol(TunnelProtocol.HTTP)
                          .labels(Map.of("example", "vertx", "service", "http"))
                          .build());
              serving =
                  tunnel.serveHttp(
                      request -> {
                        if (!request.method().equals("GET") || !request.path().equals("/")) {
                          return RstreamHttpResponse.text(404, "Not found.");
                        }
                        return RstreamHttpResponse.json(200, body());
                      });
              System.out.println("rstream forwarding address: " + tunnel.forwardingAddress());
              return null;
            })
        .<Void>mapEmpty()
        .onComplete(startPromise);
  }

  @Override
  public void stop(Promise<Void> stopPromise) {
    vertx
        .executeBlocking(
            () -> {
              if (serving != null) serving.cancel(true);
              if (tunnel != null) tunnel.close();
              if (control != null) control.close();
              if (client != null) client.close();
              return null;
            })
        .<Void>mapEmpty()
        .onComplete(stopPromise);
  }

  private static String body() throws Exception {
    return new JsonObject()
        .put("ok", true)
        .put("framework", "vertx")
        .put("hostname", InetAddress.getLocalHost().getHostName())
        .put("servedAt", Instant.now().toString())
        .encode();
  }

  private static String env(String key, String fallback) {
    var value = System.getenv(key);
    return value == null || value.isBlank() ? fallback : value;
  }
}
