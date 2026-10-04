package io.opaa.indexing.source;

import java.time.Instant;
import java.util.Objects;

/**
 * A secret on its way to a connector, with its kind and, for a token that expires, the instant it
 * does ({@code null} for none); {@link #toString} never shows the value.
 */
public record Secret(SecretKind kind, String value, Instant expiresAt) {

  public Secret {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(value, "value");
  }

  /** A secret without an expiry of its own. */
  public Secret(SecretKind kind, String value) {
    this(kind, value, null);
  }

  /** {@code value} as a personal secret, {@code null} for none. */
  public static Secret personal(String value) {
    return value == null ? null : new Secret(SecretKind.PERSONAL_SECRET, value);
  }

  /** The value of {@code secret}, {@code null} for none. */
  public static String valueOf(Secret secret) {
    return secret == null ? null : secret.value();
  }

  @Override
  public String toString() {
    return "Secret[kind=" + kind + ", value=***, expiresAt=" + expiresAt + "]";
  }
}
