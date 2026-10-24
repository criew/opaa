package io.opaa.audit;

import io.opaa.compliance.DataFlow;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Delivers syslog messages over one TCP connection, by default with TLS (RFC 5425), each framed by
 * its octet count (RFC 6587). After the last message it closes its sending side and waits briefly
 * for the receiver to close as well; a receiver that went away meanwhile fails the delivery there.
 * There is no acknowledgement per message (that would take RELP): a delivery counts as done once
 * the bytes were accepted. The timeout bounds the whole delivery: when it runs out, the socket is
 * closed by a watchdog of its own (ADR-0053, je Instanz) and a blocked write fails. A plain socket:
 * the connection never goes through the outbound proxy.
 */
@DataFlow("siem-export")
class SyslogSender {

  /** How long a delivery waits for the receiver to close after the last message. */
  static final int CLOSE_WAIT_MILLIS = 2_000;

  private static final ScheduledExecutorService WATCHDOG =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "siem-export-watchdog");
            thread.setDaemon(true);
            return thread;
          });

  private final String host;
  private final int port;
  private final boolean tls;
  private final int timeoutMillis;

  SyslogSender(String host, int port, boolean tls, Duration timeout) {
    this.host = host;
    this.port = port;
    this.tls = tls;
    this.timeoutMillis = (int) Math.min(Integer.MAX_VALUE, timeout.toMillis());
  }

  /**
   * Sends every message or throws: an exception means the receiver may have got none, some or all
   * of them, so the caller sends them again.
   */
  void send(List<String> messages) throws IOException {
    try (Socket plain = new Socket()) {
      ScheduledFuture<?> watchdog =
          WATCHDOG.schedule(() -> closeQuietly(plain), timeoutMillis, TimeUnit.MILLISECONDS);
      try {
        deliver(plain, messages);
      } finally {
        watchdog.cancel(false);
      }
    }
  }

  private void deliver(Socket plain, List<String> messages) throws IOException {
    plain.connect(new InetSocketAddress(host, port), timeoutMillis);
    plain.setSoTimeout(timeoutMillis);
    Socket socket = tls ? secured(plain) : plain;
    try {
      OutputStream out = new BufferedOutputStream(socket.getOutputStream());
      for (String message : messages) {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        out.write((bytes.length + " ").getBytes(StandardCharsets.US_ASCII));
        out.write(bytes);
      }
      out.flush();
      awaitClose(socket);
    } finally {
      if (socket != plain) {
        socket.close();
      }
    }
  }

  /**
   * Closes the sending side and reads until the receiver closes too, for at most {@link
   * #CLOSE_WAIT_MILLIS}. A reset there means the receiver was gone; a receiver that keeps the
   * connection open past the wait is taken as having received the data. TLS before 1.3 cannot close
   * one side only and skips the wait.
   */
  private static void awaitClose(Socket socket) throws IOException {
    if (socket instanceof SSLSocket secured
        && !"TLSv1.3".equals(secured.getSession().getProtocol())) {
      return;
    }
    socket.shutdownOutput();
    socket.setSoTimeout(CLOSE_WAIT_MILLIS);
    try {
      InputStream in = socket.getInputStream();
      byte[] discard = new byte[512];
      while (in.read(discard) >= 0) {
        // a receiver has nothing to say on this channel; whatever arrives is dropped
      }
    } catch (SocketTimeoutException e) {
      // still open after the wait: the bytes were accepted
    }
  }

  private SSLSocket secured(Socket plain) throws IOException {
    SSLSocket socket =
        (SSLSocket)
            ((SSLSocketFactory) SSLSocketFactory.getDefault())
                .createSocket(plain, host, port, true);
    SSLParameters parameters = socket.getSSLParameters();
    parameters.setEndpointIdentificationAlgorithm("HTTPS");
    socket.setSSLParameters(parameters);
    socket.startHandshake();
    return socket;
  }

  private static void closeQuietly(Socket socket) {
    try {
      socket.close();
    } catch (IOException ignored) {
      // the delivery that owned the socket reports its own failure
    }
  }
}
