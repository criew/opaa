package io.opaa.auth;

import io.opaa.auth.local.LocalCredentials;
import io.opaa.auth.local.LocalCredentialsRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Which of a set of accounts may exercise their rights right now - the one place that joins the two
 * halves the installation already stores, rather than inventing a third notion of "active":
 *
 * <ul>
 *   <li>a lock from the directory synchronisation takes the access away regardless of the issuer
 *       behind it ({@link User#isDirectoryLocked()}, #1818) - the same rule {@code
 *       UserProvisioningFilter} applies to every request;
 *   <li>for a local account the derived state of its credentials decides ({@link
 *       LocalCredentials#isLoginCapable}, ADR-0033 Entscheidung 3), so a locked, expired or still
 *       invited account counts as little as a locked one. A local account without a credentials row
 *       cannot sign in and is not active either, exactly as {@code LocalAdminAvailabilityGuard}
 *       reads it.
 * </ul>
 *
 * <p><b>Whether a provider is switched off is deliberately not part of this</b>: that is a property
 * of the provider, not of the account, and it is decided where a group's effectiveness is decided
 * ({@code GroupSubject#providerDisabled}), not per member.
 *
 * <p>This answers a <em>counting</em> question (ADR-0036, Entscheidung 7, Schutzpunkt 3). It is not
 * the rights resolution: a membership of a locked account stays in force, and {@code
 * GroupMembershipResolver#resolveUserIds} keeps returning it - the lock is enforced at the request,
 * not by rewriting who a grant reaches.
 */
@Service
public class AccountActivityService {

  private final UserRepository users;
  private final LocalCredentialsRepository localCredentials;
  private final Clock clock;

  AccountActivityService(
      UserRepository users, LocalCredentialsRepository localCredentials, Clock clock) {
    this.users = users;
    this.localCredentials = localCredentials;
    this.clock = clock;
  }

  /**
   * The moment this definition is evaluated at. A caller that counts the same accounts in SQL
   * ({@link ActiveAccountSql}) passes this instant in, so both halves judge expiry and lockout
   * against the same clock.
   */
  public Instant now() {
    return clock.instant();
  }

  /** Of {@code userIds}, those that may exercise their rights; an empty input answers empty. */
  @Transactional(readOnly = true)
  public Set<UUID> activeAmong(Collection<UUID> userIds) {
    if (userIds.isEmpty()) {
      return Set.of();
    }
    List<User> candidates =
        users.findAllById(userIds).stream().filter(user -> !user.isDirectoryLocked()).toList();
    List<UUID> localIds =
        candidates.stream().filter(AccountActivityService::isLocal).map(User::getId).toList();
    Map<UUID, LocalCredentials> credentialsByUser =
        localIds.isEmpty()
            ? Map.of()
            : localCredentials.findAllById(localIds).stream()
                .collect(Collectors.toMap(LocalCredentials::getUserId, Function.identity()));

    Instant now = clock.instant();
    Set<UUID> active = new HashSet<>();
    for (User candidate : candidates) {
      if (!isLocal(candidate)) {
        active.add(candidate.getId());
        continue;
      }
      LocalCredentials credentials = credentialsByUser.get(candidate.getId());
      if (credentials != null && credentials.isLoginCapable(now)) {
        active.add(candidate.getId());
      }
    }
    return active;
  }

  private static boolean isLocal(User user) {
    return LocalIssuer.URN.equals(user.getIssuer());
  }
}
