package io.rstream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class RstreamTransportTest {
  @Test
  void redirectedEngineUsesItsHostnameForTls() {
    var tls = TlsOptions.builder().serverName("owner.example.test").build();
    var owner = EngineAddress.parse("owner.example.test:443");
    var ingress = EngineAddress.parse("ingress.example.test:443");
    assertThat(RstreamTransport.peerHost(owner, tls, true)).isEqualTo("owner.example.test");
    assertThat(RstreamTransport.peerHost(ingress, tls, false)).isEqualTo("ingress.example.test");
  }

  @Test
  void tlsHandshakeUsesConfiguredTimeout() throws Exception {
    var accepted = new CountDownLatch(1);
    var executor = Executors.newSingleThreadExecutor();
    try (var listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      executor.submit(
          () -> {
            try (var socket = listener.accept()) {
              socket.setSoTimeout(2_000);
              accepted.countDown();
              Thread.sleep(2_000);
            } catch (Exception ignored) {
            }
          });
      var transport = new RstreamTransport();
      var address = "127.0.0.1:" + listener.getLocalPort();
      assertThatThrownBy(
              () ->
                  transport.dial(
                      address,
                      TlsOptions.builder().insecureSkipVerify(true).build(),
                      Duration.ofMillis(100)))
          .isInstanceOf(java.net.SocketTimeoutException.class);
      accepted.await(1, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
    }
  }
}
