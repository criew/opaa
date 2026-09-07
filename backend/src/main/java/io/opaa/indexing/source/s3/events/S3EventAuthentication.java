package io.opaa.indexing.source.s3.events;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Locale;

/**
 * The three ways an S3 event notification proves knowledge of the library's token (ADR-0027,
 * Entscheidung 6), every comparison constant-time: {@code Authorization: Bearer <token>} (MinIO
 * builds exactly that from a one-part {@code auth_token}), {@code Authorization: Basic} with the
 * token as password under any user name (Ceph RGW can only put {@code user:password} into the
 * endpoint URI) and the header {@value #SHARED_SECRET_HEADER} carrying the token itself
 * (EventBridge API destination, any sender with a free header).
 */
public final class S3EventAuthentication {

  public static final String SHARED_SECRET_HEADER = "X-OPAA-Webhook-Secret";

  private S3EventAuthentication() {}

  /**
   * @param authorization the {@code Authorization} header, or {@code null}
   * @param sharedSecret the {@value #SHARED_SECRET_HEADER} header, or {@code null}
   * @param token the library's stored token; {@code null} authenticates nothing
   */
  public static boolean verify(String authorization, String sharedSecret, String token) {
    if (token == null || token.isBlank()) {
      // the same comparisons against a stand-in, so the answer time does not tell a caller
      // whether this library exists and carries a token; the result is discarded
      presented(authorization, sharedSecret, "no-token-configured-for-this-library");
      return false;
    }
    return presented(authorization, sharedSecret, token);
  }

  private static boolean presented(String authorization, String sharedSecret, String token) {
    byte[] expected = token.getBytes(StandardCharsets.UTF_8);
    boolean matched = false;
    if (sharedSecret != null) {
      matched |= MessageDigest.isEqual(sharedSecret.getBytes(StandardCharsets.UTF_8), expected);
    }
    if (authorization != null) {
      String scheme = authorization.split(" ", 2)[0].toLowerCase(Locale.ROOT);
      String value = authorization.contains(" ") ? authorization.split(" ", 2)[1].strip() : "";
      if (scheme.equals("bearer")) {
        matched |= MessageDigest.isEqual(value.getBytes(StandardCharsets.UTF_8), expected);
      } else if (scheme.equals("basic")) {
        matched |= MessageDigest.isEqual(basicPassword(value), expected);
      }
    }
    return matched;
  }

  /**
   * The password half of a Basic credential; an undecodable value or one without the {@code
   * user:password} separator compares as empty.
   */
  private static byte[] basicPassword(String encoded) {
    try {
      String decoded = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
      int colon = decoded.indexOf(':');
      return colon < 0
          ? new byte[0]
          : decoded.substring(colon + 1).getBytes(StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      return new byte[0];
    }
  }
}
