package io.opaa.auth.local;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.LocalAccountState;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.local.LocalUserService.CreatedAccount;
import io.opaa.auth.local.LocalUserService.ResetLinkIssued;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The administration of local accounts as the API sees it (ADR-0033, Entscheidung 11): the list
 * with its review filters, the counts, and every act - each a transaction in {@link
 * LocalUserService}, followed by the mail {@link LocalAccountMailer} sends <em>after</em> that
 * commit, and for a link by the audit of its delivery path in a second, short transaction.
 *
 * <p>Failure directions of the two-phase acts: if the mail step fails after the account was
 * committed, the account exists and the administrator repeats the delivery through the reset
 * endpoint; if the audit of the delivery path fails after a sent mail, the person holds a valid
 * link whose delivery event is missing - the request answers 500, and the administrator's repeat
 * supersedes the link and writes the event. Deliberately not {@code @Transactional}: a send of up
 * to the SMTP timeouts must never hold a database connection.
 */
@Service
public class LocalUserAdminService {

  private final LocalUserService accounts;
  private final LocalAccountMailer mailer;
  private final Clock clock;

  public LocalUserAdminService(LocalUserService accounts, LocalAccountMailer mailer, Clock clock) {
    this.accounts = accounts;
    this.mailer = mailer;
    this.clock = clock;
  }

  public LocalUserPage list(UUID organizationId, LocalUserQuery query) {
    List<LocalUserOverview> matching =
        accounts.allOf(organizationId).stream()
            .filter(overview -> matches(overview, query))
            .sorted(comparator(query))
            .toList();
    int from = Math.min(query.page() * query.size(), matching.size());
    int to = Math.min(from + query.size(), matching.size());
    return new LocalUserPage(
        matching.subList(from, to), matching.size(), query.page(), query.size());
  }

  public LocalUserSummary summary(UUID organizationId) {
    List<LocalUserOverview> all = accounts.allOf(organizationId);
    return new LocalUserSummary(
        all.size(),
        all.stream()
            .filter(o -> o.credentials().getExpiresAt() == null && !o.credentials().isBootstrap())
            .count(),
        all.stream().filter(o -> o.state() == LocalAccountState.LOCKED).count(),
        all.stream().filter(o -> o.state() == LocalAccountState.INVITED).count(),
        nextReviewOn(LocalDate.ofInstant(clock.instant(), ZoneId.systemDefault())));
  }

  public LocalUserOverview get(UUID organizationId, UUID userId) {
    return accounts.get(organizationId, userId);
  }

  public LocalUserCreated create(CurrentUser actor, LocalUserCreation creation) {
    CreatedAccount created = accounts.create(actor, creation);
    UUID id = created.user().getId();
    if (created.invitation() == null) {
      return new LocalUserCreated(
          accounts.get(actor.organizationId(), id), null, created.initialPassword());
    }
    LinkDelivery delivery = mailer.sendInvitation(created.user(), created.invitation());
    accounts.recordLinkDelivery(
        actor, created.user(), AuditEventType.LOCAL_USER_INVITED, delivery.path());
    return new LocalUserCreated(accounts.get(actor.organizationId(), id), delivery, null);
  }

  public LocalUserOverview update(CurrentUser actor, UUID userId, LocalUserUpdate update) {
    return accounts.update(actor, userId, update);
  }

  /** {@code reason} is the sentence for the person's mail; it is never stored or audited. */
  public LocalUserOverview lock(CurrentUser actor, UUID userId, String reason) {
    LocalUserOverview locked = accounts.lock(actor, userId);
    mailer.sendLocked(locked.user(), reason);
    return locked;
  }

  public LocalUserOverview unlock(CurrentUser actor, UUID userId) {
    LocalUserOverview unlocked = accounts.unlock(actor, userId);
    mailer.sendUnlocked(unlocked.user());
    return unlocked;
  }

  public LinkDelivery requestPasswordReset(CurrentUser actor, UUID userId) {
    ResetLinkIssued issued = accounts.issueResetLink(actor, userId);
    LinkDelivery delivery =
        issued.invitation()
            ? mailer.sendInvitation(issued.user(), issued.token())
            : mailer.sendAdminPasswordReset(issued.user(), issued.token());
    accounts.recordLinkDelivery(
        actor,
        issued.user(),
        issued.invitation()
            ? AuditEventType.LOCAL_USER_INVITED
            : AuditEventType.LOCAL_USER_PASSWORD_RESET_REQUESTED,
        delivery.path());
    return delivery;
  }

  public String generatePassword(CurrentUser actor, UUID userId) {
    return accounts.generatePassword(actor, userId);
  }

  public void delete(CurrentUser actor, UUID userId) {
    accounts.delete(actor, userId);
  }

  /** The first day of the next quarter after {@code today}. */
  static LocalDate nextReviewOn(LocalDate today) {
    int quarterStartMonth = ((today.getMonthValue() - 1) / 3) * 3 + 1;
    return LocalDate.of(today.getYear(), quarterStartMonth, 1).plusMonths(3);
  }

  private static boolean matches(LocalUserOverview overview, LocalUserQuery query) {
    if (query.status() != null && overview.state() != query.status()) {
      return false;
    }
    if (query.role() != null && overview.user().getSystemRole() != query.role()) {
      return false;
    }
    if (query.withoutExpiry() && overview.credentials().getExpiresAt() != null) {
      return false;
    }
    if (query.inactive() && !overview.isInactive()) {
      return false;
    }
    if (query.query() != null) {
      String needle = query.query().toLowerCase(Locale.ROOT);
      String email = overview.user().getEmail();
      String name = overview.user().getDisplayName();
      boolean inEmail = email != null && email.toLowerCase(Locale.ROOT).contains(needle);
      boolean inName = name != null && name.toLowerCase(Locale.ROOT).contains(needle);
      return inEmail || inName;
    }
    return true;
  }

  private static Comparator<LocalUserOverview> comparator(LocalUserQuery query) {
    Comparator<LocalUserOverview> comparator =
        switch (query.sort()) {
          case DISPLAY_NAME ->
              Comparator.comparing(
                  o -> o.user().getDisplayName() == null ? "" : o.user().getDisplayName(),
                  String.CASE_INSENSITIVE_ORDER);
          case EMAIL ->
              Comparator.comparing(
                  o -> o.user().getEmail() == null ? "" : o.user().getEmail(),
                  String.CASE_INSENSITIVE_ORDER);
          case EXPIRES_AT ->
              Comparator.comparing(
                  o -> o.credentials().getExpiresAt(),
                  Comparator.nullsLast(Comparator.naturalOrder()));
          case CREATED_AT -> Comparator.comparing(o -> o.credentials().getCreatedAt());
        };
    if (query.descending()) {
      comparator = comparator.reversed();
    }
    return comparator.thenComparing(o -> o.user().getId());
  }
}
