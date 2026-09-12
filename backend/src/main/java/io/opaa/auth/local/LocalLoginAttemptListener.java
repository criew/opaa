package io.opaa.auth.local;

import io.opaa.auth.User;
import java.time.Instant;

/**
 * The extension point of {@link LocalLoginService} for what happens around a sign-in beyond the
 * counter it keeps itself: the lockout after five failed attempts and its audit event (#1535), the
 * bootstrap account's sign-in event and mail (#1534). Every bean of this type is called, in
 * registration order; the credentials passed are reloaded after the service's own atomic counter
 * update, so a listener may read the current count - but must not {@code save()} the row it was
 * handed after issuing bulk updates of its own.
 */
public interface LocalLoginAttemptListener {

  /** The password was verified and the account was {@code ACTIVE}; the sign-in proceeds. */
  void onLoginSucceeded(User user, LocalCredentials credentials, Instant now);

  /**
   * A known account presented a wrong password; the failed-login counter has already been
   * incremented. Not called for unknown addresses, and not for a correct password refused for
   * another reason (locked, expired, invited, management off).
   */
  void onPasswordRejected(User user, LocalCredentials credentials, Instant now);
}
