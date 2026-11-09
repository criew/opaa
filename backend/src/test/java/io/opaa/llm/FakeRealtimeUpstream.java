package io.opaa.llm;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * A local WebSocket server standing in for a live transcription upstream: answers the handshake,
 * hands every text message to a script and lets the script answer, close or drop the connection.
 * Text frames only, enough for the dialects of OPAA's adapters (RFC 6455, no extensions).
 */
final class FakeRealtimeUpstream implements AutoCloseable {

  private static final String ACCEPT_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

  private final ServerSocket server;
  private final List<Connection> connections = new CopyOnWriteArrayList<>();
  private volatile int refuseWithStatus;
  private volatile Consumer<Connection> onOpen = connection -> {};
  private volatile BiConsumer<Connection, String> onMessage = (connection, message) -> {};

  FakeRealtimeUpstream() throws IOException {
    server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
    Thread.ofVirtual().start(this::accept);
  }

  /** The address of the upstream with {@code path}, e.g. {@code /v1/realtime}. */
  String url(String path) {
    return "ws://127.0.0.1:" + server.getLocalPort() + path;
  }

  /** Answers every further handshake with this HTTP status instead of switching protocols. */
  void refuseWith(int status) {
    refuseWithStatus = status;
  }

  void onOpen(Consumer<Connection> script) {
    onOpen = script;
  }

  void onMessage(BiConsumer<Connection, String> script) {
    onMessage = script;
  }

  /** The connection opened {@code index}th, waiting for it a few seconds. */
  Connection connection(int index) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (connections.size() <= index && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    if (connections.size() <= index) {
      throw new AssertionError("no connection " + index + " reached the fake upstream");
    }
    return connections.get(index);
  }

  int connectionCount() {
    return connections.size();
  }

  @Override
  public void close() throws IOException {
    server.close();
    for (Connection connection : connections) {
      connection.drop();
    }
  }

  private void accept() {
    while (!server.isClosed()) {
      try {
        Socket socket = server.accept();
        Thread.ofVirtual().start(() -> serve(socket));
      } catch (IOException e) {
        return;
      }
    }
  }

  private void serve(Socket socket) {
    try {
      DataInputStream in = new DataInputStream(socket.getInputStream());
      OutputStream out = socket.getOutputStream();
      String requestLine = line(in);
      Map<String, String> headers = new LinkedHashMap<>();
      for (String header = line(in); !header.isEmpty(); header = line(in)) {
        int colon = header.indexOf(':');
        headers.put(
            header.substring(0, colon).strip().toLowerCase(Locale.ROOT),
            header.substring(colon + 1).strip());
      }
      int status = refuseWithStatus;
      if (status != 0) {
        out.write(
            ("HTTP/1.1 " + status + " Refused\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        out.flush();
        socket.close();
        return;
      }
      String accept = acceptKey(headers.get("sec-websocket-key"));
      out.write(
          ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                  + "Sec-WebSocket-Accept: "
                  + accept
                  + "\r\n\r\n")
              .getBytes(StandardCharsets.US_ASCII));
      out.flush();
      Connection connection =
          new Connection(socket, out, requestLine.split(" ")[1], Map.copyOf(headers));
      connections.add(connection);
      onOpen.accept(connection);
      connection.read(in, onMessage);
    } catch (IOException e) {
      // The client went away or the test dropped the connection.
    }
  }

  private static String line(InputStream in) throws IOException {
    ByteArrayOutputStream line = new ByteArrayOutputStream();
    for (int b = in.read(); b != -1; b = in.read()) {
      if (b == '\n') {
        break;
      }
      if (b != '\r') {
        line.write(b);
      }
    }
    return line.toString(StandardCharsets.US_ASCII);
  }

  private static String acceptKey(String key) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-1")
              .digest((key + ACCEPT_GUID).getBytes(StandardCharsets.US_ASCII));
      return Base64.getEncoder().encodeToString(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** One client connection: what it asked for and sent, and how the script answers it. */
  static final class Connection {

    private final Socket socket;
    private final OutputStream out;
    private final String target;
    private final Map<String, String> headers;
    private final BlockingQueue<String> received = new LinkedBlockingQueue<>();
    private final List<String> all = new CopyOnWriteArrayList<>();
    private volatile boolean closedByClient;

    Connection(Socket socket, OutputStream out, String target, Map<String, String> headers) {
      this.socket = socket;
      this.out = out;
      this.target = target;
      this.headers = headers;
    }

    /** The request target of the handshake: path and query. */
    String target() {
      return target;
    }

    String header(String name) {
      return headers.get(name.toLowerCase(Locale.ROOT));
    }

    /** The next text message of the client, waiting a few seconds for it. */
    String next() throws InterruptedException {
      String message = received.poll(5, TimeUnit.SECONDS);
      if (message == null) {
        throw new AssertionError("the client sent no further message; so far: " + all);
      }
      return message;
    }

    /** Every text message the client sent so far. */
    List<String> messages() {
      return new ArrayList<>(all);
    }

    boolean closedByClient() {
      return closedByClient;
    }

    synchronized void send(String text) {
      byte[] payload = text.getBytes(StandardCharsets.UTF_8);
      try {
        out.write(0x81);
        if (payload.length < 126) {
          out.write(payload.length);
        } else if (payload.length < 65_536) {
          out.write(126);
          out.write(payload.length >>> 8);
          out.write(payload.length & 0xFF);
        } else {
          out.write(127);
          for (int shift = 56; shift >= 0; shift -= 8) {
            out.write((int) ((long) payload.length >>> shift) & 0xFF);
          }
        }
        out.write(payload);
        out.flush();
      } catch (IOException e) {
        throw new IllegalStateException("the fake upstream could not send", e);
      }
    }

    /** Closes the TCP connection without a closing handshake, as a crashed upstream would. */
    void drop() {
      try {
        socket.close();
      } catch (IOException e) {
        // already closed
      }
    }

    private void read(DataInputStream in, BiConsumer<Connection, String> script)
        throws IOException {
      ByteArrayOutputStream message = new ByteArrayOutputStream();
      while (true) {
        int first = in.readUnsignedByte();
        int second = in.readUnsignedByte();
        boolean fin = (first & 0x80) != 0;
        int opcode = first & 0x0F;
        long length = second & 0x7F;
        if (length == 126) {
          length = in.readUnsignedShort();
        } else if (length == 127) {
          length = in.readLong();
        }
        byte[] mask = new byte[4];
        if ((second & 0x80) != 0) {
          in.readFully(mask);
        }
        byte[] payload = new byte[(int) length];
        in.readFully(payload);
        for (int i = 0; i < payload.length; i++) {
          payload[i] ^= mask[i % 4];
        }
        if (opcode == 0x8) {
          closedByClient = true;
          synchronized (this) {
            out.write(new byte[] {(byte) 0x88, 0});
            out.flush();
          }
          socket.close();
          return;
        }
        if (opcode == 0x9) {
          synchronized (this) {
            out.write(0x8A);
            out.write(payload.length);
            out.write(payload);
            out.flush();
          }
          continue;
        }
        if (opcode == 0x1 || opcode == 0x0) {
          message.write(payload);
          if (fin) {
            String text = message.toString(StandardCharsets.UTF_8);
            message.reset();
            all.add(text);
            received.add(text);
            script.accept(this, text);
          }
        }
      }
    }
  }
}
