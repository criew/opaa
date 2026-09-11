package io.opaa.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * The keys of the local token issuer (ADR-0033, Entscheidung 6), all derived from {@code
 * OPAA_AUTH_JWT_SECRET} with HKDF-SHA256 and one purpose label each, so the access-token signature,
 * the refresh-token lookup and the action-token lookup never share a key - and a rotation of the
 * one secret changes all three at once, ending every local session and every open link. Constructed
 * by {@code io.opaa.auth.local.LocalAuthConfiguration}; without a secret (only possible in the
 * {@code dev} profile, where the startup guard is inert) every derivation fails with the same
 * message the guard would have given.
 */
public final class LocalAuthKeyService {

  /** The three purposes, each with the HKDF {@code info} label ADR-0033 names. */
  public enum Purpose {
    ACCESS_TOKEN("opaa:jwt:access-token"),
    REFRESH_TOKEN_LOOKUP("opaa:jwt:refresh-token-lookup"),
    ACTION_TOKEN_LOOKUP("opaa:jwt:action-token-lookup");

    private final String info;

    Purpose(String info) {
      this.info = info;
    }

    public String info() {
      return info;
    }
  }

  public static final String SECRET_VARIABLE = "OPAA_AUTH_JWT_SECRET";

  private static final String HMAC_ALGORITHM = "HmacSHA256";
  private static final int KEY_LENGTH_BYTES = 32;
  private static final byte[] APPLICATION_SALT =
      "opaa:auth:hkdf:v1".getBytes(StandardCharsets.UTF_8);
  private static final HexFormat HEX = HexFormat.of();

  private final Map<Purpose, SecretKey> keys;

  public LocalAuthKeyService(String jwtSecret) {
    String secret = jwtSecret == null ? "" : jwtSecret.trim();
    this.keys = secret.isEmpty() ? Map.of() : deriveAll(secret);
  }

  /** The 256-bit HMAC-SHA256 key of {@code purpose}. */
  public SecretKey key(Purpose purpose) {
    Objects.requireNonNull(purpose, "purpose");
    SecretKey key = keys.get(purpose);
    if (key == null) {
      throw new IllegalStateException(
          "Local accounts have no signing secret: "
              + SecretValidator.describeRequirement(SECRET_VARIABLE));
    }
    return key;
  }

  /**
   * The value the token tables store for a raw refresh or action token: HMAC-SHA256 under the
   * purpose's key, as 64 lowercase hex characters. The raw token itself is never persisted.
   */
  public String lookupHash(Purpose purpose, String rawToken) {
    Objects.requireNonNull(rawToken, "rawToken");
    try {
      Mac mac = Mac.getInstance(HMAC_ALGORITHM);
      mac.init(key(purpose));
      return HEX.formatHex(mac.doFinal(rawToken.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HMAC-SHA256 is required but unavailable", e);
    }
  }

  /**
   * The denylist key of an access token's {@code jti}: a plain SHA-256 hex digest. A {@code jti} is
   * a random UUID, not a bearer secret, so no key is needed - and the denylist deliberately
   * survives a secret rotation.
   */
  public static String jtiHash(String jti) {
    Objects.requireNonNull(jti, "jti");
    try {
      return HEX.formatHex(
          MessageDigest.getInstance("SHA-256").digest(jti.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("SHA-256 is required but unavailable", e);
    }
  }

  private static Map<Purpose, SecretKey> deriveAll(String secret) {
    byte[] ikm = secret.getBytes(StandardCharsets.UTF_8);
    Map<Purpose, SecretKey> derived = new EnumMap<>(Purpose.class);
    for (Purpose purpose : Purpose.values()) {
      byte[] info = purpose.info().getBytes(StandardCharsets.UTF_8);
      byte[] keyBytes = Hkdf.deriveKey(ikm, APPLICATION_SALT, info, KEY_LENGTH_BYTES);
      derived.put(purpose, new SecretKeySpec(keyBytes, HMAC_ALGORITHM));
    }
    return Map.copyOf(derived);
  }
}
