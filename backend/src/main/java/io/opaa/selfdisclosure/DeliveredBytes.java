package io.opaa.selfdisclosure;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Counts and hashes the bytes the response accepted: a byte counts only once the stream below took
 * it without an exception, so size and SHA-256 describe what was delivered, also after an abort.
 * Closing it closes nothing below - the servlet container owns the response stream. A write or
 * flush the stream below refused marks the response as failed: the client went away.
 */
final class DeliveredBytes extends FilterOutputStream {

  private final MessageDigest digest;
  private long count;
  private boolean failed;

  DeliveredBytes(OutputStream out) {
    super(out);
    try {
      this.digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is part of every JRE", e);
    }
  }

  @Override
  public void write(int b) throws IOException {
    try {
      out.write(b);
    } catch (IOException e) {
      failed = true;
      throw e;
    }
    digest.update((byte) b);
    count++;
  }

  @Override
  public void write(byte[] b, int off, int len) throws IOException {
    try {
      out.write(b, off, len);
    } catch (IOException e) {
      failed = true;
      throw e;
    }
    digest.update(b, off, len);
    count += len;
  }

  @Override
  public void flush() throws IOException {
    try {
      out.flush();
    } catch (IOException e) {
      failed = true;
      throw e;
    }
  }

  @Override
  public void close() throws IOException {
    flush();
  }

  long count() {
    return count;
  }

  boolean failed() {
    return failed;
  }

  /** The hex SHA-256 over every byte delivered so far; the stream may continue afterwards. */
  String sha256() {
    try {
      return HexFormat.of().formatHex(((MessageDigest) digest.clone()).digest());
    } catch (CloneNotSupportedException e) {
      throw new IllegalStateException("SHA-256 digests of the JRE are cloneable", e);
    }
  }
}
