package io.opaa.indexing;

import io.opaa.indexing.source.IndexingSourceType;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public class ChecksumService {

  public String computeSha256(Path file) throws IOException {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (InputStream is = Files.newInputStream(file);
          DigestInputStream dis = new DigestInputStream(is, digest)) {
        dis.readAllBytes();
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 algorithm not available", e);
    }
  }

  /**
   * Computes the SHA-256 checksum of already in-memory content - used for {@link
   * IndexingSourceType#RSS_FEED} runs, whose text comes from an extracted detail page rather than a
   * file on disk.
   */
  public String computeSha256(byte[] content) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(content));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 algorithm not available", e);
    }
  }
}
