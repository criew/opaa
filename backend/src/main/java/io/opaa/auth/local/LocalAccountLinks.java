package io.opaa.auth.local;

import io.opaa.common.PublicBaseUrl;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * The SPA routes the local account management links to (ADR-0033, Entscheidungen 10 and 11) -
 * English paths like every route of the SPA, the raw token as the {@code token} query. A link is
 * absolute only with {@code OPAA_PUBLIC_BASE_URL}; without one it is a path relative to the
 * installation, so an invitation handed to the administrator stays redeemable where no mail could
 * ever carry a link. The pages behind the paths are #1539/#1540/#1541.
 */
public final class LocalAccountLinks {

  /** The page that redeems an invitation or a password-reset link ({@code ?token=…}). */
  public static final String SET_PASSWORD_PATH = "set-password";

  /** The page that confirms the address of a self-registered account ({@code ?token=…}). */
  public static final String VERIFY_EMAIL_PATH = "verify-email";

  public static final String TOKEN_QUERY = "token";
  public static final String LOGIN_PATH = "login";

  /**
   * The administration's list of local accounts - the SPA route of #1541, not the API path {@code
   * /api/v1/admin/local-users} the list is fed from. The quarterly review reminder links here.
   */
  public static final String ADMIN_LOCAL_USERS_PATH = "admin/users";

  private LocalAccountLinks() {}

  /** Absolute with a configured base, otherwise {@code /set-password?token=…}. */
  static String setPasswordLink(PublicBaseUrl base, String rawToken) {
    return tokenLink(base, SET_PASSWORD_PATH, rawToken);
  }

  /** Absolute with a configured base, otherwise {@code /verify-email?token=…}. */
  static String verifyEmailLink(PublicBaseUrl base, String rawToken) {
    return tokenLink(base, VERIFY_EMAIL_PATH, rawToken);
  }

  /** The absolute link to {@code path}, or an empty string while no base is configured. */
  static String absoluteOrEmpty(PublicBaseUrl base, String path) {
    return base.link(path).orElse("");
  }

  private static String tokenLink(PublicBaseUrl base, String path, String rawToken) {
    return base.link(path, TOKEN_QUERY, rawToken)
        .orElseGet(
            () ->
                "/"
                    + path
                    + "?"
                    + TOKEN_QUERY
                    + "="
                    + URLEncoder.encode(rawToken, StandardCharsets.UTF_8));
  }
}
