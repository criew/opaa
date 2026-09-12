package io.opaa.auth.local;

import io.opaa.common.PublicBaseUrl;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * The SPA paths the local account management links to (ADR-0033, Entscheidungen 10 and 11). A link
 * is absolute only with {@code OPAA_PUBLIC_BASE_URL}; without one, the set-password link is handed
 * to the administrator as a path relative to the installation, so a hand-over stays possible where
 * no mail could ever carry a link. The pages behind the paths are #1539/#1540/#1541.
 */
public final class LocalAccountLinks {

  /** The page that redeems an invitation or a password-reset link ({@code ?token=…}). */
  public static final String SET_PASSWORD_PATH = "konto/passwort";

  public static final String TOKEN_QUERY = "token";
  public static final String LOGIN_PATH = "login";
  public static final String ADMIN_LOCAL_USERS_PATH = "admin/local-users";

  private LocalAccountLinks() {}

  /** Absolute with a configured base, otherwise {@code /konto/passwort?token=…}. */
  static String setPasswordLink(PublicBaseUrl base, String rawToken) {
    return base.link(SET_PASSWORD_PATH, TOKEN_QUERY, rawToken)
        .orElseGet(
            () ->
                "/"
                    + SET_PASSWORD_PATH
                    + "?"
                    + TOKEN_QUERY
                    + "="
                    + URLEncoder.encode(rawToken, StandardCharsets.UTF_8));
  }

  /** The absolute link to {@code path}, or an empty string while no base is configured. */
  static String absoluteOrEmpty(PublicBaseUrl base, String path) {
    return base.link(path).orElse("");
  }
}
