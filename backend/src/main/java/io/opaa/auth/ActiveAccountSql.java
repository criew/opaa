package io.opaa.auth;

/**
 * The SQL sibling of {@link AccountActivityService#activeAmong}: the same definition of an active
 * account, for a caller that only wants the <b>number</b> and must not load the accounts to get it
 * (#1820). Both encodings are held together by a parity test, so a change to one without the other
 * fails the build.
 *
 * <p>{@link #PREDICATE} expects the {@code users} row under the alias {@code u} and one named
 * parameter {@code :now}, the moment the definition is evaluated at - supplied by the caller from
 * the same {@link java.time.Clock} the Java path uses, never by the database's own clock.
 */
public final class ActiveAccountSql {

  /**
   * Directory lock plus, for a local account, the derived credential state {@code
   * LocalCredentials#isLoginCapable} answers: no lock in force, no reached expiry, password set and
   * address confirmed. A FAILED_LOGINS lock ends by itself with {@code lockout_until}; every other
   * lock lasts until it is taken back.
   */
  public static final String PREDICATE =
      """
      u.directory_locked_at IS NULL
        AND (u.issuer <> '"""
          + LocalIssuer.URN
          + """
      '
             OR EXISTS (SELECT 1 FROM local_credentials c
                         WHERE c.user_id = u.id
                           AND c.password_hash IS NOT NULL
                           AND c.email_verified_at IS NOT NULL
                           AND (c.expires_at IS NULL OR c.expires_at > :now)
                           AND (CASE WHEN c.locked_reason = 'FAILED_LOGINS'
                                     THEN c.lockout_until IS NULL OR c.lockout_until <= :now
                                     ELSE c.locked_at IS NULL END)))
      """;

  private ActiveAccountSql() {}
}
