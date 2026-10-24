package io.opaa.audit;

import io.opaa.compliance.DataFlow;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Delivers syslog messages over one TCP connection, by default with TLS (RFC 5425), each framed by
 * its octet count (RFC 6587). The timeout bounds the whole delivery: when it runs out, the socket
 * is closed and a blocked write fails. A plain socket: the connection never goes through the
 * outbound proxy.
 */
@DataFlow("siem-export")
class SyslogSender {

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
      CompletableFuture.delayedExecutor(timeoutMillis, TimeUnit.MILLISECONDS)
          .execute(() -> closeQuietly(plain));
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
      } finally {
        if (socket != plain) {
          socket.close();
        }
      }
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
