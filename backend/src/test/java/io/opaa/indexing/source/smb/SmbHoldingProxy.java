package io.opaa.indexing.source.smb;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A TCP relay in front of Samba that swallows the first large server reply (a file read; small
 * replies such as the answers to a link walk pass), so the reader waits for it. The SMB frame
 * length is visible even when the payload is signed and encrypted. This makes "a download is
 * waiting for its data" a state the test reaches and observes, not one it hopes to hit in time.
 */
final class SmbHoldingProxy implements AutoCloseable {

  private static final int LARGE_FRAME_BYTES = 256 * 1024;

  private final ServerSocket listener;
  private final int targetPort;
  private final AtomicBoolean held = new AtomicBoolean();
  private final CountDownLatch holding = new CountDownLatch(1);

  SmbHoldingProxy(int targetPort) throws IOException {
    this.targetPort = targetPort;
    this.listener = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
    daemon(this::acceptLoop);
  }

  int port() {
    return listener.getLocalPort();
  }

  /** Blocks until a large reply is held back; {@code false} when that does not happen in time. */
  boolean awaitHolding(long seconds) throws InterruptedException {
    return holding.await(seconds, TimeUnit.SECONDS);
  }

  private void acceptLoop() {
    while (!listener.isClosed()) {
      try {
        Socket client = listener.accept();
        Socket server = new Socket(InetAddress.getLoopbackAddress(), targetPort);
        daemon(() -> relay(client, server, false));
        daemon(() -> relay(server, client, true));
      } catch (IOException e) {
        return;
      }
    }
  }

  private void relay(Socket from, Socket to, boolean serverReplies) {
    try {
      DataInputStream in = new DataInputStream(from.getInputStream());
      OutputStream out = to.getOutputStream();
      while (true) {
        byte[] header = new byte[4];
        in.readFully(header);
        int length = ((header[1] & 0xFF) << 16) | ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
        byte[] body = new byte[length];
        in.readFully(body);
        if (serverReplies && length > LARGE_FRAME_BYTES && held.compareAndSet(false, true)) {
          holding.countDown();
          continue;
        }
        out.write(header);
        out.write(body);
        out.flush();
      }
    } catch (IOException e) {
      // the connection ended
    } finally {
      closeQuietly(from);
      closeQuietly(to);
    }
  }

  private static void daemon(Runnable task) {
    Thread thread = new Thread(task, "smb-holding-proxy");
    thread.setDaemon(true);
    thread.start();
  }

  private static void closeQuietly(Socket socket) {
    try {
      socket.close();
    } catch (IOException e) {
      // already closed
    }
  }

  @Override
  public void close() throws IOException {
    listener.close();
  }
}
