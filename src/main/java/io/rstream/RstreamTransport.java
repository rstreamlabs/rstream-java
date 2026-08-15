package io.rstream;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import javax.net.ssl.SSLSocket;

final class RstreamTransport {
  SSLSocket dial(String engine, TlsOptions tls, Duration timeout) throws IOException {
    return dial(engine, tls, timeout, true);
  }

  SSLSocket dial(String engine, TlsOptions tls, Duration timeout, boolean useConfiguredServerName)
      throws IOException {
    var address = EngineAddress.parse(engine);
    var context = TlsSupport.context(tls);
    var peerHost = peerHost(address, tls, useConfiguredServerName);
    var rawSocket = new Socket();
    SSLSocket socket = null;
    try {
      rawSocket.connect(
          new InetSocketAddress(address.host(), address.port()), timeoutMillis(timeout));
      socket =
          (SSLSocket)
              context.getSocketFactory().createSocket(rawSocket, peerHost, address.port(), true);
      socket.setSoTimeout(timeoutMillis(timeout));
      TlsSupport.configure(socket, peerHost, tls);
      socket.startHandshake();
      socket.setSoTimeout(0);
      return socket;
    } catch (IOException | RuntimeException error) {
      closeQuietly(socket == null ? rawSocket : socket);
      throw error;
    }
  }

  static String peerHost(EngineAddress address, TlsOptions tls, boolean useConfiguredServerName) {
    var serverName = tls == null || tls.serverName() == null ? "" : tls.serverName().trim();
    return useConfiguredServerName && !serverName.isEmpty() ? serverName : address.host();
  }

  private static int timeoutMillis(Duration timeout) {
    var millis = timeout.toMillis();
    if (millis > Integer.MAX_VALUE) return Integer.MAX_VALUE;
    return Math.max(1, (int) millis);
  }

  private static void closeQuietly(Socket socket) {
    try {
      socket.close();
    } catch (IOException ignored) {
    }
  }
}
