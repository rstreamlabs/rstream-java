package io.rstream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.StringValue;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import rstream.io_rstrm.protobuf.Rstream;

final class RuntimeFakeEngineIT {
  @TempDir Path temp;

  @Test
  void createTunnelSendsNormalizedPropertiesAndCloses() throws Exception {
    try (var engine = FakeEngine.start(temp);
        var client = client(engine)) {
      try (var control = client.connect()) {
        var tunnel =
            control.createTunnel(
                CreateTunnelOptions.builder()
                    .name("web")
                    .publish(true)
                    .protocol(TunnelProtocol.HTTP)
                    .labels(Map.of("service", "api"))
                    .trustedIps(List.of("10.0.0.0/8"))
                    .auth(TunnelAuth.builder().token(true).rstream(false).challenge(true).build())
                    .build());
        var request = engine.openTunnelRequests.poll(2, TimeUnit.SECONDS);
        assertThat(tunnel.id()).isEqualTo("tun_1");
        assertThat(tunnel.forwardingAddress()).isEqualTo("https://web.example.test");
        assertThat(request.getTunnelProperties().getName().getValue()).isEqualTo("web");
        assertThat(request.getTunnelProperties().getPublish().getValue()).isTrue();
        assertThat(request.getTunnelProperties().getProtocol().getValue()).isEqualTo("http");
        assertThat(request.getTunnelProperties().getLabelsMap()).containsEntry("service", "api");
        assertThat(request.getTunnelProperties().getTrustedIpsList()).containsExactly("10.0.0.0/8");
        assertThat(request.getTunnelProperties().getTokenAuth().getValue()).isTrue();
        assertThat(request.getTunnelProperties().getRstreamAuth().getValue()).isFalse();
        assertThat(request.getTunnelProperties().getChallengeMode().getValue()).isTrue();
        control.closeTunnel(tunnel.id());
        assertThat(tunnel.closed()).isTrue();
      }
    }
  }

  @Test
  void negotiatedLivenessToleratesDelayedAcknowledgement() throws Exception {
    try (var engine = FakeEngine.start(temp);
        var client = heartbeatClient(engine, Duration.ofSeconds(1))) {
      engine.configureLiveness(1_000, 1_000, true, 800, 1, 0);
      try (var control = client.connect()) {
        var heartbeat = engine.heartbeats.poll(2, TimeUnit.SECONDS);
        assertThat(heartbeat).isNotNull();
        assertThat(engine.openControlRequests.peek().getLiveness().getHeartbeatIntervalMs())
            .isEqualTo(1_000);
        assertThat(heartbeat.getSequence()).isEqualTo(1);
        Thread.sleep(1_100);
        assertThat(control.closed()).isFalse();
      }
    }
  }

  @Test
  void negotiatedLivenessExpiresWhenAcknowledgementsStop() throws Exception {
    try (var engine = FakeEngine.start(temp);
        var client = heartbeatClient(engine, Duration.ofSeconds(1))) {
      engine.configureLiveness(1_000, 1_000, false, 0, 1, 0);
      var control = client.connect();
      assertThat(failureCode(control, 2, TimeUnit.SECONDS))
          .isEqualTo("ERR_RSTREAM_CONTROL_LIVENESS");
    }
  }

  @Test
  void acceptedStreamSurvivesLivenessTimeout() throws Exception {
    try (var engine = FakeEngine.start(temp);
        var client = heartbeatClient(engine, Duration.ofSeconds(1))) {
      engine.configureLiveness(1_000, 1_000, false, 0, 1, 0);
      var control = client.connect();
      var tunnel = control.createTunnel();
      engine.sendProxyConnection(tunnel.id(), "draining-accepted", "stream-secret");
      try (var stream = tunnel.accept(Duration.ofSeconds(2))) {
        assertThat(engine.proxyConnectionResponses.poll(2, TimeUnit.SECONDS)).isNotNull();
        assertThat(roundTrip(stream.socket(), "before")).isEqualTo("before");
        assertThat(failureCode(control, 2, TimeUnit.SECONDS))
            .isEqualTo("ERR_RSTREAM_CONTROL_LIVENESS");
        assertThat(tunnel.closed()).isTrue();
        assertThat(roundTrip(stream.socket(), "after")).isEqualTo("after");
      }
    }
  }

  @Test
  void forwardedStreamSurvivesLivenessTimeout() throws Exception {
    try (var local = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        var engine = FakeEngine.start(temp);
        var client = heartbeatClient(engine, Duration.ofSeconds(1))) {
      engine.configureLiveness(1_000, 1_000, false, 0, 1, 0);
      var control = client.connect();
      var tunnel = control.createTunnel();
      var forwarding = tunnel.forwardTo("127.0.0.1", local.getLocalPort());
      var accepted = CompletableFuture.supplyAsync(() -> accept(local));
      engine.sendProxyConnection(tunnel.id(), "draining-forward", "stream-secret");
      try (var localStream = accepted.get(2, TimeUnit.SECONDS)) {
        assertThat(engine.proxyConnectionResponses.poll(2, TimeUnit.SECONDS)).isNotNull();
        assertThat(roundTrip(localStream, "before")).isEqualTo("before");
        assertThat(failureCode(control, 2, TimeUnit.SECONDS))
            .isEqualTo("ERR_RSTREAM_CONTROL_LIVENESS");
        assertThat(roundTrip(localStream, "after")).isEqualTo("after");
      }
      forwarding.get(2, TimeUnit.SECONDS);
    }
  }

  @Test
  void explicitControlCloseStopsForwardedStreamLocally() throws Exception {
    try (var local = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        var engine = FakeEngine.start(temp);
        var client = client(engine)) {
      var control = client.connect();
      var tunnel = control.createTunnel();
      var forwarding = tunnel.forwardTo("127.0.0.1", local.getLocalPort());
      var accepted = CompletableFuture.supplyAsync(() -> accept(local));
      engine.sendProxyConnection(tunnel.id(), "hard-close-forward", "stream-secret");
      try (var localStream = accepted.get(2, TimeUnit.SECONDS)) {
        assertThat(engine.proxyConnectionResponses.poll(2, TimeUnit.SECONDS)).isNotNull();
        assertThat(roundTrip(localStream, "before")).isEqualTo("before");
        control.close();
        localStream.setSoTimeout(500);
        assertThat(localStream.getInputStream().read()).isEqualTo(-1);
      }
      forwarding.get(2, TimeUnit.SECONDS);
    }
  }

  @Test
  void explicitControlCloseStopsAcceptedStreamLocally() throws Exception {
    try (var engine = FakeEngine.start(temp);
        var client = client(engine)) {
      var control = client.connect();
      var tunnel = control.createTunnel();
      engine.sendProxyConnection(tunnel.id(), "hard-close-accepted", "stream-secret");
      try (var stream = tunnel.accept(Duration.ofSeconds(2))) {
        assertThat(engine.proxyConnectionResponses.poll(2, TimeUnit.SECONDS)).isNotNull();
        assertThat(roundTrip(stream.socket(), "before")).isEqualTo("before");
        control.close();
        assertThat(stream.socket().isClosed()).isTrue();
      }
    }
  }

  @Test
  void localHardCloseAfterLivenessTimeoutStopsAcceptedStream() throws Exception {
    try (var engine = FakeEngine.start(temp);
        var client = heartbeatClient(engine, Duration.ofSeconds(1))) {
      engine.configureLiveness(1_000, 1_000, false, 0, 1, 0);
      var control = client.connect();
      var tunnel = control.createTunnel();
      engine.sendProxyConnection(tunnel.id(), "soft-then-hard-accepted", "stream-secret");
      try (var stream = tunnel.accept(Duration.ofSeconds(2))) {
        assertThat(engine.proxyConnectionResponses.poll(2, TimeUnit.SECONDS)).isNotNull();
        assertThat(roundTrip(stream.socket(), "before")).isEqualTo("before");
        assertThat(failureCode(control, 2, TimeUnit.SECONDS))
            .isEqualTo("ERR_RSTREAM_CONTROL_LIVENESS");
        assertThat(roundTrip(stream.socket(), "after-soft-close")).isEqualTo("after-soft-close");
        tunnel.close();
        assertThat(stream.socket().isClosed()).isTrue();
      }
    }
  }

  @Test
  void malformedControlFrameStopsForwardedStreamLocally() throws Exception {
    try (var local = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        var engine = FakeEngine.start(temp);
        var client = client(engine)) {
      var control = client.connect();
      var tunnel = control.createTunnel();
      var forwarding = tunnel.forwardTo("127.0.0.1", local.getLocalPort());
      var accepted = CompletableFuture.supplyAsync(() -> accept(local));
      engine.sendProxyConnection(tunnel.id(), "protocol-failure-forward", "stream-secret");
      try (var localStream = accepted.get(2, TimeUnit.SECONDS)) {
        assertThat(engine.proxyConnectionResponses.poll(2, TimeUnit.SECONDS)).isNotNull();
        assertThat(roundTrip(localStream, "before")).isEqualTo("before");
        engine.sendMalformedControlFrame();
        assertThat(failureCode(control, 2, TimeUnit.SECONDS)).isEqualTo("ERR_RSTREAM_PROTOCOL");
        localStream.setSoTimeout(500);
        assertThat(localStream.getInputStream().read()).isEqualTo(-1);
      }
      forwarding.get(2, TimeUnit.SECONDS);
    }
  }

  @Test
  void zeroRttDirectStreamDoesNotInheritOperationTimeout() throws Exception {
    try (var engine = FakeEngine.start(temp);
        var client = timeoutClient(engine, true)) {
      CompletableFuture<Integer> blockedRead;
      try (var stream = client.dial("private-api")) {
        blockedRead =
            CompletableFuture.supplyAsync(
                () -> {
                  try {
                    return stream.inputStream().read();
                  } catch (IOException error) {
                    throw new RstreamException("Test read failed.", "ERR_TEST_READ", error);
                  }
                });
        Thread.sleep(250);
        assertThat(blockedRead).isNotDone();
      }
      blockedRead.handle((value, error) -> null).get(2, TimeUnit.SECONDS);
    }
  }

  @Test
  void zeroRttForwarderDoesNotInheritOperationTimeout() throws Exception {
    try (var local = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        var engine = FakeEngine.start(temp);
        var client = timeoutClient(engine, true);
        var control = client.connect()) {
      var tunnel = control.createTunnel();
      var forwarding = tunnel.forwardTo("127.0.0.1", local.getLocalPort());
      var accepted = CompletableFuture.supplyAsync(() -> accept(local));
      engine.sendProxyConnection(tunnel.id(), "zero-rtt-timeout-forward", "stream-secret");
      try (var localStream = accepted.get(2, TimeUnit.SECONDS)) {
        assertThat(engine.proxyConnectionResponses.poll(2, TimeUnit.SECONDS)).isNotNull();
        assertThat(roundTrip(localStream, "before")).isEqualTo("before");
        Thread.sleep(250);
        assertThat(roundTrip(localStream, "after")).isEqualTo("after");
      }
      tunnel.close();
      forwarding.get(2, TimeUnit.SECONDS);
    }
  }

  @Test
  void acceptedStreamSurvivesUnexpectedControlTransportEof() throws Exception {
    try (var engine = FakeEngine.start(temp);
        var client = client(engine)) {
      var control = client.connect();
      var tunnel = control.createTunnel();
      engine.sendProxyConnection(tunnel.id(), "eof-accepted", "stream-secret");
      try (var stream = tunnel.accept(Duration.ofSeconds(2))) {
        assertThat(engine.proxyConnectionResponses.poll(2, TimeUnit.SECONDS)).isNotNull();
        engine.closeControlSocket();
        assertThatThrownBy(() -> control.done().get(2, TimeUnit.SECONDS))
            .isInstanceOf(ExecutionException.class);
        assertThat(roundTrip(stream.socket(), "survives")).isEqualTo("survives");
      }
    }
  }

  @Test
  void negotiatedLivenessRejectsFutureAcknowledgement() throws Exception {
    try (var engine = FakeEngine.start(temp);
        var client = heartbeatClient(engine, Duration.ofSeconds(1))) {
      engine.configureLiveness(1_000, 60_000, true, 0, 1, 1);
      var control = client.connect();
      assertThat(failureCode(control, 2, TimeUnit.SECONDS)).isEqualTo("ERR_RSTREAM_PROTOCOL");
    }
  }

  @Test
  void negotiatedLivenessRejectsReplayedAcknowledgement() throws Exception {
    try (var engine = FakeEngine.start(temp);
        var client = heartbeatClient(engine, Duration.ofSeconds(1))) {
      engine.configureLiveness(1_000, 60_000, true, 0, 1, 0);
      engine.duplicateHeartbeatAcknowledgement = true;
      var control = client.connect();
      assertThat(failureCode(control, 2, TimeUnit.SECONDS)).isEqualTo("ERR_RSTREAM_PROTOCOL");
    }
  }

  @Test
  void negotiatedLivenessToleratesDroppedHeartbeat() throws Exception {
    try (var engine = FakeEngine.start(temp);
        var client = heartbeatClient(engine, Duration.ofSeconds(1))) {
      engine.configureLiveness(1_000, 2_500, true, 0, 2, 0);
      try (var control = client.connect()) {
        for (var sequence = 1L; sequence <= 4L; sequence++) {
          var heartbeat = engine.heartbeats.poll(3, TimeUnit.SECONDS);
          assertThat(heartbeat).isNotNull();
          assertThat(heartbeat.getSequence()).isEqualTo(sequence);
        }
        assertThat(control.closed()).isFalse();
      }
    }
  }

  @Test
  void connectRejectsInvalidServerLivenessPolicies() throws Exception {
    for (var policy :
        List.of(new int[] {2_000, 60_000}, new int[] {1_000, 999}, new int[] {1_000, 900_001})) {
      try (var engine = FakeEngine.start(temp);
          var client = heartbeatClient(engine, Duration.ofSeconds(1))) {
        engine.configureLiveness(policy[0], policy[1], false, 0, 1, 0);
        assertThatThrownBy(client::connect).isInstanceOf(ProtocolException.class);
      }
    }
    try (var engine = FakeEngine.start(temp);
        var client = client(engine)) {
      engine.configureLiveness(1_000, 60_000, false, 0, 1, 0);
      assertThatThrownBy(client::connect).isInstanceOf(ProtocolException.class);
    }
  }

  @Test
  void livenessIsNotStarvedByStalledProxyTlsHandshake() throws Exception {
    try (var blackhole = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        var engine = FakeEngine.start(temp);
        var client = heartbeatClient(engine, Duration.ofMillis(500))) {
      engine.configureLiveness(1_000, 1_500, true, 0, 1, 0);
      try (var control = client.connect()) {
        var tunnel = control.createTunnel();
        var accepted = CompletableFuture.supplyAsync(() -> accept(blackhole));
        engine.sendProxyConnection(
            tunnel.id(),
            "blocked-stream",
            "stream-secret",
            "127.0.0.1:" + blackhole.getLocalPort());
        try (var blackholeSocket = accepted.get(1, TimeUnit.SECONDS)) {
          assertThat(blackholeSocket.isClosed()).isFalse();
          Thread.sleep(1_900);
          assertThat(control.closed()).isFalse();
          assertThat(control.createTunnel().id()).isNotBlank();
        }
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void dialPrivateBytestreamByNameAndId(boolean zeroRtt) throws Exception {
    try (var engine = FakeEngine.start(temp);
        var client = client(engine)) {
      for (var target : List.of("private-api", "tun_private")) {
        try (var stream = client.dial(target, DialOptions.builder().zeroRtt(zeroRtt).build())) {
          stream.outputStream().write("ping".getBytes(StandardCharsets.UTF_8));
          stream.outputStream().flush();
          assertThat(stream.inputStream().readNBytes(4))
              .isEqualTo("ping".getBytes(StandardCharsets.UTF_8));
        }
        var request = engine.streamRequests.poll(2, TimeUnit.SECONDS);
        assertThat(request.getTunnelIdName()).isEqualTo(target);
        assertThat(request.getZeroRtt().getValue()).isEqualTo(zeroRtt);
      }
    }
  }

  @Test
  void proxyConnectionIsDeliveredToTunnel() throws Exception {
    try (var engine = FakeEngine.start(temp);
        var client = client(engine)) {
      try (var control = client.connect()) {
        var tunnel =
            control.createTunnel(
                CreateTunnelOptions.builder().name("web").protocol(TunnelProtocol.HTTP).build());
        engine.sendProxyConnection(tunnel.id(), "stream_1", "stream-secret");
        try (var stream = tunnel.accept(Duration.ofSeconds(2))) {
          stream.outputStream().write("pong".getBytes(StandardCharsets.UTF_8));
          stream.outputStream().flush();
          assertThat(stream.inputStream().readNBytes(4))
              .isEqualTo("pong".getBytes(StandardCharsets.UTF_8));
        }
        var proxyRequest = engine.proxyRequests.poll(2, TimeUnit.SECONDS);
        var proxyResponse = engine.proxyConnectionResponses.poll(2, TimeUnit.SECONDS);
        assertThat(proxyRequest.getStreamId()).isEqualTo("stream_1");
        assertThat(proxyRequest.getClientDetails().getToken().getValue())
            .isEqualTo("stream-secret");
        assertThat(proxyResponse.getStreamId()).isEqualTo("stream_1");
        assertThat(proxyResponse.hasError()).isFalse();
      }
    }
  }

  @Test
  void controlCloseReleasesAllConcurrentAcceptWaiters() throws Exception {
    try (var engine = FakeEngine.start(temp);
        var client = client(engine)) {
      var control = client.connect();
      var tunnel = control.createTunnel();
      var accepts = IntStream.range(0, 8).mapToObj(ignored -> tunnel.acceptAsync()).toList();
      Thread.sleep(50);

      control.close();

      assertThat(accepts).allMatch(CompletableFuture::isDone);
      assertThat(accepts).allMatch(CompletableFuture::isCompletedExceptionally);
    }
  }

  @Test
  void controlCloseClosesUnacceptedProxyStream() throws Exception {
    try (var engine = FakeEngine.start(temp);
        var client = client(engine)) {
      var control = client.connect();
      var tunnel = control.createTunnel();
      engine.sendProxyConnection(tunnel.id(), "unaccepted-stream", "stream-secret");
      assertThat(engine.proxyConnectionResponses.poll(2, TimeUnit.SECONDS)).isNotNull();
      var proxyClosed = engine.proxyClosures.poll(2, TimeUnit.SECONDS);
      assertThat(proxyClosed).isNotNull();

      control.close();

      proxyClosed.get(1, TimeUnit.SECONDS);
    }
  }

  @Test
  void proxyConnectionCanDialIngressEngine() throws Exception {
    try (var owner = FakeEngine.start(temp);
        var ingress = FakeEngine.start(temp);
        var client = client(owner, false, "owner-pat");
        var control = client.connect()) {
      var tunnel =
          control.createTunnel(
              CreateTunnelOptions.builder().name("web").protocol(TunnelProtocol.HTTP).build());
      owner.sendProxyConnection(tunnel.id(), "stream_direct", "stream-secret", ingress.address());
      try (var stream = tunnel.accept(Duration.ofSeconds(2))) {
        stream.outputStream().write("direct".getBytes(StandardCharsets.UTF_8));
        stream.outputStream().flush();
        assertThat(stream.inputStream().readNBytes(6))
            .isEqualTo("direct".getBytes(StandardCharsets.UTF_8));
      }
      var response = owner.proxyConnectionResponses.poll(2, TimeUnit.SECONDS);
      assertThat(response.hasError()).isFalse();
      assertThat(owner.proxyRequests.poll(100, TimeUnit.MILLISECONDS)).isNull();
      var proxyRequest = ingress.proxyRequests.poll(2, TimeUnit.SECONDS);
      assertThat(proxyRequest.getStreamId()).isEqualTo("stream_direct");
      assertThat(proxyRequest.getClientDetails().getToken().getValue()).isEqualTo("stream-secret");
    }
  }

  @Test
  void proxyRedirectWithoutStreamSecretIsRejected() throws Exception {
    try (var owner = FakeEngine.start(temp);
        var ingress = FakeEngine.start(temp);
        var client = client(owner);
        var control = client.connect()) {
      var tunnel = control.createTunnel(CreateTunnelOptions.builder().name("web").build());
      owner.sendProxyConnection(tunnel.id(), "stream_missing_secret", null, ingress.address());
      var response = owner.proxyConnectionResponses.poll(2, TimeUnit.SECONDS);
      assertThat(response.hasError()).isTrue();
      assertThat(response.getError().getMessage().getValue()).contains("credentials");
      assertThat(owner.proxyRequests.poll(100, TimeUnit.MILLISECONDS)).isNull();
      assertThat(ingress.proxyRequests.poll(100, TimeUnit.MILLISECONDS)).isNull();
    }
  }

  @Test
  void proxyRedirectWithEmptyStreamSecretIsRejected() throws Exception {
    try (var owner = FakeEngine.start(temp);
        var ingress = FakeEngine.start(temp);
        var client = client(owner);
        var control = client.connect()) {
      var tunnel = control.createTunnel(CreateTunnelOptions.builder().name("web").build());
      owner.sendProxyConnection(tunnel.id(), "stream_empty_secret", "", ingress.address());
      var response = owner.proxyConnectionResponses.poll(2, TimeUnit.SECONDS);
      assertThat(response.hasError()).isTrue();
      assertThat(response.getError().getMessage().getValue()).contains("credentials");
      assertThat(owner.proxyRequests.poll(100, TimeUnit.MILLISECONDS)).isNull();
      assertThat(ingress.proxyRequests.poll(100, TimeUnit.MILLISECONDS)).isNull();
    }
  }

  @Test
  void concurrentTunnelCreationUsesOneControlChannelSafely() throws Exception {
    var count = 24;
    try (var engine = FakeEngine.start(temp);
        var client = client(engine);
        var control = client.connect()) {
      var futures =
          IntStream.range(0, count)
              .mapToObj(
                  index ->
                      control.createTunnelAsync(
                          CreateTunnelOptions.builder().name("web-" + index).build()))
              .toList();
      var tunnels = new ArrayList<BytestreamTunnel>();
      for (var future : futures) tunnels.add(future.get(5, TimeUnit.SECONDS));
      assertThat(tunnels).extracting(BytestreamTunnel::id).doesNotHaveDuplicates().hasSize(count);
      assertThat(drain(engine.openTunnelRequests, count))
          .extracting(request -> request.getTunnelProperties().getName().getValue())
          .containsExactlyInAnyOrderElementsOf(
              IntStream.range(0, count).mapToObj(index -> "web-" + index).toList());
    }
  }

  @Test
  void concurrentDialsRoundTripIndependently() throws Exception {
    var count = 32;
    try (var engine = FakeEngine.start(temp);
        var client = client(engine)) {
      var futures =
          IntStream.range(0, count)
              .mapToObj(
                  index ->
                      CompletableFuture.supplyAsync(
                          () ->
                              dialEcho(client, "private-api", index % 2 == 0, "payload-" + index)))
              .toList();
      for (var index = 0; index < count; index++) {
        assertThat(futures.get(index).get(5, TimeUnit.SECONDS)).isEqualTo("payload-" + index);
      }
      var requests = drain(engine.streamRequests, count);
      assertThat(requests)
          .extracting(Rstream.StreamReq::getTunnelIdName)
          .containsOnly("private-api");
      assertThat(requests)
          .extracting(request -> request.getZeroRtt().getValue())
          .contains(true, false);
    }
  }

  @Test
  void concurrentProxyConnectionsCanBeAcceptedAndEchoed() throws Exception {
    var count = 16;
    try (var engine = FakeEngine.start(temp);
        var client = client(engine);
        var control = client.connect()) {
      var tunnel = control.createTunnel(CreateTunnelOptions.builder().name("web").build());
      var accepts = IntStream.range(0, count).mapToObj(index -> tunnel.acceptAsync()).toList();
      for (var index = 0; index < count; index++) {
        engine.sendProxyConnection(tunnel.id(), "stream_" + index, "secret-" + index);
      }
      for (var index = 0; index < count; index++) {
        try (var stream = accepts.get(index).get(5, TimeUnit.SECONDS)) {
          var payload = ("proxy-" + index).getBytes(StandardCharsets.UTF_8);
          stream.outputStream().write(payload);
          stream.outputStream().flush();
          assertThat(stream.inputStream().readNBytes(payload.length)).isEqualTo(payload);
        }
      }
      assertThat(drain(engine.proxyRequests, count))
          .extracting(Rstream.ProxyReq::getStreamId)
          .containsExactlyInAnyOrderElementsOf(
              IntStream.range(0, count).mapToObj(index -> "stream_" + index).toList());
      assertThat(drain(engine.proxyConnectionResponses, count))
          .allSatisfy(response -> assertThat(response.hasError()).isFalse());
    }
  }

  @Test
  void concurrentControlCloseIsIdempotent() throws Exception {
    try (var engine = FakeEngine.start(temp);
        var client = client(engine)) {
      var control = client.connect();
      var closes =
          IntStream.range(0, 8)
              .mapToObj(index -> CompletableFuture.runAsync(control::close))
              .toList();
      for (var close : closes) close.get(5, TimeUnit.SECONDS);
      assertThat(control.closed()).isTrue();
    }
  }

  @Test
  void concurrentTunnelCloseIsIdempotent() throws Exception {
    try (var engine = FakeEngine.start(temp);
        var client = client(engine);
        var control = client.connect()) {
      var tunnel = control.createTunnel(CreateTunnelOptions.builder().name("web").build());
      var closes =
          IntStream.range(0, 8).mapToObj(index -> control.closeTunnelAsync(tunnel.id())).toList();
      for (var close : closes) close.get(5, TimeUnit.SECONDS);
      assertThat(tunnel.closed()).isTrue();
      assertThat(engine.closeTunnelRequests.poll(2, TimeUnit.SECONDS).getTunnelId())
          .isEqualTo(tunnel.id());
      assertThat(engine.closeTunnelRequests.poll(100, TimeUnit.MILLISECONDS)).isNull();
    }
  }

  @Test
  void asyncConvenienceMethodsComplete() throws Exception {
    try (var engine = FakeEngine.start(temp);
        var client = client(engine);
        var control = client.connectAsync().get(5, TimeUnit.SECONDS)) {
      var tunnel = control.createTunnelAsync().get(5, TimeUnit.SECONDS);
      assertThat(tunnel.id()).isEqualTo("tun_1");
      control.closeTunnelAsync(tunnel.id()).get(5, TimeUnit.SECONDS);
      assertThat(tunnel.closed()).isTrue();
      control.closeAsync().get(5, TimeUnit.SECONDS);
      assertThat(control.closed()).isTrue();
    }
  }

  @Test
  void clientRejectsUseAfterClose() throws Exception {
    try (var engine = FakeEngine.start(temp)) {
      var client = client(engine);
      client.close();
      assertThatThrownBy(client::connect)
          .isInstanceOf(RstreamException.class)
          .hasMessageContaining("client is closed");
      assertThatThrownBy(() -> client.dial("private-api"))
          .isInstanceOf(RstreamException.class)
          .hasMessageContaining("client is closed");
      assertThatThrownBy(() -> client.connectAsync().get(1, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .hasCauseInstanceOf(RstreamException.class);
      assertThatThrownBy(() -> client.dialAsync("private-api").get(1, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .hasCauseInstanceOf(RstreamException.class);
    }
  }

  @Test
  void clientCloseClosesOpenControlChannels() throws Exception {
    try (var engine = FakeEngine.start(temp)) {
      var client = client(engine);
      var control = client.connect();

      client.close();

      assertThat(control.closed()).isTrue();
      assertThat(control.done()).isCompleted();
      client.close();
    }
  }

  @Test
  void clientCloseWinsRaceWithControlRegistration() throws Exception {
    try (var engine = FakeEngine.start(temp)) {
      var client = client(engine);
      engine.pauseControlResponse();
      var connecting = CompletableFuture.supplyAsync(client::connect);
      assertThat(engine.openControlRequests.poll(2, TimeUnit.SECONDS)).isNotNull();

      client.close();
      engine.resumeControlResponse();

      assertThatThrownBy(() -> connecting.get(2, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .hasCauseInstanceOf(RstreamException.class)
          .hasRootCauseMessage("rstream client is closed.");
    }
  }

  @Test
  void controlOpenTimeoutIsBounded() throws Exception {
    try (var engine = FakeEngine.start(temp);
        var client = timeoutClient(engine)) {
      engine.nextControlHang = true;
      assertThatThrownBy(client::connect)
          .isInstanceOf(RstreamException.class)
          .hasMessageContaining("Timed out")
          .extracting("code")
          .isEqualTo("ERR_RSTREAM_OPERATION_TIMEOUT");
    }
  }

  @Test
  void controlChannelDoneCompletesExceptionallyWhenEngineStops() throws Exception {
    try (var engine = FakeEngine.start(temp);
        var client = client(engine);
        var control = client.connect()) {
      engine.closeControlSocket();
      assertThatThrownBy(() -> control.done().get(5, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class);
    }
  }

  @Test
  void tlsServerNameOverrideIsUsedForVerification() throws Exception {
    try (var engine = FakeEngine.start(temp);
        var client =
            RstreamClient.fromEnv(
                ClientOptions.builder()
                    .engine(engine.address())
                    .readConfigFile(false)
                    .noToken(true)
                    .heartbeat(false)
                    .tls(
                        TlsOptions.builder()
                            .caFile(engine.certificatePath().toString())
                            .serverName("localhost")
                            .build())
                    .build())) {
      try (var control = client.connect()) {
        assertThat(control.serverDetails().agent()).isEqualTo("fake-engine");
      }
    }
  }

  @Test
  void controlChannelEngineErrorsAreSurfaced() throws Exception {
    try (var engine = FakeEngine.start(temp);
        var client = client(engine)) {
      engine.nextControlError = true;
      assertThatThrownBy(client::connect)
          .isInstanceOf(EngineException.class)
          .hasMessageContaining("control failed");
    }
  }

  @Test
  void openTunnelEngineErrorsAreSurfaced() throws Exception {
    try (var engine = FakeEngine.start(temp);
        var client = client(engine)) {
      engine.nextOpenTunnelError = true;
      try (var control = client.connect()) {
        assertThatThrownBy(() -> control.createTunnel(CreateTunnelOptions.defaults()))
            .isInstanceOf(EngineException.class)
            .hasMessageContaining("open failed");
      }
    }
  }

  @Test
  void openTunnelTimeoutIsBoundedAndCleansPendingRequest() throws Exception {
    try (var engine = FakeEngine.start(temp);
        var client = timeoutClient(engine);
        var control = client.connect()) {
      engine.nextOpenTunnelHang = true;
      assertThatThrownBy(() -> control.createTunnel(CreateTunnelOptions.defaults()))
          .isInstanceOf(RstreamException.class)
          .hasMessageContaining("Timed out")
          .extracting("code")
          .isEqualTo("ERR_RSTREAM_OPERATION_TIMEOUT");
      assertThat(engine.openTunnelRequests.poll(2, TimeUnit.SECONDS)).isNotNull();
      var tunnel = control.createTunnel(CreateTunnelOptions.builder().name("retry").build());
      assertThat(tunnel.id()).isEqualTo("tun_1");
    }
  }

  @Test
  void closeTunnelTimeoutIsBoundedAndCanBeRetried() throws Exception {
    try (var engine = FakeEngine.start(temp);
        var client = timeoutClient(engine);
        var control = client.connect()) {
      var tunnel = control.createTunnel(CreateTunnelOptions.builder().name("web").build());
      engine.nextCloseTunnelHang = true;
      assertThatThrownBy(() -> control.closeTunnel(tunnel.id()))
          .isInstanceOf(RstreamException.class)
          .hasMessageContaining("Timed out")
          .extracting("code")
          .isEqualTo("ERR_RSTREAM_OPERATION_TIMEOUT");
      control.closeTunnel(tunnel.id());
      assertThat(tunnel.closed()).isTrue();
    }
  }

  @Test
  void closeControlChannelTimeoutIsBounded() throws Exception {
    try (var engine = FakeEngine.start(temp);
        var client = timeoutClient(engine)) {
      var control = client.connect();
      engine.nextCloseControlHang = true;
      assertThatThrownBy(control::close)
          .isInstanceOf(RstreamException.class)
          .hasMessageContaining("Timed out")
          .extracting("code")
          .isEqualTo("ERR_RSTREAM_OPERATION_TIMEOUT");
      assertThat(control.closed()).isTrue();
    }
  }

  @Test
  void emptyOpenTunnelResponsesAreProtocolErrors() throws Exception {
    try (var engine = FakeEngine.start(temp);
        var client = client(engine);
        var control = client.connect()) {
      engine.nextOpenTunnelEmptyResponse = true;
      assertThatThrownBy(() -> control.createTunnel(CreateTunnelOptions.defaults()))
          .isInstanceOf(ProtocolException.class)
          .hasMessageContaining("empty OpenTunnelRsp");
    }
  }

  @Test
  void streamEngineErrorsAreSurfaced() throws Exception {
    try (var engine = FakeEngine.start(temp);
        var client = client(engine)) {
      engine.nextStreamError = true;
      assertThatThrownBy(
              () -> client.dial("private-api", DialOptions.builder().zeroRtt(false).build()))
          .isInstanceOf(EngineException.class)
          .hasMessageContaining("stream failed");
    }
  }

  @Test
  void streamHandshakeTimeoutIsBounded() throws Exception {
    try (var engine = FakeEngine.start(temp);
        var client = timeoutClient(engine)) {
      engine.nextStreamHang = true;
      assertThatThrownBy(
              () -> client.dial("private-api", DialOptions.builder().zeroRtt(false).build()))
          .isInstanceOf(RstreamException.class)
          .hasMessageContaining("Timed out")
          .extracting("code")
          .isEqualTo("ERR_RSTREAM_OPERATION_TIMEOUT");
    }
  }

  @Test
  void emptyStreamResponsesAreProtocolErrors() throws Exception {
    try (var engine = FakeEngine.start(temp);
        var client = client(engine)) {
      engine.nextStreamEmptyResponse = true;
      assertThatThrownBy(
              () -> client.dial("private-api", DialOptions.builder().zeroRtt(false).build()))
          .isInstanceOf(ProtocolException.class)
          .hasMessageContaining("empty StreamRsp");
    }
  }

  @Test
  void proxyConnectionForUnknownTunnelIsRejectedWithoutOpeningProxyStream() throws Exception {
    try (var engine = FakeEngine.start(temp);
        var client = client(engine);
        var control = client.connect()) {
      assertThat(control.serverDetails().agent()).isEqualTo("fake-engine");
      engine.sendProxyConnection("missing", "stream_missing", "stream-secret");
      var response = engine.proxyConnectionResponses.poll(2, TimeUnit.SECONDS);
      assertThat(response.getStreamId()).isEqualTo("stream_missing");
      assertThat(response.hasError()).isTrue();
      assertThat(engine.proxyRequests.poll(100, TimeUnit.MILLISECONDS)).isNull();
    }
  }

  @Test
  void proxyHandshakeTimeoutIsReportedToEngine() throws Exception {
    try (var engine = FakeEngine.start(temp);
        var client = timeoutClient(engine, false);
        var control = client.connect()) {
      var tunnel = control.createTunnel(CreateTunnelOptions.builder().name("web").build());
      engine.nextProxyHang = true;
      engine.sendProxyConnection(tunnel.id(), "stream_timeout", "stream-secret");
      var response = engine.proxyConnectionResponses.poll(2, TimeUnit.SECONDS);
      assertThat(response.getStreamId()).isEqualTo("stream_timeout");
      assertThat(response.hasError()).isTrue();
      assertThat(response.getError().getMessage().getValue()).contains("Timed out");
    }
  }

  @Test
  void unsupportedTunnelFamiliesAreRejectedBeforeRequest() throws Exception {
    try (var engine = FakeEngine.start(temp);
        var client = client(engine);
        var control = client.connect()) {
      assertThatThrownBy(
              () ->
                  control.createTunnel(
                      CreateTunnelOptions.builder().type(TunnelType.DATAGRAM).build()))
          .isInstanceOf(UnsupportedFeatureException.class);
      assertThatThrownBy(
              () ->
                  control.createTunnel(
                      CreateTunnelOptions.builder().httpVersion(HttpVersion.H3).build()))
          .isInstanceOf(UnsupportedFeatureException.class);
    }
  }

  @Test
  void privateTunnelProtocolOptionsAreSent() throws Exception {
    try (var engine = FakeEngine.start(temp);
        var client = client(engine);
        var control = client.connect()) {
      control.createTunnel(
          CreateTunnelOptions.builder()
              .name("private-api")
              .publish(false)
              .protocol(TunnelProtocol.HTTP)
              .httpVersion(HttpVersion.H2C)
              .build());
      var request = engine.openTunnelRequests.poll(2, TimeUnit.SECONDS);
      assertThat(request).isNotNull();
      assertThat(request.getTunnelProperties().getPublish().getValue()).isFalse();
      assertThat(request.getTunnelProperties().getProtocol().getValue()).isEqualTo("http");
      assertThat(request.getTunnelProperties().getHttpVersion().getValue()).isEqualTo("h2c");
    }
  }

  @Test
  void publishedTCPOptionsAreSent() throws Exception {
    try (var engine = FakeEngine.start(temp);
        var client = client(engine);
        var control = client.connect()) {
      control.createTunnel(
          CreateTunnelOptions.builder()
              .name("ssh")
              .protocol(TunnelProtocol.TCP)
              .port(10042)
              .allowCrossRegionRouting(true)
              .build());
      var request = engine.openTunnelRequests.poll(2, TimeUnit.SECONDS);
      assertThat(request).isNotNull();
      assertThat(request.getTunnelProperties().getType().getValue()).isEqualTo("bytestream");
      assertThat(request.getTunnelProperties().getPublish().getValue()).isTrue();
      assertThat(request.getTunnelProperties().getProtocol().getValue()).isEqualTo("tcp");
      assertThat(request.getTunnelProperties().getPort().getValue()).isEqualTo(10042);
      assertThat(request.getTunnelProperties().getAllowCrossRegionRouting().getValue()).isTrue();
    }
  }

  @Test
  void publishedTCPRejectsIncompatibleOptionsBeforeRequest() throws Exception {
    try (var engine = FakeEngine.start(temp);
        var client = client(engine);
        var control = client.connect()) {
      assertThatThrownBy(
              () ->
                  control.createTunnel(
                      CreateTunnelOptions.builder()
                          .protocol(TunnelProtocol.TCP)
                          .hostname("ssh.example.test")
                          .build()))
          .isInstanceOf(RstreamException.class)
          .hasMessageContaining("do not accept");
      assertThat(engine.openTunnelRequests).isEmpty();
    }
  }

  @Test
  void crossRegionRoutingPolicyIsSentForHTTP() throws Exception {
    try (var engine = FakeEngine.start(temp);
        var client = client(engine);
        var control = client.connect()) {
      control.createTunnel(
          CreateTunnelOptions.builder()
              .protocol(TunnelProtocol.HTTP)
              .allowCrossRegionRouting(true)
              .build());
      var request = engine.openTunnelRequests.poll(2, TimeUnit.SECONDS);
      assertThat(request).isNotNull();
      assertThat(request.getTunnelProperties().getProtocol().getValue()).isEqualTo("http");
      assertThat(request.getTunnelProperties().getAllowCrossRegionRouting().getValue()).isTrue();
    }
  }

  @Test
  void privateTunnelPublicExposureOptionsAreRejectedBeforeRequest() throws Exception {
    try (var engine = FakeEngine.start(temp);
        var client = client(engine);
        var control = client.connect()) {
      assertThatThrownBy(
              () ->
                  control.createTunnel(
                      CreateTunnelOptions.builder()
                          .name("private-api")
                          .publish(false)
                          .hostname("private-api-project.t.example.test")
                          .build()))
          .isInstanceOf(RstreamException.class)
          .hasMessageContaining("Private tunnels do not accept public exposure options");
      assertThat(engine.openTunnelRequests).isEmpty();
    }
  }

  private static RstreamClient client(FakeEngine engine) {
    return client(engine, true);
  }

  private static RstreamClient heartbeatClient(FakeEngine engine, Duration operationTimeout) {
    return RstreamClient.fromEnv(
        ClientOptions.builder()
            .engine(engine.address())
            .readConfigFile(false)
            .noToken(true)
            .heartbeat(true)
            .heartbeatInterval(Duration.ofSeconds(1))
            .connectTimeout(Duration.ofSeconds(5))
            .operationTimeout(operationTimeout)
            .tls(TlsOptions.builder().insecureSkipVerify(true).build())
            .build());
  }

  private static String failureCode(ControlChannel control, long timeout, TimeUnit timeUnit)
      throws Exception {
    try {
      control.done().get(timeout, timeUnit);
      throw new AssertionError("control channel completed without an error");
    } catch (ExecutionException error) {
      assertThat(error.getCause()).isInstanceOf(RstreamException.class);
      return ((RstreamException) error.getCause()).code();
    }
  }

  private static Socket accept(ServerSocket server) {
    try {
      return server.accept();
    } catch (IOException error) {
      throw new RstreamException("Test blackhole accept failed.", "ERR_TEST_ENGINE", error);
    }
  }

  private static RstreamClient timeoutClient(FakeEngine engine) {
    return timeoutClient(engine, true);
  }

  private static RstreamClient timeoutClient(FakeEngine engine, boolean zeroRtt) {
    return RstreamClient.fromEnv(
        ClientOptions.builder()
            .engine(engine.address())
            .readConfigFile(false)
            .noToken(true)
            .heartbeat(false)
            .operationTimeout(Duration.ofMillis(75))
            .zeroRtt(zeroRtt)
            .tls(TlsOptions.builder().insecureSkipVerify(true).build())
            .build());
  }

  private static RstreamClient client(FakeEngine engine, boolean zeroRtt) {
    return client(engine, zeroRtt, null);
  }

  private static RstreamClient client(FakeEngine engine, boolean zeroRtt, String token) {
    var builder =
        ClientOptions.builder()
            .engine(engine.address())
            .readConfigFile(false)
            .heartbeat(false)
            .zeroRtt(zeroRtt)
            .tls(TlsOptions.builder().insecureSkipVerify(true).build());
    if (token == null) builder.noToken(true);
    else builder.token(token);
    return RstreamClient.fromEnv(builder.build());
  }

  private static String dialEcho(
      RstreamClient client, String target, boolean zeroRtt, String payload) {
    try (var stream = client.dial(target, DialOptions.builder().zeroRtt(zeroRtt).build())) {
      var bytes = payload.getBytes(StandardCharsets.UTF_8);
      stream.outputStream().write(bytes);
      stream.outputStream().flush();
      return new String(stream.inputStream().readNBytes(bytes.length), StandardCharsets.UTF_8);
    } catch (IOException error) {
      throw new RstreamException("Test dial failed.", "ERR_TEST_DIAL", error);
    }
  }

  private static String roundTrip(Socket socket, String payload) throws IOException {
    var bytes = payload.getBytes(StandardCharsets.UTF_8);
    socket.getOutputStream().write(bytes);
    socket.getOutputStream().flush();
    return new String(socket.getInputStream().readNBytes(bytes.length), StandardCharsets.UTF_8);
  }

  private static <T> List<T> drain(BlockingQueue<T> queue, int count) throws InterruptedException {
    var values = new ArrayList<T>();
    for (var index = 0; index < count; index++) {
      var value = queue.poll(2, TimeUnit.SECONDS);
      assertThat(value).describedAs("queue item " + index).isNotNull();
      values.add(value);
    }
    return values;
  }

  private static final class FakeEngine implements Closeable {
    private final SSLServerSocket listener;
    private final Path certificatePath;
    private final java.util.concurrent.ExecutorService executor =
        java.util.concurrent.Executors.newCachedThreadPool();
    private final Object controlWriteLock = new Object();
    private volatile SSLSocket controlSocket;
    private volatile boolean nextControlError;
    private volatile boolean nextControlHang;
    private volatile boolean nextOpenTunnelError;
    private volatile boolean nextOpenTunnelHang;
    private volatile boolean nextOpenTunnelEmptyResponse;
    private volatile boolean nextCloseTunnelHang;
    private volatile boolean nextCloseControlHang;
    private volatile boolean nextStreamError;
    private volatile boolean nextStreamHang;
    private volatile boolean nextStreamEmptyResponse;
    private volatile boolean nextProxyHang;
    private volatile Rstream.ControlChannelLiveness liveness;
    private volatile boolean acknowledgeHeartbeats;
    private volatile long heartbeatAcknowledgementDelayMillis;
    private volatile int heartbeatAcknowledgementEvery = 1;
    private volatile long heartbeatAcknowledgementOffset;
    private volatile boolean duplicateHeartbeatAcknowledgement;
    private volatile CountDownLatch controlResponseGate;
    private int tunnelCounter;
    private int heartbeatCount;
    private final BlockingQueue<Rstream.OpenControlChannelReq> openControlRequests =
        new LinkedBlockingQueue<>();
    private final BlockingQueue<Rstream.Heartbeat> heartbeats = new LinkedBlockingQueue<>();
    private final BlockingQueue<Rstream.OpenTunnelReq> openTunnelRequests =
        new LinkedBlockingQueue<>();
    private final BlockingQueue<Rstream.CloseTunnelReq> closeTunnelRequests =
        new LinkedBlockingQueue<>();
    private final BlockingQueue<Rstream.StreamReq> streamRequests = new LinkedBlockingQueue<>();
    private final BlockingQueue<Rstream.ProxyReq> proxyRequests = new LinkedBlockingQueue<>();
    private final BlockingQueue<CompletableFuture<Void>> proxyClosures =
        new LinkedBlockingQueue<>();
    private final BlockingQueue<Rstream.ProxyConnRsp> proxyConnectionResponses =
        new LinkedBlockingQueue<>();

    private FakeEngine(SSLServerSocket listener, Path certificatePath) {
      this.listener = listener;
      this.certificatePath = certificatePath;
      executor.submit(this::acceptLoop);
    }

    static FakeEngine start(Path temp) throws Exception {
      var context = serverContext(temp);
      var listener =
          (SSLServerSocket)
              context
                  .getServerSocketFactory()
                  .createServerSocket(0, 50, InetAddress.getLoopbackAddress());
      return new FakeEngine(listener, temp.resolve("server.crt"));
    }

    String address() {
      return "127.0.0.1:" + listener.getLocalPort();
    }

    Path certificatePath() {
      return certificatePath;
    }

    void configureLiveness(
        int intervalMillis,
        int timeoutMillis,
        boolean acknowledge,
        long acknowledgementDelayMillis,
        int acknowledgementEvery,
        long acknowledgementOffset) {
      liveness =
          Rstream.ControlChannelLiveness.newBuilder()
              .setHeartbeatIntervalMs(intervalMillis)
              .setHeartbeatTimeoutMs(timeoutMillis)
              .build();
      acknowledgeHeartbeats = acknowledge;
      heartbeatAcknowledgementDelayMillis = acknowledgementDelayMillis;
      heartbeatAcknowledgementEvery = acknowledgementEvery;
      heartbeatAcknowledgementOffset = acknowledgementOffset;
    }

    void pauseControlResponse() {
      controlResponseGate = new CountDownLatch(1);
    }

    void resumeControlResponse() {
      controlResponseGate.countDown();
    }

    void sendProxyConnection(String tunnelId, String streamId, String secret) {
      sendProxyConnection(tunnelId, streamId, secret, null);
    }

    void sendProxyConnection(
        String tunnelId, String streamId, String secret, String proxyEndpoint) {
      var request = Rstream.ProxyConnReq.newBuilder().setTunnelId(tunnelId).setStreamId(streamId);
      if (secret != null) request.setSecret(StringValue.newBuilder().setValue(secret));
      if (proxyEndpoint != null)
        request.setProxyEndpoint(StringValue.newBuilder().setValue(proxyEndpoint));
      writeControl(Rstream.Message.newBuilder().setProxyConnReq(request.build()).build());
    }

    void closeControlSocket() throws IOException {
      controlSocket.close();
    }

    void sendMalformedControlFrame() {
      synchronized (controlWriteLock) {
        try {
          controlSocket.getOutputStream().write(new byte[] {0, 0, 0, 1, (byte) 0xff});
          controlSocket.getOutputStream().flush();
        } catch (IOException error) {
          throw new RstreamException("Fake engine control write failed.", "ERR_TEST_ENGINE", error);
        }
      }
    }

    @Override
    public void close() throws IOException {
      listener.close();
      if (controlSocket != null) controlSocket.close();
      executor.shutdownNow();
    }

    private void acceptLoop() {
      while (!listener.isClosed()) {
        try {
          var socket = (SSLSocket) listener.accept();
          executor.submit(() -> handle(socket));
        } catch (IOException error) {
          if (!listener.isClosed())
            throw new RstreamException("Fake engine accept failed.", "ERR_TEST_ENGINE", error);
        }
      }
    }

    private void handle(SSLSocket socket) {
      try {
        var message = Protocol.readMessage(socket.getInputStream());
        if (message.hasOpenControlChannelReq()) {
          openControlRequests.offer(message.getOpenControlChannelReq());
          handleControl(socket);
          return;
        }
        if (message.hasStreamReq()) {
          handleStream(socket, message.getStreamReq());
          return;
        }
        if (message.hasProxyReq()) handleProxy(socket, message.getProxyReq());
      } catch (IOException error) {
        closeQuietly(socket);
      }
    }

    private void handleControl(SSLSocket socket) throws IOException {
      controlSocket = socket;
      if (nextControlError) {
        nextControlError = false;
        writeControl(
            Rstream.Message.newBuilder()
                .setOpenControlChannelRsp(
                    Rstream.OpenControlChannelRsp.newBuilder().setError(error("control failed")))
                .build());
        return;
      }
      if (nextControlHang) {
        nextControlHang = false;
        return;
      }
      var responseGate = controlResponseGate;
      if (responseGate != null) {
        try {
          responseGate.await();
        } catch (InterruptedException error) {
          Thread.currentThread().interrupt();
          return;
        }
      }
      var ok =
          Rstream.OpenControlChannelRsp.Ok.newBuilder()
              .setClientId("cli_1")
              .setServerDetails(
                  Rstream.ServerDetails.newBuilder()
                      .setAgent(StringValue.newBuilder().setValue("fake-engine")));
      if (liveness != null) ok.setLiveness(liveness);
      writeControl(
          Rstream.Message.newBuilder()
              .setOpenControlChannelRsp(Rstream.OpenControlChannelRsp.newBuilder().setOk(ok))
              .build());
      while (!socket.isClosed()) {
        var message = Protocol.readMessage(socket.getInputStream());
        if (message.hasOpenTunnelReq()) handleOpenTunnel(message.getOpenTunnelReq());
        if (message.hasCloseTunnelReq()) handleCloseTunnel(message.getCloseTunnelReq());
        if (message.hasProxyConnRsp()) proxyConnectionResponses.offer(message.getProxyConnRsp());
        if (message.hasHeartbeat()) handleHeartbeat(message.getHeartbeat());
        if (message.hasCloseControlChannelReq()) {
          if (nextCloseControlHang) {
            nextCloseControlHang = false;
            return;
          }
          writeControl(
              Rstream.Message.newBuilder()
                  .setCloseControlChannelRsp(Rstream.CloseControlChannelRsp.newBuilder())
                  .build());
          return;
        }
      }
    }

    private void handleHeartbeat(Rstream.Heartbeat heartbeat) {
      heartbeats.offer(heartbeat);
      heartbeatCount++;
      if (!acknowledgeHeartbeats || heartbeatCount % heartbeatAcknowledgementEvery != 0) return;
      if (heartbeatCount == 1 && heartbeatAcknowledgementDelayMillis > 0) {
        try {
          Thread.sleep(heartbeatAcknowledgementDelayMillis);
        } catch (InterruptedException error) {
          Thread.currentThread().interrupt();
          return;
        }
      }
      writeControl(
          Rstream.Message.newBuilder()
              .setHeartbeat(
                  Rstream.Heartbeat.newBuilder()
                      .setAcknowledgement(heartbeat.getSequence() + heartbeatAcknowledgementOffset))
              .build());
      if (duplicateHeartbeatAcknowledgement) {
        writeControl(
            Rstream.Message.newBuilder()
                .setHeartbeat(
                    Rstream.Heartbeat.newBuilder()
                        .setAcknowledgement(
                            heartbeat.getSequence() + heartbeatAcknowledgementOffset))
                .build());
      }
    }

    private void handleOpenTunnel(Rstream.OpenTunnelReq request) {
      openTunnelRequests.offer(request);
      var builder = Rstream.OpenTunnelRsp.newBuilder().setRequestId(request.getRequestId());
      if (nextOpenTunnelError) {
        nextOpenTunnelError = false;
        builder.setError(error("open failed"));
      } else if (nextOpenTunnelHang) {
        nextOpenTunnelHang = false;
        return;
      } else if (nextOpenTunnelEmptyResponse) {
        nextOpenTunnelEmptyResponse = false;
      } else {
        tunnelCounter++;
        builder.setTunnelProperties(
            Rstream.TunnelProperties.newBuilder()
                .setId(StringValue.newBuilder().setValue("tun_" + tunnelCounter))
                .setName(
                    StringValue.newBuilder()
                        .setValue(request.getTunnelProperties().getName().getValue()))
                .setType(StringValue.newBuilder().setValue("bytestream"))
                .setProtocol(StringValue.newBuilder().setValue("http"))
                .setHostname(StringValue.newBuilder().setValue("web.example.test"))
                .setPort(com.google.protobuf.UInt32Value.newBuilder().setValue(443)));
      }
      writeControl(Rstream.Message.newBuilder().setOpenTunnelRsp(builder).build());
    }

    private void handleCloseTunnel(Rstream.CloseTunnelReq request) {
      closeTunnelRequests.offer(request);
      if (nextCloseTunnelHang) {
        nextCloseTunnelHang = false;
        return;
      }
      writeControl(
          Rstream.Message.newBuilder()
              .setCloseTunnelRsp(
                  Rstream.CloseTunnelRsp.newBuilder().setTunnelId(request.getTunnelId()))
              .build());
    }

    private void handleStream(SSLSocket socket, Rstream.StreamReq request) throws IOException {
      streamRequests.offer(request);
      if (nextStreamHang) {
        nextStreamHang = false;
        return;
      }
      if (nextStreamError) {
        nextStreamError = false;
        Protocol.writeMessage(
            socket.getOutputStream(),
            Rstream.Message.newBuilder()
                .setStreamRsp(Rstream.StreamRsp.newBuilder().setError(error("stream failed")))
                .build());
        socket.close();
        return;
      }
      if (nextStreamEmptyResponse) {
        nextStreamEmptyResponse = false;
        Protocol.writeMessage(
            socket.getOutputStream(),
            Rstream.Message.newBuilder().setStreamRsp(Rstream.StreamRsp.newBuilder()).build());
        socket.close();
        return;
      }
      if (!request.getZeroRtt().getValue()) {
        Protocol.writeMessage(
            socket.getOutputStream(),
            Rstream.Message.newBuilder()
                .setStreamRsp(Rstream.StreamRsp.newBuilder().setStreamId("stream_direct"))
                .build());
      }
      echo(socket);
    }

    private void handleProxy(SSLSocket socket, Rstream.ProxyReq request) throws IOException {
      proxyRequests.offer(request);
      if (nextProxyHang) {
        nextProxyHang = false;
        return;
      }
      if (!request.getZeroRtt().getValue()) {
        Protocol.writeMessage(
            socket.getOutputStream(),
            Rstream.Message.newBuilder().setProxyRsp(Rstream.ProxyRsp.newBuilder()).build());
      }
      var closed = new CompletableFuture<Void>();
      proxyClosures.offer(closed);
      try {
        echo(socket);
      } finally {
        closed.complete(null);
      }
    }

    private void writeControl(Rstream.Message message) {
      synchronized (controlWriteLock) {
        try {
          Protocol.writeMessage(controlSocket.getOutputStream(), message);
        } catch (IOException error) {
          throw new RstreamException("Fake engine control write failed.", "ERR_TEST_ENGINE", error);
        }
      }
    }

    private static Rstream.Error error(String message) {
      return Rstream.Error.newBuilder()
          .setCode(Rstream.ErrorCode.ERROR_CODE_INVALID_STREAM)
          .setMessage(StringValue.newBuilder().setValue(message))
          .build();
    }

    private static void echo(SSLSocket socket) throws IOException {
      var buffer = new byte[8192];
      var input = socket.getInputStream();
      var output = socket.getOutputStream();
      int read;
      while ((read = input.read(buffer)) >= 0) {
        output.write(buffer, 0, read);
        output.flush();
      }
    }

    private static SSLContext serverContext(Path temp) throws Exception {
      var keyStorePath = temp.resolve("server.p12");
      var keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
      if (!java.nio.file.Files.exists(keyStorePath)) {
        var process =
            new ProcessBuilder(
                    keytool,
                    "-genkeypair",
                    "-alias",
                    "server",
                    "-keyalg",
                    "RSA",
                    "-storetype",
                    "PKCS12",
                    "-keystore",
                    keyStorePath.toString(),
                    "-storepass",
                    "changeit",
                    "-keypass",
                    "changeit",
                    "-dname",
                    "CN=localhost",
                    "-validity",
                    "2",
                    "-ext",
                    "SAN=dns:localhost")
                .redirectErrorStream(true)
                .start();
        if (process.waitFor() != 0) {
          throw new IllegalStateException(
              new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        }
        var export =
            new ProcessBuilder(
                    keytool,
                    "-exportcert",
                    "-rfc",
                    "-alias",
                    "server",
                    "-keystore",
                    keyStorePath.toString(),
                    "-storepass",
                    "changeit",
                    "-file",
                    temp.resolve("server.crt").toString())
                .redirectErrorStream(true)
                .start();
        if (export.waitFor() != 0) {
          throw new IllegalStateException(
              new String(export.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        }
      }
      var store = KeyStore.getInstance("PKCS12");
      try (var input = java.nio.file.Files.newInputStream(keyStorePath)) {
        store.load(input, "changeit".toCharArray());
      }
      var keyFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      keyFactory.init(store, "changeit".toCharArray());
      var context = SSLContext.getInstance("TLS");
      context.init(keyFactory.getKeyManagers(), null, null);
      return context;
    }

    private static void closeQuietly(SSLSocket socket) {
      try {
        socket.close();
      } catch (IOException ignored) {
      }
    }
  }
}
