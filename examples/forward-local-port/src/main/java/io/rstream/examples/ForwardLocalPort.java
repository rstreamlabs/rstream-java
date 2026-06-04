package io.rstream.examples;

import io.rstream.CreateTunnelOptions;
import io.rstream.HttpVersion;
import io.rstream.RstreamClient;
import io.rstream.TunnelProtocol;

public final class ForwardLocalPort {
  private ForwardLocalPort() {}

  public static void main(String[] args) {
    var host = args.length > 0 ? args[0] : "127.0.0.1";
    var port = args.length > 1 ? Integer.parseInt(args[1]) : 8000;
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
      // forwardTo keeps accepting tunnel streams and pipes them to the local service.
      tunnel.forwardTo(host, port).join();
    }
  }
}
