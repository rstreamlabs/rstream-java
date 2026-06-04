package io.rstream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

final class BytestreamTunnelTest {
  @Test
  void formatsPublishedAndPrivateForwardingAddresses() {
    assertThat(
            BytestreamTunnel.formatForwardingAddress(
                TunnelProperties.builder()
                    .id("tun_1")
                    .name("web")
                    .protocol(TunnelProtocol.HTTP)
                    .hostname("web.example.com")
                    .build()))
        .isEqualTo("https://web.example.com");
    assertThat(
            BytestreamTunnel.formatForwardingAddress(
                TunnelProperties.builder()
                    .id("tun_1")
                    .protocol(TunnelProtocol.HTTP)
                    .hostname("web.example.com")
                    .port(8443)
                    .build()))
        .isEqualTo("https://web.example.com:8443");
    assertThat(
            BytestreamTunnel.formatForwardingAddress(
                TunnelProperties.builder()
                    .id("tun_1")
                    .protocol(TunnelProtocol.TLS)
                    .hostname("tls.example.com")
                    .build()))
        .isEqualTo("tls.example.com:443 (tls)");
    assertThat(
            BytestreamTunnel.formatForwardingAddress(
                TunnelProperties.builder()
                    .id("tun_1")
                    .protocol(TunnelProtocol.QUIC)
                    .host("quic.example.com:443")
                    .build()))
        .isEqualTo("quic.example.com:443 (quic)");
    assertThat(
            BytestreamTunnel.formatForwardingAddress(
                TunnelProperties.builder().id("tun_1").name("private-api").build()))
        .isEqualTo("rstrm://private-api (unpublished)");
    assertThat(
            BytestreamTunnel.formatForwardingAddress(
                TunnelProperties.builder().id("tun_1").build()))
        .isEqualTo("rstrm://tun_1 (unpublished)");
  }

  @Test
  void acceptTimeoutAndCloseArePredictable() throws Exception {
    var executor = Executors.newSingleThreadExecutor();
    try {
      var tunnel =
          new BytestreamTunnel(
              null, TunnelProperties.builder().id("tun_1").name("private-api").build(), executor);
      assertThat(tunnel.accept(Duration.ofMillis(10))).isNull();
      assertThatThrownBy(() -> tunnel.accept(Duration.ofMillis(-1)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("timeout");
      assertThatThrownBy(() -> tunnel.forwardTo("", 8080))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("host");
      assertThatThrownBy(() -> tunnel.forwardTo("127.0.0.1", 0))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("port");
      tunnel.onClose(null);
      assertThat(tunnel.closed()).isTrue();
      assertThatThrownBy(() -> tunnel.accept(Duration.ofSeconds(1)))
          .isInstanceOf(RstreamException.class)
          .hasMessageContaining("Tunnel closed");
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void constructorRejectsMissingTunnelId() {
    var executor = Executors.newSingleThreadExecutor();
    try {
      assertThatThrownBy(
              () ->
                  new BytestreamTunnel(
                      null, TunnelProperties.builder().name("web").build(), executor))
          .isInstanceOf(ProtocolException.class)
          .hasMessageContaining("tunnel ID");
    } finally {
      executor.shutdownNow();
    }
  }
}
