package io.opaa.connection.token;

import java.time.Instant;
import java.util.Objects;

/**
 * A secret on its way into {@link ConnectionSecrets#store}; its form decides the stored kind. A
 * further form (an OAuth grant) is a further permitted type. {@link #toString} never shows a value.
 */
public sealed interface NewSecret permits NewSecret.Personal {

  /** An app password, a token or {@code user:password}, without an expiry of its own. */
  static NewSecret personal(String value) {
    return new Personal(value, null);
  }

  /**
   * A personal secret as entered.
   *
   * @param expiresAt when the provider said it ends, {@code null} for never
   */
  record Personal(String value, Instant expiresAt) implements NewSecret {

    public Personal {
      Objects.requireNonNull(value, "value");
      if (value.isBlank()) {
        throw new IllegalArgumentException("a personal secret is not blank");
      }
    }

    @Override
    public String toString() {
      return "Personal[value=***, expiresAt=" + expiresAt + "]";
    }
  }
}
