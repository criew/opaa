package io.opaa.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/**
 * The form in which a free text of a setting reaches the audit log: never the wording, only its
 * SHA-256 (hex, over the UTF-8 bytes) and its length in characters, under {@code <name>Sha256} and
 * {@code <name>Length}. Two entries show that a text changed and whether it is back to an earlier
 * one; whoever holds the text can check it against the hash.
 */
public final class AuditTextDigest {

  private AuditTextDigest() {}

  /** Puts hash and length of {@code text} into {@code payload}; nothing for {@code null}. */
  public static void put(Map<String, Object> payload, String name, String text) {
    if (text == null) {
      return;
    }
    payload.put(name + "Sha256", sha256(text));
    payload.put(name + "Length", text.length());
  }

  static String sha256(String text) {
    try {
      byte[] hash =
          MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException missing) {
      throw new IllegalStateException("SHA-256 is part of every Java platform", missing);
    }
  }
}
