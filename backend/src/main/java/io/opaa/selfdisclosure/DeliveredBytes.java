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
 * Closing it closes nothing below - the servlet container owns the response stream.
 */
final class DeliveredBytes extends FilterOutputStream {

  private final MessageDigest digest;
  private long count;

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
    out.write(b);
    digest.update((byte) b);
    count++;
  }

  @Override
  public void write(byte[] b, int off, int len) throws IOException {
    out.write(b, off, len);
    digest.update(b, off, len);
    count += len;
  }

  @Override
  public void close() throws IOException {
    flush();
  }

  long count() {
    return count;
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
