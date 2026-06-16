package io.rstream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.StringValue;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
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
    return RstreamClient.fromEnv(
        ClientOptions.builder()
            .engine(engine.address())
            .readConfigFile(false)
            .noToken(true)
            .heartbeat(false)
            .zeroRtt(zeroRtt)
            .tls(TlsOptions.builder().insecureSkipVerify(true).build())
            .build());
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
    private int tunnelCounter;
    private final BlockingQueue<Rstream.OpenTunnelReq> openTunnelRequests =
        new LinkedBlockingQueue<>();
    private final BlockingQueue<Rstream.CloseTunnelReq> closeTunnelRequests =
        new LinkedBlockingQueue<>();
    private final BlockingQueue<Rstream.StreamReq> streamRequests = new LinkedBlockingQueue<>();
    private final BlockingQueue<Rstream.ProxyReq> proxyRequests = new LinkedBlockingQueue<>();
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

    void sendProxyConnection(String tunnelId, String streamId, String secret) {
      var request =
          Rstream.ProxyConnReq.newBuilder()
              .setTunnelId(tunnelId)
              .setStreamId(streamId)
              .setSecret(StringValue.newBuilder().setValue(secret))
              .build();
      writeControl(Rstream.Message.newBuilder().setProxyConnReq(request).build());
    }

    void closeControlSocket() throws IOException {
      controlSocket.close();
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
      writeControl(
          Rstream.Message.newBuilder()
              .setOpenControlChannelRsp(
                  Rstream.OpenControlChannelRsp.newBuilder()
                      .setOk(
                          Rstream.OpenControlChannelRsp.Ok.newBuilder()
                              .setClientId("cli_1")
                              .setServerDetails(
                                  Rstream.ServerDetails.newBuilder()
                                      .setAgent(StringValue.newBuilder().setValue("fake-engine")))))
              .build());
      while (!socket.isClosed()) {
        var message = Protocol.readMessage(socket.getInputStream());
        if (message.hasOpenTunnelReq()) handleOpenTunnel(message.getOpenTunnelReq());
        if (message.hasCloseTunnelReq()) handleCloseTunnel(message.getCloseTunnelReq());
        if (message.hasProxyConnRsp()) proxyConnectionResponses.offer(message.getProxyConnRsp());
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
      echo(socket);
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
