package io.opaa.test;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A plain HTTP forward proxy on {@code 127.0.0.1} for tests: requests in absolute form ({@code GET
 * http://host/path}) and tunnels ({@code CONNECT host:port}). It resolves names only through its
 * own table ({@link #route}), so a test target such as {@code upstream.test} exists only behind the
 * proxy - a client that bypassed it could not reach it. Every request line it received is recorded.
 * {@link #refuseTunnels} answers every {@code CONNECT} with the given status instead.
 */
public final class RecordingHttpProxy implements Closeable {

  private final ServerSocket server;
  private final ExecutorService workers = Executors.newCachedThreadPool();
  private final Map<String, InetSocketAddress> routes = new ConcurrentHashMap<>();
  private final List<String> requests = new CopyOnWriteArrayList<>();
  private final List<Socket> open = new CopyOnWriteArrayList<>();
  private volatile int tunnelRefusal;

  public RecordingHttpProxy() throws IOException {
    server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
    workers.execute(this::acceptLoop);
  }

  /** The port the proxy listens on, on {@code 127.0.0.1}. */
  public int port() {
    return server.getLocalPort();
  }

  /** {@code 127.0.0.1:port}, the form the outbound proxy setting takes. */
  public String address() {
    return "127.0.0.1:" + port();
  }

  /** Lets the proxy reach {@code host} (any port) at {@code target}'s address and port. */
  public RecordingHttpProxy route(String host, InetSocketAddress target) {
    routes.put(host.toLowerCase(Locale.ROOT), target);
    return this;
  }

  /** Answers every following {@code CONNECT} with {@code status}; {@code 0} tunnels again. */
  public void refuseTunnels(int status) {
    tunnelRefusal = status;
  }

  /** Every request line received, in order, e.g. {@code GET http://upstream.test:8080/a}. */
  public List<String> requests() {
    return List.copyOf(requests);
  }

  @Override
  public void close() throws IOException {
    server.close();
    for (Socket socket : open) {
      closeQuietly(socket);
    }
    workers.shutdownNow();
  }

  private void acceptLoop() {
    while (!server.isClosed()) {
      try {
        Socket client = server.accept();
        open.add(client);
        workers.execute(() -> handle(client));
      } catch (IOException closed) {
        return;
      }
    }
  }

  private void handle(Socket client) {
    try (client) {
      InputStream in = client.getInputStream();
      OutputStream out = client.getOutputStream();
      List<String> head = readHead(in);
      if (head.isEmpty()) {
        return;
      }
      String requestLine = head.get(0);
      requests.add(requestLine.substring(0, requestLine.lastIndexOf(' ')));
      String[] parts = requestLine.split(" ");
      if (parts[0].equalsIgnoreCase("CONNECT")) {
        tunnel(parts[1], in, out);
      } else {
        forward(parts[0], URI.create(parts[1]), parts[2], head.subList(1, head.size()), in, out);
      }
    } catch (IOException | RuntimeException ignored) {
      // the client or the target went away; the test sees it on its own side
    }
  }

  private void tunnel(String authority, InputStream in, OutputStream out) throws IOException {
    if (tunnelRefusal != 0) {
      respond(out, tunnelRefusal + " Refused");
      return;
    }
    int colon = authority.lastIndexOf(':');
    InetSocketAddress target = resolve(authority.substring(0, colon));
    if (target == null) {
      respond(out, "502 Bad Gateway");
      return;
    }
    try (Socket upstream = new Socket(target.getAddress(), target.getPort())) {
      open.add(upstream);
      out.write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
      out.flush();
      pump(in, out, upstream);
    }
  }

  private void forward(
      String method,
      URI target,
      String version,
      List<String> headers,
      InputStream in,
      OutputStream out)
      throws IOException {
    InetSocketAddress address = resolve(target.getHost());
    if (address == null) {
      respond(out, "502 Bad Gateway");
      return;
    }
    try (Socket upstream = new Socket(address.getAddress(), address.getPort())) {
      open.add(upstream);
      StringBuilder request = new StringBuilder();
      String path = target.getRawPath() == null || target.getRawPath().isEmpty() ? "/" : null;
      request
          .append(method)
          .append(' ')
          .append(path != null ? path : target.getRawPath())
          .append(target.getRawQuery() == null ? "" : "?" + target.getRawQuery())
          .append(' ')
          .append(version)
          .append("\r\n");
      for (String header : headers) {
        String name =
            header.substring(0, Math.max(header.indexOf(':'), 0)).toLowerCase(Locale.ROOT);
        if (!name.equals("connection") && !name.startsWith("proxy-")) {
          request.append(header).append("\r\n");
        }
      }
      request.append("Connection: close\r\n\r\n");
      OutputStream upstreamOut = upstream.getOutputStream();
      upstreamOut.write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
      upstreamOut.flush();
      pump(in, out, upstream);
    }
  }

  /** Copies both directions until the target closes; then the client connection ends too. */
  private void pump(InputStream clientIn, OutputStream clientOut, Socket upstream)
      throws IOException {
    InputStream upstreamIn = upstream.getInputStream();
    OutputStream upstreamOut = upstream.getOutputStream();
    workers.execute(
        () -> {
          try {
            clientIn.transferTo(upstreamOut);
          } catch (IOException ignored) {
            // the tunnel closed
          }
        });
    upstreamIn.transferTo(clientOut);
    clientOut.flush();
  }

  private InetSocketAddress resolve(String host) {
    String key = host.toLowerCase(Locale.ROOT);
    if (key.startsWith("[") && key.endsWith("]")) {
      key = key.substring(1, key.length() - 1);
    }
    InetSocketAddress exact = routes.get(key);
    if (exact != null) {
      return exact;
    }
    for (Map.Entry<String, InetSocketAddress> route : routes.entrySet()) {
      if (key.endsWith("." + route.getKey())) {
        return route.getValue();
      }
    }
    return null;
  }

  private static void respond(OutputStream out, String status) throws IOException {
    out.write(
        ("HTTP/1.1 " + status + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
            .getBytes(StandardCharsets.US_ASCII));
    out.flush();
  }

  /** The request line and header lines, without the blank line ending them. */
  private static List<String> readHead(InputStream in) throws IOException {
    List<String> lines = new ArrayList<>();
    ByteArrayOutputStream line = new ByteArrayOutputStream();
    int previous = -1;
    int current;
    while ((current = in.read()) != -1) {
      if (current == '\n' && previous == '\r') {
        byte[] bytes = line.toByteArray();
        String text = new String(bytes, 0, bytes.length - 1, StandardCharsets.ISO_8859_1);
        if (text.isEmpty()) {
          return lines;
        }
        lines.add(text);
        line.reset();
      } else {
        line.write(current);
      }
      previous = current;
    }
    return List.of();
  }

  private static void closeQuietly(Socket socket) {
    try {
      socket.close();
    } catch (IOException ignored) {
      // closing anyway
    }
  }
}
