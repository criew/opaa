package io.opaa.indexing.source.nextcloud;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The change feature of a Nextcloud file: {@code n:} and a hash over ETag and size, so it fits
 * {@code documents.last_modified_remote} (64 characters) whatever the ETag's length. A rename keeps
 * the ETag; the file sync notices the new place itself.
 */
final class NextcloudChangeMarker {

  private NextcloudChangeMarker() {}

  static String of(String etag, long size) {
    try {
      byte[] hash =
          MessageDigest.getInstance("SHA-256")
              .digest((etag + "\n" + size).getBytes(StandardCharsets.UTF_8));
      return "n:" + HexFormat.of().formatHex(hash, 0, 20);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is part of every JRE", e);
    }
  }
}
