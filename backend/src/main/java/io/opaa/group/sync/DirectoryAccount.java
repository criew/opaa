package io.opaa.group.sync;

import java.util.Objects;

/**
 * One account as the directory reports it for a single synchronisation snapshot (#1818, ADR-0036
 * Entscheidung 3).
 *
 * @param subject the OIDC {@code subject} of the account, matched against {@code User.subject}
 *     scoped to the organization and the provider's issuer - the same identity {@link
 *     DirectoryGroup#memberSubjects()} uses. A subject with no matching user is skipped: an account
 *     that never signed in has nothing to lock.
 * @param enabled whether the directory lets this account sign in ({@code enabled} of a Keycloak
 *     user). An account the directory reports as disabled is locked by the next run; one it stops
 *     reporting altogether is treated the same way - that is what leaving the organization looks
 *     like from here.
 */
public record DirectoryAccount(String subject, boolean enabled) {

  public DirectoryAccount {
    Objects.requireNonNull(subject, "subject must not be null");
  }
}
