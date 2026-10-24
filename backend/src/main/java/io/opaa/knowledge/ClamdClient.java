package io.opaa.knowledge;

import io.opaa.compliance.DataFlow;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Streams one file to a clamd daemon with its {@code INSTREAM} command and returns the reply line,
 * e.g. {@code stream: OK} or {@code stream: Eicar-Signature FOUND}. A plain TCP socket: the
 * connection never goes through the outbound proxy.
 */
@DataFlow("malware-scanner")
class ClamdClient {

  private static final byte[] COMMAND = "zINSTREAM\0".getBytes(StandardCharsets.US_ASCII);
  private static final int CHUNK_BYTES = 64 * 1024;
  private static final int MAX_REPLY_BYTES = 4096;

  private final String host;
  private final int port;
  private final int timeoutMillis;

  ClamdClient(String host, int port, Duration timeout) {
    this.host = host;
    this.port = port;
    this.timeoutMillis = (int) Math.min(Integer.MAX_VALUE, timeout.toMillis());
  }

  /**
   * The daemon's reply to {@code file}, without its terminating NUL. The timeout bounds the whole
   * scan: when it runs out, the socket is closed and a blocked write or read fails. A daemon that
   * closes the stream early (its {@code StreamMaxLength}) usually answers first; that answer is
   * returned rather than the write error.
   *
   * @throws IOException when the daemon cannot be reached, closes early or does not answer in time
   */
  String scan(Path file) throws IOException {
    try (Socket socket = new Socket()) {
      CompletableFuture.delayedExecutor(timeoutMillis, TimeUnit.MILLISECONDS)
          .execute(() -> closeQuietly(socket));
      socket.connect(new InetSocketAddress(host, port), timeoutMillis);
      socket.setSoTimeout(timeoutMillis);
      try {
        stream(file, socket.getOutputStream());
      } catch (IOException writeFailure) {
        try {
          return readReply(socket.getInputStream());
        } catch (IOException noReply) {
          writeFailure.addSuppressed(noReply);
          throw writeFailure;
        }
      }
      return readReply(socket.getInputStream());
    }
  }

  private static void stream(Path file, OutputStream socketOut) throws IOException {
    OutputStream out = new BufferedOutputStream(socketOut, CHUNK_BYTES + 4);
    out.write(COMMAND);
    byte[] buffer = new byte[CHUNK_BYTES];
    try (InputStream in = Files.newInputStream(file)) {
      int read;
      while ((read = in.read(buffer)) > 0) {
        writeLength(out, read);
        out.write(buffer, 0, read);
      }
    }
    writeLength(out, 0);
    out.flush();
  }

  private static void closeQuietly(Socket socket) {
    try {
      socket.close();
    } catch (IOException ignored) {
      // the scan that owned the socket reports its own failure
    }
  }

  private static void writeLength(OutputStream out, int length) throws IOException {
    out.write(length >>> 24);
    out.write(length >>> 16);
    out.write(length >>> 8);
    out.write(length);
  }

  private static String readReply(InputStream in) throws IOException {
    ByteArrayOutputStream reply = new ByteArrayOutputStream();
    int next;
    while ((next = in.read()) > 0) {
      if (reply.size() >= MAX_REPLY_BYTES) {
        throw new IOException("clamd reply exceeds " + MAX_REPLY_BYTES + " bytes");
      }
      reply.write(next);
    }
    if (reply.size() == 0) {
      throw new IOException("clamd closed the connection without a reply");
    }
    return reply.toString(StandardCharsets.US_ASCII).strip();
  }
}
