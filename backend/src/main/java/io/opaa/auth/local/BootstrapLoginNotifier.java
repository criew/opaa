package io.opaa.auth.local;

import io.opaa.auth.User;
import java.time.Instant;

/**
 * Tells the other system administrators that the bootstrap account was used (ADR-0033, Entscheidung
 * 5: mail {@code BOOTSTRAP_ACCOUNT_USED} to every other {@code SYSTEM_ADMIN}), implemented by
 * {@link MailingBootstrapLoginNotifier}; the audit event itself is written by {@link
 * BootstrapAccountLoginListener} regardless of the notifier.
 */
@FunctionalInterface
public interface BootstrapLoginNotifier {

  void bootstrapAccountSignedIn(User bootstrapAccount, Instant at);
}
