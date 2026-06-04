package io.rstream;

import java.io.IOException;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import rstream.io_rstrm.protobuf.Rstream;

/** Main rstream Java SDK client. */
public final class RstreamClient implements AutoCloseable {
  private final ClientOptions options;
  private final RstreamTransport transport;
  private final ExecutorService executor;
  private final ScheduledExecutorService scheduler;
  private final Set<ControlChannel> controls = ConcurrentHashMap.newKeySet();
  private volatile ResolvedClientOptions resolved;
  private volatile boolean closed;

  public RstreamClient(ClientOptions options) {
    this.options = options == null ? ClientOptions.defaults() : options;
    this.transport = new RstreamTransport();
    this.executor = Executors.newCachedThreadPool(threadFactory("rstream-java-worker"));
    this.scheduler =
        Executors.newSingleThreadScheduledExecutor(threadFactory("rstream-java-scheduler"));
  }

  public static RstreamClient fromEnv() {
    return new RstreamClient(ClientOptions.defaults());
  }

  public static RstreamClient fromEnv(ClientOptions options) {
    return new RstreamClient(options);
  }

  public ControlChannel connect() {
    ensureOpen();
    var resolvedOptions = resolved();
    var engine = resolveEngine(resolvedOptions);
    var token = resolveToken(resolvedOptions);
    Socket socket = null;
    try {
      socket = transport.dial(engine, resolvedOptions.tls(), resolvedOptions.connectTimeout());
      setReadTimeout(socket, resolvedOptions.operationTimeout());
      Protocol.writeMessage(socket.getOutputStream(), Protocol.openControlChannelRequest(token));
      var response = Protocol.readMessage(socket.getInputStream());
      if (!response.hasOpenControlChannelRsp()) {
        throw new ProtocolException(
            "Engine did not return OpenControlChannelRsp.", "ERR_RSTREAM_PROTOCOL");
      }
      var payload = response.getOpenControlChannelRsp();
      if (payload.getPayloadCase() == Rstream.OpenControlChannelRsp.PayloadCase.ERROR) {
        throw Protocol.engineErrorFromPb(payload.getError());
      }
      if (payload.getPayloadCase() != Rstream.OpenControlChannelRsp.PayloadCase.OK) {
        throw new ProtocolException(
            "Engine returned an empty OpenControlChannelRsp.", "ERR_RSTREAM_PROTOCOL");
      }
      setReadTimeout(socket, Duration.ZERO);
      var control =
          new ControlChannel(
              socket,
              resolvedOptions,
              Protocol.serverDetailsFromPb(payload.getOk().getServerDetails()),
              executor,
              scheduler,
              request -> openProxyConnection(engine, resolvedOptions, request));
      controls.add(control);
      control.done().whenComplete((ignored, error) -> controls.remove(control));
      return control;
    } catch (SocketTimeoutException error) {
      closeQuietly(socket);
      throw operationTimeout("Timed out waiting for the engine control channel response.", error);
    } catch (IOException error) {
      closeQuietly(socket);
      throw new RstreamException(
          "Failed to connect to the rstream engine.", "ERR_RSTREAM_CONNECT", error);
    } catch (RuntimeException error) {
      closeQuietly(socket);
      throw error;
    }
  }

  public CompletableFuture<ControlChannel> connectAsync() {
    if (closed) return CompletableFuture.failedFuture(closedError());
    return CompletableFuture.supplyAsync(this::connect, executor);
  }

  public RstreamStream dial(String tunnel) {
    return dial(tunnel, DialOptions.defaults());
  }

  public RstreamStream dial(String tunnel, DialOptions options) {
    ensureOpen();
    var target = tunnel == null ? "" : tunnel.trim();
    if (target.isEmpty()) {
      throw new RstreamException("Tunnel ID or name is required.", "ERR_RSTREAM_INVALID_TUNNEL");
    }
    var resolvedOptions = resolved();
    var engine = resolveEngine(resolvedOptions);
    var token = options.token() != null ? options.token() : resolveToken(resolvedOptions);
    var zeroRtt = options.zeroRtt() == null ? resolvedOptions.zeroRtt() : options.zeroRtt();
    var socket =
        openStreamSocket(engine, resolvedOptions, Protocol.streamRequest(target, token, zeroRtt));
    if (!zeroRtt) {
      try {
        var response = Protocol.readMessage(socket.getInputStream());
        if (!response.hasStreamRsp()) {
          throw new ProtocolException("Engine did not return StreamRsp.", "ERR_RSTREAM_PROTOCOL");
        }
        var streamResponse = response.getStreamRsp();
        if (streamResponse.getPayloadCase() == Rstream.StreamRsp.PayloadCase.ERROR) {
          throw Protocol.engineErrorFromPb(streamResponse.getError());
        }
        if (streamResponse.getPayloadCase() != Rstream.StreamRsp.PayloadCase.STREAM_ID) {
          throw new ProtocolException(
              "Engine returned an empty StreamRsp.", "ERR_RSTREAM_PROTOCOL");
        }
        setReadTimeout(socket, Duration.ZERO);
      } catch (SocketTimeoutException error) {
        closeQuietly(socket);
        throw operationTimeout("Timed out waiting for the private stream response.", error);
      } catch (IOException | RuntimeException error) {
        closeQuietly(socket);
        throw runtime("Failed to dial private bytestream tunnel.", "ERR_RSTREAM_DIAL", error);
      }
    }
    return new RstreamStream(socket);
  }

  public CompletableFuture<RstreamStream> dialAsync(String tunnel) {
    return dialAsync(tunnel, DialOptions.defaults());
  }

  public CompletableFuture<RstreamStream> dialAsync(String tunnel, DialOptions options) {
    if (closed) return CompletableFuture.failedFuture(closedError());
    return CompletableFuture.supplyAsync(() -> dial(tunnel, options), executor);
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
    RuntimeException failure = null;
    try {
      for (var control : controls) {
        try {
          control.close();
        } catch (RuntimeException error) {
          if (failure == null) failure = error;
        }
      }
    } finally {
      controls.clear();
      scheduler.shutdownNow();
      executor.shutdownNow();
    }
    if (failure != null) throw failure;
  }

  private void ensureOpen() {
    if (closed) {
      throw closedError();
    }
  }

  private static RstreamException closedError() {
    return new RstreamException("rstream client is closed.", "ERR_RSTREAM_CLIENT_CLOSED");
  }

  private RstreamStream openProxyConnection(
      String engine, ResolvedClientOptions resolvedOptions, Rstream.ProxyConnReq request) {
    var token = request.hasSecret() ? request.getSecret().getValue() : null;
    var socket =
        openStreamSocket(
            engine,
            resolvedOptions,
            Protocol.proxyRequest(request.getStreamId(), token, resolvedOptions.zeroRtt()));
    if (!resolvedOptions.zeroRtt()) {
      try {
        var response = Protocol.readMessage(socket.getInputStream());
        if (!response.hasProxyRsp()) {
          throw new ProtocolException("Engine did not return ProxyRsp.", "ERR_RSTREAM_PROTOCOL");
        }
        if (response.getProxyRsp().hasError())
          throw Protocol.engineErrorFromPb(response.getProxyRsp().getError());
        setReadTimeout(socket, Duration.ZERO);
      } catch (SocketTimeoutException error) {
        closeQuietly(socket);
        throw operationTimeout("Timed out waiting for the proxy stream response.", error);
      } catch (IOException | RuntimeException error) {
        closeQuietly(socket);
        throw runtime("Failed to open rstream proxy connection.", "ERR_RSTREAM_PROXY", error);
      }
    }
    return new RstreamStream(socket);
  }

  private Socket openStreamSocket(
      String engine, ResolvedClientOptions resolvedOptions, Rstream.Message request) {
    Socket socket = null;
    try {
      socket = transport.dial(engine, resolvedOptions.tls(), resolvedOptions.connectTimeout());
      setReadTimeout(socket, resolvedOptions.operationTimeout());
      Protocol.writeMessage(socket.getOutputStream(), request);
      return socket;
    } catch (IOException error) {
      closeQuietly(socket);
      throw new RstreamException(
          "Failed to connect to the rstream engine.", "ERR_RSTREAM_CONNECT", error);
    } catch (RuntimeException error) {
      closeQuietly(socket);
      throw error;
    }
  }

  private ResolvedClientOptions resolved() {
    var current = resolved;
    if (current != null) return current;
    synchronized (this) {
      if (resolved == null) resolved = ConfigResolver.resolve(options);
      return resolved;
    }
  }

  private String resolveEngine(ResolvedClientOptions resolvedOptions) {
    if (resolvedOptions.engine() != null) return resolvedOptions.engine();
    if (resolvedOptions.projectEndpoint() == null) {
      throw new RstreamException(
          "Engine is required but not configured.", "ERR_RSTREAM_ENGINE_REQUIRED");
    }
    return new RstreamApiClient(resolvedOptions.apiUrl(), resolvedOptions.token())
        .resolveEngine(resolvedOptions.projectEndpoint());
  }

  private String resolveToken(ResolvedClientOptions resolvedOptions) {
    return resolvedOptions.noToken() ? null : resolvedOptions.token();
  }

  private static ThreadFactory threadFactory(String prefix) {
    return runnable -> {
      var thread = new Thread(runnable, prefix + "-" + System.nanoTime());
      thread.setDaemon(true);
      return thread;
    };
  }

  private static void closeQuietly(Socket socket) {
    if (socket == null) return;
    try {
      socket.close();
    } catch (IOException ignored) {
    }
  }

  private static RuntimeException runtime(String message, String code, Throwable error) {
    if (error instanceof RuntimeException runtime) return runtime;
    return new RstreamException(message, code, error);
  }

  private static void setReadTimeout(Socket socket, Duration timeout) throws SocketException {
    var millis = timeout == null || timeout.isZero() ? 0 : timeout.toMillis();
    if (millis > Integer.MAX_VALUE) millis = Integer.MAX_VALUE;
    socket.setSoTimeout((int) millis);
  }

  static RstreamException operationTimeout(String message, Throwable cause) {
    return new RstreamException(message, "ERR_RSTREAM_OPERATION_TIMEOUT", cause);
  }

  static <T> T await(CompletableFuture<T> future) {
    try {
      return future.get();
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new RstreamException(
          "Interrupted while waiting for rstream operation.", "ERR_RSTREAM_INTERRUPTED", error);
    } catch (ExecutionException error) {
      var cause = Objects.requireNonNullElse(error.getCause(), error);
      if (cause instanceof RuntimeException runtime) throw runtime;
      throw new RstreamException("rstream operation failed.", "ERR_RSTREAM_OPERATION", cause);
    }
  }
}
