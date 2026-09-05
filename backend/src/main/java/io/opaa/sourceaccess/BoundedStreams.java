package io.opaa.sourceaccess;

import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * The one byte ceiling every bounded read or write goes through: enforced while the bytes flow,
 * never after the whole body has been buffered, so a remote end or an archive entry past the limit
 * is cut off before the excess reaches heap or disk. Crossing the limit throws {@link
 * LimitExceededException}; a stream exactly at the limit passes.
 */
public final class BoundedStreams {

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

  private static void checkLimit(long soFar, long maxBytes) throws IOException {
    if (soFar > maxBytes) {
      throw new LimitExceededException(maxBytes);
    }
  }
}
