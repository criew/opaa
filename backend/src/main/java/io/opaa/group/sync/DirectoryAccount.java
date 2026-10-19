package io.opaa.group.sync;

import java.util.Objects;

/**
 * One account as reported by the directory for a single synchronisation snapshot (#1818).
 *
 * @param subject the OIDC {@code subject} of the account, matched against {@code User.subject}
 *     scoped to the organization and to the provider's issuer - the same identity {@link
 *     DirectoryGroup#memberSubjects()} carries. A subject with no matching user is skipped: this
 *     run blocks accounts, it never creates them.
 * @param enabled whether the directory reports the account as usable. {@code false} means the
 *     account is locked there; an account the directory does not report <em>at all</em> is a
 *     departure and treated the same way ("Verschwindet ein Konto im Verzeichnis oder wird es dort
 *     gesperrt", access-control.md) - which is why {@link DirectorySnapshot#accounts()} has to be
 *     the directory's complete list, not a selection.
 */
public record DirectoryAccount(String subject, boolean enabled) {

  public DirectoryAccount {
    Objects.requireNonNull(subject, "subject must not be null");
  }
}
