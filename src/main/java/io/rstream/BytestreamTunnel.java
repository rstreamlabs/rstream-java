package io.rstream;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/** A bytestream tunnel opened on the rstream engine. */
public final class BytestreamTunnel implements AutoCloseable {
  private final ControlChannel control;
  private final TunnelProperties properties;
  private final StreamQueue streams = new StreamQueue();
  private final ExecutorService executor;
  private volatile boolean closed;

  BytestreamTunnel(ControlChannel control, TunnelProperties properties, ExecutorService executor) {
    if (properties.id() == null || properties.id().isBlank()) {
      throw new ProtocolException("Engine did not return a tunnel ID.", "ERR_RSTREAM_PROTOCOL");
    }
    this.control = control;
    this.properties = properties;
    this.executor = executor;
  }

  public String id() {
    return properties.id();
  }

  public boolean closed() {
    return closed;
  }

  public TunnelProperties properties() {
    return properties;
  }

  public String forwardingAddress() {
    return formatForwardingAddress(properties);
  }

  public RstreamStream accept() throws InterruptedException {
    return streams.take(null);
  }

  public RstreamStream accept(Duration timeout) throws InterruptedException {
    Objects.requireNonNull(timeout, "timeout");
    if (timeout.isNegative()) throw new IllegalArgumentException("timeout must not be negative");
    return streams.take(timeout);
  }

  public CompletableFuture<RstreamStream> acceptAsync() {
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            return accept();
          } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new RstreamException(
                "Interrupted while accepting tunnel stream.", "ERR_RSTREAM_INTERRUPTED", error);
          }
        },
        executor);
  }

  public CompletableFuture<Void> forwardTo(String host, int port) {
    Objects.requireNonNull(host, "host");
    if (host.isBlank()) throw new IllegalArgumentException("host must not be blank");
    if (port < 1 || port > 65_535) {
      throw new IllegalArgumentException("port must be between 1 and 65535");
    }
    return CompletableFuture.runAsync(
        () -> {
          while (!closed) {
            try {
              var stream = accept();
              executor.submit(() -> pipeToLocal(stream, host, port));
            } catch (InterruptedException error) {
              Thread.currentThread().interrupt();
              return;
            } catch (RstreamException error) {
              if (!closed) throw error;
              return;
            }
          }
        },
        executor);
  }

  public CompletableFuture<Void> serveHttp(RstreamHttpHandler handler) {
    return serveHttp(handler, RstreamHttpOptions.defaults());
  }

  public CompletableFuture<Void> serveHttp(RstreamHttpHandler handler, RstreamHttpOptions options) {
    return RstreamHttpServer.serve(this, handler, options, executor);
  }

  boolean deliver(RstreamStream stream) {
    return streams.offer(stream);
  }

  void onClose(Throwable error) {
    if (closed) return;
    closed = true;
    var closeError =
        error instanceof RuntimeException runtimeError
            ? runtimeError
            : new RstreamException("Tunnel closed.", "ERR_RSTREAM_TUNNEL_CLOSED", error);
    streams.close(closeError);
  }

  @Override
  public void close() {
    if (closed) return;
    control.closeTunnel(id());
  }

  public CompletableFuture<Void> closeAsync() {
    return CompletableFuture.runAsync(this::close, executor);
  }

  static String formatForwardingAddress(TunnelProperties properties) {
    var published = publishedHost(properties);
    if (published != null && properties.protocol() == TunnelProtocol.HTTP)
      return "https://" + published;
    if (published != null && properties.protocol() == TunnelProtocol.TLS)
      return published + " (tls)";
    if (published != null && properties.protocol() == TunnelProtocol.TCP)
      return published + " (tcp)";
    if (published != null && properties.protocol() == TunnelProtocol.DTLS)
      return published + " (dtls)";
    if (published != null && properties.protocol() == TunnelProtocol.QUIC)
      return published + " (quic)";
    if (published != null) return published;
    if (properties.name() != null) return "rstrm://" + properties.name() + " (unpublished)";
    if (properties.id() != null) return "rstrm://" + properties.id() + " (unpublished)";
    throw new RstreamException(
        "Invalid tunnel properties: no host, name, or ID.", "ERR_RSTREAM_INVALID_TUNNEL");
  }

  private static String publishedHost(TunnelProperties properties) {
    if (properties.hostname() != null && !properties.hostname().isBlank()) {
      var port = properties.port() == null ? 443 : properties.port();
      if (properties.protocol() == TunnelProtocol.TLS
          || properties.protocol() == TunnelProtocol.TCP
          || port != 443) return properties.hostname() + ":" + port;
      return properties.hostname();
    }
    if (properties.host() != null && !properties.host().isBlank()) return properties.host();
    return null;
  }

  private static void pipeToLocal(RstreamStream stream, String host, int port) {
    try (stream;
        var local = new Socket(host, port)) {
      var streamInput = stream.inputStream();
      var streamOutput = stream.outputStream();
      var localInput = local.getInputStream();
      var localOutput = local.getOutputStream();
      var upstream =
          new Thread(
              () -> {
                copy(streamInput, localOutput);
                shutdownOutput(local);
              },
              "rstream-forward-upstream");
      var downstream =
          new Thread(
              () -> {
                copy(localInput, streamOutput);
                shutdownOutput(stream.socket());
              },
              "rstream-forward-downstream");
      upstream.setDaemon(true);
      downstream.setDaemon(true);
      upstream.start();
      downstream.start();
      upstream.join();
      downstream.join();
    } catch (IOException error) {
      throw new RstreamException("Failed to forward tunnel stream.", "ERR_RSTREAM_FORWARD", error);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new RstreamException(
          "Interrupted while forwarding tunnel stream.", "ERR_RSTREAM_INTERRUPTED", error);
    }
  }

  private static void copy(InputStream input, OutputStream output) {
    try {
      input.transferTo(output);
      output.flush();
    } catch (IOException ignored) {
    }
  }

  private static void shutdownOutput(Socket socket) {
    try {
      if (!socket.isClosed() && !socket.isOutputShutdown()) socket.shutdownOutput();
    } catch (IOException ignored) {
    }
  }

  private static final class StreamQueue {
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition available = lock.newCondition();
    private final ArrayDeque<RstreamStream> queued = new ArrayDeque<>();
    private RuntimeException closeError;

    RstreamStream take(Duration timeout) throws InterruptedException {
      var remaining = timeout == null ? 0 : timeoutNanos(timeout);
      lock.lockInterruptibly();
      try {
        while (queued.isEmpty()) {
          if (closeError != null) throw closeError;
          if (timeout == null) available.await();
          else if (remaining <= 0) return null;
          else remaining = available.awaitNanos(remaining);
        }
        return queued.removeFirst();
      } finally {
        lock.unlock();
      }
    }

    boolean offer(RstreamStream stream) {
      var accepted = false;
      lock.lock();
      try {
        if (closeError == null) {
          queued.addLast(stream);
          available.signal();
          accepted = true;
        }
      } finally {
        lock.unlock();
      }
      if (!accepted) stream.closeQuietly();
      return accepted;
    }

    void close(RuntimeException error) {
      ArrayList<RstreamStream> abandoned;
      lock.lock();
      try {
        if (closeError != null) return;
        closeError = error;
        abandoned = new ArrayList<>(queued);
        queued.clear();
        available.signalAll();
      } finally {
        lock.unlock();
      }
      abandoned.forEach(RstreamStream::closeQuietly);
    }

    private static long timeoutNanos(Duration timeout) {
      try {
        return timeout.toNanos();
      } catch (ArithmeticException ignored) {
        return Long.MAX_VALUE;
      }
    }
  }
}
