package io.rstream.examples;

import io.rstream.RstreamClient;
import java.nio.charset.StandardCharsets;

public final class PrivateDial {
  private PrivateDial() {}

  public static void main(String[] args) throws Exception {
    var target = args.length > 0 ? args[0] : "private-api";
    try (var client = RstreamClient.fromEnv();
        var stream = client.dial(target)) {
      // dial accepts either a tunnel name or tunnel ID.
      var request =
          ("GET / HTTP/1.1\r\nHost: " + target + "\r\nConnection: close\r\n\r\n")
              .getBytes(StandardCharsets.UTF_8);
      stream.outputStream().write(request);
      stream.outputStream().flush();
      stream.inputStream().transferTo(System.out);
    }
  }
}
