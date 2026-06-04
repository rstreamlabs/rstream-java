package io.rstream;

import java.io.IOException;
import java.net.Socket;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import rstream.io_rstrm.protobuf.Rstream;

/** Open control channel used to create and manage tunnels. */
public final class ControlChannel implements AutoCloseable {
  private final Socket socket;
  private final ResolvedClientOptions options;
  private final ServerDetails serverDetails;
  private final ExecutorService executor;
  private final ScheduledExecutorService scheduler;
  private final Function<Rstream.ProxyConnReq, RstreamStream> openProxyConnection;
  private final Map<String, CompletableFuture<BytestreamTunnel>> pendingTunnels =
      new ConcurrentHashMap<>();
  private final Map<String, CompletableFuture<Void>> pendingCloses = new ConcurrentHashMap<>();
  private final Map<String, BytestreamTunnel> tunnels = new ConcurrentHashMap<>();
  private final Object writeLock = new Object();
  private final Object closeLock = new Object();
  private final CompletableFuture<Void> done = new CompletableFuture<>();
  private ScheduledFuture<?> heartbeat;
  private volatile boolean closing;
  private volatile boolean closed;

  ControlChannel(
      Socket socket,
      ResolvedClientOptions options,
      ServerDetails serverDetails,
      ExecutorService executor,
      ScheduledExecutorService scheduler,
      Function<Rstream.ProxyConnReq, RstreamStream> openProxyConnection) {
    this.socket = socket;
    this.options = options;
    this.serverDetails = serverDetails;
    this.executor = executor;
    this.scheduler = scheduler;
    this.openProxyConnection = openProxyConnection;
    start();
  }

  public ServerDetails serverDetails() {
    return serverDetails;
  }

  public boolean closed() {
    return closed;
  }

  public CompletableFuture<Void> done() {
    return done;
  }

  public BytestreamTunnel createTunnel() {
    return createTunnel(CreateTunnelOptions.defaults());
  }

  public BytestreamTunnel createTunnel(CreateTunnelOptions options) {
    if (options.type() != null && options.type() != TunnelType.BYTESTREAM) {
      throw new UnsupportedFeatureException(
          "Only bytestream tunnels are supported by rstream-java.",
          "ERR_RSTREAM_UNSUPPORTED_TUNNEL");
    }
    return createBytestreamTunnel(options);
  }

  public CompletableFuture<BytestreamTunnel> createTunnelAsync(CreateTunnelOptions options) {
    return CompletableFuture.supplyAsync(() -> createTunnel(options), executor);
  }

  public CompletableFuture<BytestreamTunnel> createTunnelAsync() {
    return createTunnelAsync(CreateTunnelOptions.defaults());
  }

  public BytestreamTunnel createBytestreamTunnel(CreateTunnelOptions options) {
    if (closed || closing) {
      throw new RstreamException("Control channel is closed.", "ERR_RSTREAM_CONTROL_CLOSED");
    }
    var properties = normalizeBytestreamOptions(options);
    var requestId = UUID.randomUUID().toString();
    var pending = new CompletableFuture<BytestreamTunnel>();
    pendingTunnels.put(requestId, pending);
    var timeout =
        operationTimeout(
            pending,
            () -> pendingTunnels.remove(requestId),
            "Timed out waiting for the engine tunnel creation response.");
    try {
      write(Protocol.openTunnelRequest(requestId, properties));
    } catch (RuntimeException error) {
      timeout.cancel(false);
      pendingTunnels.remove(requestId);
      throw error;
    }
    return RstreamClient.await(pending);
  }

  public void closeTunnel(String tunnelId) {
    var tunnel = tunnels.get(tunnelId);
    if (tunnel == null || tunnel.closed()) return;
    CompletableFuture<Void> pending;
    var owner = false;
    synchronized (tunnel) {
      if (tunnel.closed()) return;
      pending = pendingCloses.get(tunnelId);
      if (pending == null) {
        pending = new CompletableFuture<>();
        pendingCloses.put(tunnelId, pending);
        owner = true;
      }
    }
    if (owner) {
      var timeout =
          operationTimeout(
              pending,
              () -> pendingCloses.remove(tunnelId),
              "Timed out waiting for the engine tunnel close response.");
      try {
        write(Protocol.closeTunnelRequest(tunnelId));
      } catch (RuntimeException error) {
        timeout.cancel(false);
        pendingCloses.remove(tunnelId);
        pending.completeExceptionally(error);
        throw error;
      }
    }
    RstreamClient.await(pending);
  }

  public CompletableFuture<Void> closeTunnelAsync(String tunnelId) {
    return CompletableFuture.runAsync(() -> closeTunnel(tunnelId), executor);
  }

  @Override
  public void close() {
    if (closed) return;
    ScheduledFuture<?> closeTimeout = null;
    synchronized (closeLock) {
      if (!closing && !closed) {
        closing = true;
        closeTimeout =
            scheduler.schedule(
                () ->
                    finish(
                        RstreamClient.operationTimeout(
                            "Timed out waiting for the engine control-channel close response.",
                            null)),
                options.operationTimeout().toMillis(),
                TimeUnit.MILLISECONDS);
        try {
          write(Protocol.closeControlChannelRequest());
        } catch (RuntimeException error) {
          finish(error);
        }
      }
    }
    try {
      RstreamClient.await(done);
    } finally {
      if (closeTimeout != null) closeTimeout.cancel(false);
    }
  }

  public CompletableFuture<Void> closeAsync() {
    return CompletableFuture.runAsync(this::close, executor);
  }

  void finish(Throwable error) {
    if (closed) return;
    closed = true;
    if (heartbeat != null) heartbeat.cancel(true);
    try {
      socket.close();
    } catch (IOException ignored) {
    }
    var closeError =
        error == null
            ? new RstreamException("Control channel closed.", "ERR_RSTREAM_CONTROL_CLOSED")
            : error;
    pendingTunnels.values().forEach(pending -> pending.completeExceptionally(closeError));
    pendingCloses.values().forEach(pending -> pending.completeExceptionally(closeError));
    tunnels.values().forEach(tunnel -> tunnel.onClose(closeError));
    pendingTunnels.clear();
    pendingCloses.clear();
    tunnels.clear();
    if (error == null) done.complete(null);
    else done.completeExceptionally(error);
  }

  private void start() {
    executor.submit(this::readLoop);
    if (options.heartbeat() && options.heartbeatInterval().toMillis() > 0) {
      heartbeat =
          scheduler.scheduleAtFixedRate(
              () -> {
                try {
                  if (!closed) write(Protocol.heartbeat());
                } catch (RuntimeException error) {
                  finish(error);
                }
              },
              options.heartbeatInterval().toMillis(),
              options.heartbeatInterval().toMillis(),
              TimeUnit.MILLISECONDS);
    }
  }

  private void readLoop() {
    try {
      while (!closed) handleMessage(Protocol.readMessage(socket.getInputStream()));
    } catch (Throwable error) {
      if (!closed) finish(error);
    }
  }

  private void handleMessage(Rstream.Message message) {
    if (message.hasOpenTunnelRsp()) {
      handleOpenTunnelResponse(message.getOpenTunnelRsp());
      return;
    }
    if (message.hasCloseTunnelRsp()) {
      handleCloseTunnelResponse(message.getCloseTunnelRsp().getTunnelId());
      return;
    }
    if (message.hasProxyConnReq()) {
      handleProxyConnectionRequest(message.getProxyConnReq());
      return;
    }
    if (message.hasCloseControlChannelRsp()) finish(null);
  }

  private void handleOpenTunnelResponse(Rstream.OpenTunnelRsp response) {
    var pending = pendingTunnels.remove(response.getRequestId());
    if (pending == null) return;
    if (response.getPayloadCase() == Rstream.OpenTunnelRsp.PayloadCase.ERROR) {
      pending.completeExceptionally(Protocol.engineErrorFromPb(response.getError()));
      return;
    }
    if (response.getPayloadCase() != Rstream.OpenTunnelRsp.PayloadCase.TUNNEL_PROPERTIES) {
      pending.completeExceptionally(
          new ProtocolException("Engine returned an empty OpenTunnelRsp.", "ERR_RSTREAM_PROTOCOL"));
      return;
    }
    var properties = Protocol.tunnelPropertiesFromPb(response.getTunnelProperties());
    var tunnel = new BytestreamTunnel(this, properties, executor);
    tunnels.put(tunnel.id(), tunnel);
    pending.complete(tunnel);
  }

  private void handleCloseTunnelResponse(String tunnelId) {
    var tunnel = tunnels.remove(tunnelId);
    if (tunnel != null) tunnel.onClose(null);
    var pending = pendingCloses.remove(tunnelId);
    if (pending != null) pending.complete(null);
  }

  private void handleProxyConnectionRequest(Rstream.ProxyConnReq request) {
    var tunnel = tunnels.get(request.getTunnelId());
    if (tunnel == null) {
      write(
          Protocol.proxyConnectionResponse(
              request.getStreamId(), Protocol.errorToPb("Tunnel is not open on this client.")));
      return;
    }
    try {
      var stream = openProxyConnection.apply(request);
      if (!tunnel.deliver(stream)) {
        write(
            Protocol.proxyConnectionResponse(
                request.getStreamId(), Protocol.errorToPb("Tunnel is closed.")));
        return;
      }
      write(Protocol.proxyConnectionResponse(request.getStreamId(), null));
    } catch (RuntimeException error) {
      write(
          Protocol.proxyConnectionResponse(
              request.getStreamId(), Protocol.errorToPb(error.getMessage())));
    }
  }

  private void write(Rstream.Message message) {
    synchronized (writeLock) {
      try {
        Protocol.writeMessage(socket.getOutputStream(), message);
      } catch (IOException error) {
        throw new RstreamException("Failed to write protocol message.", "ERR_RSTREAM_WRITE", error);
      }
    }
  }

  private <T> ScheduledFuture<?> operationTimeout(
      CompletableFuture<T> pending, Runnable cleanup, String message) {
    var timeout =
        scheduler.schedule(
            () -> {
              cleanup.run();
              pending.completeExceptionally(RstreamClient.operationTimeout(message, null));
            },
            options.operationTimeout().toMillis(),
            TimeUnit.MILLISECONDS);
    pending.whenComplete((value, error) -> timeout.cancel(false));
    return timeout;
  }

  private static TunnelProperties normalizeBytestreamOptions(CreateTunnelOptions options) {
    if (options.httpVersion() == HttpVersion.H3) {
      throw new UnsupportedFeatureException(
          "HTTP/3 tunnels require datagram support, which rstream-java does not support.",
          "ERR_RSTREAM_UNSUPPORTED_TUNNEL");
    }
    if (Boolean.FALSE.equals(options.publish()) && hasPublicExposureOptions(options)) {
      throw new RstreamException(
          "Private tunnels do not accept public exposure options.",
          "ERR_RSTREAM_INVALID_TUNNEL_OPTIONS");
    }
    var auth = options.auth();
    var tokenAuth =
        options.tokenAuth() != null ? options.tokenAuth() : auth == null ? null : auth.token();
    var rstreamAuth =
        options.rstreamAuth() != null
            ? options.rstreamAuth()
            : auth == null ? null : auth.rstream();
    var challengeMode =
        options.challengeMode() != null
            ? options.challengeMode()
            : auth == null ? null : auth.challenge();
    return TunnelProperties.builder()
        .name(options.name())
        .type(TunnelType.BYTESTREAM)
        .publish(options.publish())
        .protocol(options.protocol())
        .labels(options.labels())
        .geoIp(options.geoIp())
        .trustedIps(options.trustedIps())
        .tlsMode(options.tlsMode())
        .tlsAlpns(options.tlsAlpns())
        .tlsMinVersion(options.tlsMinVersion())
        .tlsCiphers(options.tlsCiphers())
        .mtlsAuth(options.mtlsAuth())
        .httpVersion(options.httpVersion())
        .tokenAuth(tokenAuth)
        .rstreamAuth(rstreamAuth)
        .challengeMode(challengeMode)
        .hostname(options.hostname())
        .upstreamTls(options.upstreamTls())
        .build();
  }

  private static boolean hasPublicExposureOptions(CreateTunnelOptions options) {
    return options.protocol() != null
        || !options.geoIp().isEmpty()
        || !options.trustedIps().isEmpty()
        || options.tlsMode() != null
        || !options.tlsAlpns().isEmpty()
        || options.tlsMinVersion() != null
        || !options.tlsCiphers().isEmpty()
        || options.mtlsAuth() != null
        || options.httpVersion() != null
        || options.tokenAuth() != null
        || options.rstreamAuth() != null
        || options.challengeMode() != null
        || options.hostname() != null
        || options.upstreamTls() != null
        || options.auth() != null;
  }
}
