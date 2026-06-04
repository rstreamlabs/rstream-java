package io.rstream;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

/** Bidirectional byte stream carried by the rstream engine. */
public final class RstreamStream implements Closeable {
  private final Socket socket;

  RstreamStream(Socket socket) {
    this.socket = socket;
  }

  public InputStream inputStream() throws IOException {
    return socket.getInputStream();
  }

  public OutputStream outputStream() throws IOException {
    return socket.getOutputStream();
  }

  public Socket socket() {
    return socket;
  }

  @Override
  public void close() throws IOException {
    socket.close();
  }

  void closeQuietly() {
    try {
      close();
    } catch (IOException ignored) {
    }
  }
}
