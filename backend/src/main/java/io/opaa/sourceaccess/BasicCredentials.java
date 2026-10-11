package io.opaa.sourceaccess;

/**
 * A source configuration's {@code sourceCredentials} ({@code user:password}, Basic Auth), parsed
 * once for every caller that owns a source configuration.
 *
 * @param username {@code null} without usable credentials
 * @param password {@code null} without usable credentials
 */
public record BasicCredentials(String username, String password) {

  private static final BasicCredentials NONE = new BasicCredentials(null, null);

  /** {@code user:password}; anything without a non-empty user part is no credentials. */
  public static BasicCredentials parse(String credentials) {
    if (credentials == null || credentials.isBlank()) {
      return NONE;
    }
    int colonIdx = credentials.indexOf(':');
    if (colonIdx <= 0) {
      return NONE;
    }
    return new BasicCredentials(
        credentials.substring(0, colonIdx), credentials.substring(colonIdx + 1));
  }

  /** Names whether a password is set, never its value. */
  @Override
  public String toString() {
    return "BasicCredentials[username="
        + username
        + ", password="
        + (password == null ? "null" : "***")
        + "]";
  }
}
