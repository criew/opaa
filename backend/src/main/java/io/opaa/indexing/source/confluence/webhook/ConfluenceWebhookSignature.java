package io.opaa.indexing.source.confluence.webhook;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Authenticates a webhook notification against the library's own secret (#1140), for both ways a
 * sender can carry it: Confluence Data Center signs the raw body with HMAC-SHA256 and sends the hex
 * digest as {@code X-Hub-Signature: sha256=<hex>}; a Confluence Cloud Automation rule ("Send web
 * request") cannot sign, so it sends the secret itself in {@value #SHARED_SECRET_HEADER}. Both
 * comparisons are constant-time; a request carrying neither header, or an unparseable one, is not
 * authenticated. A signature is verified over the bytes exactly as received - the body is never
 * re-serialised before the comparison.
 */
public final class ConfluenceWebhookSignature {

  public static final String HUB_SIGNATURE_HEADER = "X-Hub-Signature";
  public static final String SHARED_SECRET_HEADER = "X-OPAA-Webhook-Secret";

  private static final String SIGNATURE_PREFIX = "sha256=";
  private static final String DUMMY_SECRET = "no-secret-configured-for-this-library";
  private static final String HMAC_ALGORITHM = "HmacSHA256";

  private ConfluenceWebhookSignature() {}

  /**
   * @param body the request body exactly as received (empty, never {@code null})
   * @param hubSignature the {@value #HUB_SIGNATURE_HEADER} header, or {@code null}
   * @param sharedSecret the {@value #SHARED_SECRET_HEADER} header, or {@code null}
   * @param secret the library's stored secret; {@code null} authenticates nothing
   */
  public static boolean verify(
      byte[] body, String hubSignature, String sharedSecret, String secret) {
    if (secret == null || secret.isBlank()) {
      // The same work as for a library with a secret, so the answer time does not tell a caller
      // whether this library exists and carries a webhook; the result is discarded.
      if (hubSignature != null) {
        matchesHmac(body, hubSignature, DUMMY_SECRET);
      }
      return false;
    }
    if (hubSignature != null && matchesHmac(body, hubSignature, secret)) {
      return true;
    }
    return sharedSecret != null
        && MessageDigest.isEqual(
            sharedSecret.getBytes(StandardCharsets.UTF_8), secret.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * The {@code sha256=<hex>} value Data Center would send for {@code body} under {@code secret}.
   */
  public static String sign(byte[] body, String secret) {
    return SIGNATURE_PREFIX + HexFormat.of().formatHex(hmac(body, secret));
  }

  private static boolean matchesHmac(byte[] body, String header, String secret) {
    String trimmed = header.trim();
    if (!trimmed.toLowerCase(Locale.ROOT).startsWith(SIGNATURE_PREFIX)) {
      return false;
    }
    byte[] presented;
    try {
      presented = HexFormat.of().parseHex(trimmed.substring(SIGNATURE_PREFIX.length()));
    } catch (IllegalArgumentException e) {
      return false;
    }
    return MessageDigest.isEqual(presented, hmac(body, secret));
  }

  private static byte[] hmac(byte[] body, String secret) {
    try {
      Mac mac = Mac.getInstance(HMAC_ALGORITHM);
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
      return mac.doFinal(body);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(HMAC_ALGORITHM + " is unavailable", e);
    }
  }
}
