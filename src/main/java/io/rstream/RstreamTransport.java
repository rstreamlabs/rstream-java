package io.rstream;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import javax.net.ssl.SSLSocket;

final class RstreamTransport {
  SSLSocket dial(String engine, TlsOptions tls, Duration timeout) throws IOException {
    var address = EngineAddress.parse(engine);
    var context = TlsSupport.context(tls);
    var serverName = tls == null || tls.serverName() == null ? "" : tls.serverName().trim();
    var peerHost = serverName.isEmpty() ? address.host() : serverName;
    var rawSocket = new Socket();
    rawSocket.connect(
        new InetSocketAddress(address.host(), address.port()), timeoutMillis(timeout));
    var socket =
        (SSLSocket)
            context.getSocketFactory().createSocket(rawSocket, peerHost, address.port(), true);
    socket.setSoTimeout(timeoutMillis(timeout));
    TlsSupport.configure(socket, peerHost, tls);
    socket.startHandshake();
    socket.setSoTimeout(0);
    return socket;
  }

  private static int timeoutMillis(Duration timeout) {
    var millis = timeout.toMillis();
    if (millis > Integer.MAX_VALUE) return Integer.MAX_VALUE;
    return Math.max(1, (int) millis);
  }
}
