package io.rstream.examples;

import io.rstream.CreateTunnelOptions;
import io.rstream.HttpVersion;
import io.rstream.RstreamClient;
import io.rstream.RstreamHttpResponse;
import io.rstream.TunnelProtocol;
import java.net.InetAddress;

public final class PublishedHttpServer {
  private PublishedHttpServer() {}

  public static void main(String[] args) throws Exception {
    try (var client = RstreamClient.fromEnv();
        var control = client.connect()) {
      // RstreamClient.fromEnv reads the selected rstream context, like the CLI.
      var tunnel =
          control.createTunnel(
              CreateTunnelOptions.builder()
                  .protocol(TunnelProtocol.HTTP)
                  .httpVersion(HttpVersion.HTTP_1_1)
                  .publish(true)
                  .build());
      System.out.println("Forwarding address: " + tunnel.forwardingAddress());
      // serveHttp accepts rstream streams and handles HTTP in this process.
      tunnel.serveHttp(request -> RstreamHttpResponse.text(200, InetAddress.getLocalHost().getHostName())).join();
    }
  }
}
