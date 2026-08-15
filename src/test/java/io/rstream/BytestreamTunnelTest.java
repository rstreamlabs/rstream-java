package io.rstream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
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
                    .protocol(TunnelProtocol.TCP)
                    .hostname("tcp.example.com")
                    .port(10042)
                    .build()))
        .isEqualTo("tcp.example.com:10042 (tcp)");
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

  @Test
  void hardCloseClosesAForwarderBeforeItsTaskStarts() throws Exception {
    var executor = singleWorkerExecutor();
    try (var peerListener = loopbackListener();
        var targetListener = loopbackListener();
        var streamSocket = new Socket(peerListener.getInetAddress(), peerListener.getLocalPort());
        var peer = peerListener.accept()) {
      var tunnel = tunnel(executor);
      var forwarding = tunnel.forwardTo("127.0.0.1", targetListener.getLocalPort());
      assertThat(tunnel.deliver(new RstreamStream(streamSocket))).isTrue();
      awaitQueuedTasks(executor, 1);
      tunnel.onClose(null, false);
      peer.setSoTimeout(1_000);
      assertThat(peer.getInputStream().read()).isEqualTo(-1);
      forwarding.get(2, TimeUnit.SECONDS);
      assertThat(executor.getQueue()).isEmpty();
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void softClosePreservesAForwarderBeforeItsTaskStarts() throws Exception {
    var executor = singleWorkerExecutor();
    try (var peerListener = loopbackListener();
        var targetListener = loopbackListener();
        var streamSocket = new Socket(peerListener.getInetAddress(), peerListener.getLocalPort());
        var peer = peerListener.accept()) {
      var tunnel = tunnel(executor);
      var forwarding = tunnel.forwardTo("127.0.0.1", targetListener.getLocalPort());
      assertThat(tunnel.deliver(new RstreamStream(streamSocket))).isTrue();
      awaitQueuedTasks(executor, 1);
      tunnel.onClose(
          new RstreamException("Control transport lost.", "ERR_RSTREAM_CONTROL_LIVENESS"), true);
      try (var target = targetListener.accept()) {
        var bytes = "survives".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        peer.getOutputStream().write(bytes);
        peer.getOutputStream().flush();
        assertThat(target.getInputStream().readNBytes(bytes.length)).isEqualTo(bytes);
        target.getOutputStream().write(bytes);
        target.getOutputStream().flush();
        assertThat(peer.getInputStream().readNBytes(bytes.length)).isEqualTo(bytes);
      }
      forwarding.get(2, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void hardCloseCannotLeakALiveStreamThroughTheAcceptanceRace() throws Exception {
    var executor = Executors.newCachedThreadPool();
    try {
      for (var iteration = 0; iteration < 200; iteration++) {
        var socket = new Socket();
        var tunnel =
            new BytestreamTunnel(
                null, TunnelProperties.builder().id("tun_" + iteration).build(), executor);
        var acceptance = tunnel.acceptAsync();
        assertThat(tunnel.deliver(new RstreamStream(socket))).isTrue();
        tunnel.onClose(null, false);
        try {
          assertThat(acceptance.get(2, TimeUnit.SECONDS).socket().isClosed()).isTrue();
        } catch (ExecutionException error) {
          assertThat(error.getCause()).isInstanceOf(RstreamException.class);
        }
      }
    } finally {
      executor.shutdownNow();
    }
  }

  private static BytestreamTunnel tunnel(ThreadPoolExecutor executor) {
    return new BytestreamTunnel(
        null, TunnelProperties.builder().id("tun_1").name("private-api").build(), executor);
  }

  private static ServerSocket loopbackListener() throws java.io.IOException {
    return new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
  }

  private static ThreadPoolExecutor singleWorkerExecutor() {
    return new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
  }

  private static void awaitQueuedTasks(ThreadPoolExecutor executor, int expected)
      throws InterruptedException {
    var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (executor.getQueue().size() != expected && System.nanoTime() < deadline) {
      Thread.sleep(1);
    }
    assertThat(executor.getQueue()).hasSize(expected);
  }
}
