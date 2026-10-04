package io.opaa.sourceaccess;

import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The one byte ceiling every bounded read or write goes through: enforced while the bytes flow,
 * never after the whole body has been buffered, so a remote end or an archive entry past the limit
 * is cut off before the excess reaches heap or disk. Crossing the limit throws {@link
 * LimitExceededException}; a stream exactly at the limit passes.
 */
public final class BoundedStreams {

  /** Closes a body still being read when its deadline passes. */
  private static final ScheduledExecutorService DEADLINES =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "bounded-read-deadline");
            thread.setDaemon(true);
            return thread;
          });

  private BoundedStreams() {}

  /** Thrown the moment a read or write would carry the stream past {@link #maxBytes()}. */
  public static final class LimitExceededException extends IOException {
    private final long maxBytes;

    public LimitExceededException(long maxBytes) {
      super("Stream exceeds the configured size limit of " + maxBytes + " bytes");
      this.maxBytes = maxBytes;
    }

    public long maxBytes() {
      return maxBytes;
    }
  }

  /** Wraps {@code in} so a read past {@code maxBytes} throws instead of growing the heap. */
  public static InputStream input(InputStream in, long maxBytes) {
    return new FilterInputStream(in) {
      private long total;

      @Override
      public int read() throws IOException {
        int b = super.read();
        if (b != -1) {
          checkLimit(++total, maxBytes);
        }
        return b;
      }

      @Override
      public int read(byte[] b, int off, int len) throws IOException {
        int n = super.read(b, off, len);
        if (n > 0) {
          total += n;
          checkLimit(total, maxBytes);
        }
        return n;
      }

      @Override
      public boolean markSupported() {
        return false;
      }
    };
  }

  /**
   * Wraps {@code out} so a write past {@code maxBytes} throws before the excess is written - for a
   * producer that writes on its own and offers no {@link InputStream} to bound.
   */
  public static OutputStream output(OutputStream out, long maxBytes) {
    return new FilterOutputStream(out) {
      private long total;

      @Override
      public void write(int b) throws IOException {
        checkLimit(++total, maxBytes);
        out.write(b);
      }

      @Override
      public void write(byte[] b, int off, int len) throws IOException {
        total += len;
        checkLimit(total, maxBytes);
        out.write(b, off, len);
      }
    };
  }

  /**
   * Copies {@code in} to {@code out}, throwing the moment the copied volume would exceed {@code
   * maxBytes} - the excess is never written; the caller deletes the partial target.
   */
  public static void copy(InputStream in, OutputStream out, long maxBytes) throws IOException {
    input(in, maxBytes).transferTo(out);
  }

  /** Reads {@code in} to its end, throwing the moment a further byte would exceed the limit. */
  public static byte[] readFully(InputStream in, long maxBytes) throws IOException {
    byte[] probe = in.readNBytes(Math.toIntExact(Math.min(maxBytes + 1, Integer.MAX_VALUE)));
    if (probe.length > maxBytes) {
      throw new LimitExceededException(maxBytes);
    }
    return probe;
  }

  /**
   * {@link #readFully} that also ends at {@code deadlineNanos} (on the {@link System#nanoTime()}
   * scale): a body still being read then is closed and {@link HttpTimeoutException} thrown, so a
   * remote end trickling bytes cannot hold the reader past it. Crossing the limit still throws
   * {@link LimitExceededException}.
   */
  public static byte[] readFullyBefore(InputStream in, long maxBytes, long deadlineNanos)
      throws IOException {
    AtomicBoolean expired = new AtomicBoolean();
    ScheduledFuture<?> stop =
        DEADLINES.schedule(
            () -> {
              expired.set(true);
              closeQuietly(in);
            },
            Math.max(0, deadlineNanos - System.nanoTime()),
            TimeUnit.NANOSECONDS);
    try {
      byte[] read = readFully(in, maxBytes);
      if (expired.get()) {
        throw new HttpTimeoutException("the body was not read completely before its deadline");
      }
      return read;
    } catch (IOException e) {
      if (expired.get() && !(e instanceof LimitExceededException)) {
        throw new HttpTimeoutException("the body was not read completely before its deadline");
      }
      throw e;
    } finally {
      stop.cancel(false);
    }
  }

  private static void closeQuietly(InputStream in) {
    try {
      in.close();
    } catch (IOException e) {
      // the reading thread sees the closed stream
    }
  }

  private static void checkLimit(long soFar, long maxBytes) throws IOException {
    if (soFar > maxBytes) {
      throw new LimitExceededException(maxBytes);
    }
  }
}
