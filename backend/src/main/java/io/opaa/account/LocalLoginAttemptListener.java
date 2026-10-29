package io.opaa.account;

import io.opaa.auth.LocalCredentials;
import io.opaa.auth.User;
import java.time.Instant;

/**
 * The extension point of {@link LocalLoginService} for what happens around a sign-in beyond the
 * counter it keeps itself: the lockout after five failed attempts and its audit event, the
 * bootstrap account's sign-in event and mail. Every bean of this type is called, in registration
 * order, with the credentials {@link LocalLoginService} read under the account's row lock - for a
 * rejected password re-read after its counter update, so a listener may read the current count -
 * but must not {@code save()} the row it was handed after issuing bulk updates of its own.
 */
public interface LocalLoginAttemptListener {

  /**
   * The password was verified and the account was {@code ACTIVE}; the sign-in proceeds. Called
   * after the deciding transaction has committed, without the row lock, with the row as read under
   * it.
   */
  void onLoginSucceeded(User user, LocalCredentials credentials, Instant now);

  /**
   * A known account presented a wrong password; the failed-login counter has already been
   * incremented. Called inside the transaction that holds the account's row lock, so no parallel
   * attempt is decided until it returns. Not called for unknown addresses, not while the account is
   * locked, and not for a correct password refused for another reason (locked, expired, invited,
   * management off).
   */
  void onPasswordRejected(User user, LocalCredentials credentials, Instant now);
}
