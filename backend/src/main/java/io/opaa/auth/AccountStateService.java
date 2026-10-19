package io.opaa.auth;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.AuditSubjectKind;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.permission.PermissionHistoryClock;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The account state and its history in one place (#1818, ADR-0036 Entscheidungen 3, 6 and 8). There
 * is no state column: the open interval of {@code account_state_history} <em>is</em> the state, so
 * the answer to "is this account blocked" and the evidence for "since when" can never disagree, and
 * no write path can forget to keep a second copy in step.
 *
 * <p><b>A block ends the way in, not the rights.</b> Memberships, grants and ownership stay exactly
 * as they were - that is what makes the block reversible without a break in the history (ADR-0036,
 * Entscheidung 6). What does end are the running sessions and the access tokens of ADR-0035; the
 * tokens through the {@link AccountAccessEndedEvent} published here, the sessions because {@link
 * UserProvisioningFilter} refuses every request of a blocked account, including one carrying a
 * still-valid provider token.
 *
 * <p>Every method joins the caller's transaction: the state change, its interval and its audit
 * entry commit together or not at all.
 */
@Service
public class AccountStateService {

  private static final Logger log = LoggerFactory.getLogger(AccountStateService.class);

  private final AccountStateHistoryRepository history;
  private final AuditEventRecorder auditEventRecorder;
  private final ApplicationEventPublisher eventPublisher;
  private final PermissionHistoryClock clock;

  AccountStateService(
      AccountStateHistoryRepository history,
      AuditEventRecorder auditEventRecorder,
      ApplicationEventPublisher eventPublisher,
      PermissionHistoryClock clock) {
    this.history = history;
    this.auditEventRecorder = auditEventRecorder;
    this.eventPublisher = eventPublisher;
    this.clock = clock;
  }

  /** Whether this one account is blocked right now. */
  @Transactional(readOnly = true)
  public boolean isBlocked(UUID userId) {
    return history
        .findByUserIdAndValidToIsNull(userId)
        .map(interval -> interval.getState() == AccountState.BLOCKED)
        .orElse(false);
  }

  /** The blocked accounts among {@code userIds}; empty for an empty input, without a query. */
  @Transactional(readOnly = true)
  public Set<UUID> blockedAmong(Collection<UUID> userIds) {
    return userIds.isEmpty() ? Set.of() : history.findBlockedUserIdsIn(userIds);
  }

  /**
   * Moves the account into the state {@code cause} belongs to, closing whatever interval was open
   * and opening the next one, and records the change once - in the rights history and in the audit
   * log. A call that would not change the state writes nothing and returns {@code false}, so a run
   * that reports the same directory reading twice leaves one interval, not two.
   *
   * @param actorUserId the person who confirmed the run, or {@code null} for a run the schedule or
   *     an unconfirmed manual trigger made - the audit entry names the process either way
   *     (correlation to the run's header entry carries the confirming person)
   */
  @Transactional
  public boolean apply(
      User user,
      AccountStateHistoryCause cause,
      UUID providerId,
      UUID actorUserId,
      String correlationRef) {
    Optional<AccountStateHistory> open = history.findByUserIdAndValidToIsNull(user.getId());
    AccountState current = open.map(AccountStateHistory::getState).orElse(AccountState.ACTIVE);
    if (current == cause.state()) {
      return false;
    }
    Instant now = clock.nextBoundary();
    // Flushed immediately: Hibernate runs every queued insert before every queued update, so
    // without this the new row's INSERT would reach Postgres before the old row's UPDATE ... SET
    // valid_to and transiently violate uk_account_state_history_open.
    open.ifPresent(
        interval -> {
          interval.close(now);
          history.saveAndFlush(interval);
        });
    history.save(
        AccountStateHistory.open(
            user.getId(), user.getOrganizationId(), cause, providerId, actorUserId, now));
    recordAudit(user, cause, providerId, correlationRef);
    if (cause.state() == AccountState.BLOCKED) {
      log.info(
          "Account {} blocked by the directory run of provider {} ({})",
          user.getId(),
          providerId,
          cause);
      // Ends the access tokens of ADR-0035 inside this same transaction - the block and their end
      // commit together, exactly as they do for a locked local account.
      eventPublisher.publishEvent(AccountAccessEndedEvent.bySystem(user, DIRECTORY_SYNC_ACTOR));
    } else {
      log.info(
          "Account {} reactivated by the directory run of provider {}", user.getId(), providerId);
    }
    return true;
  }

  /**
   * Mirrors {@code DirectorySyncPlanExecutor}'s identical constant - the fixed, non-pseudonymised
   * label of the run; a sync run has no acting person.
   */
  private static final String DIRECTORY_SYNC_ACTOR = "directory-sync";

  /**
   * One entry per account whose state changed. Object and subject are the same person, so both
   * resolve to the same pseudonym and {@code objectLabel} stays null - the reasoning {@code
   * UserService#updateRole} states for the role change applies unchanged here.
   */
  private void recordAudit(
      User user, AccountStateHistoryCause cause, UUID providerId, String correlationRef) {
    UUID pseudonym = auditEventRecorder.pseudonymFor(user.getId(), user.getOrganizationId());
    Map<String, Object> after = new LinkedHashMap<>();
    after.put("state", cause.state().name());
    after.put("cause", cause.name());
    if (providerId != null) {
      after.put("providerId", providerId.toString());
    }
    auditEventRecorder.recordSystemProcessAction(
        AuditEvent.builder()
            .organizationId(user.getOrganizationId())
            .actorRef(DIRECTORY_SYNC_ACTOR)
            .type(
                cause.state() == AccountState.BLOCKED
                    ? AuditEventType.ACCOUNT_DEACTIVATED
                    : AuditEventType.ACCOUNT_REACTIVATED)
            .object(AuditObjectType.USER_ACCOUNT, pseudonym, null)
            .subject(AuditSubjectKind.USER, user.getId())
            .after(after)
            .outcome(AuditOutcome.SUCCESS)
            .correlationRef(correlationRef)
            .build());
  }
}
