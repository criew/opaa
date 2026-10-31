package io.opaa.chat;

import io.opaa.account.LocalUserService;
import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.AuditSubjectKind;
import io.opaa.api.types.ChatCleanupAfterLockStatus;
import io.opaa.api.types.ChatCleanupHoldPurpose;
import io.opaa.api.types.LockReason;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.audit.ChatCleanupHoldCounts;
import io.opaa.audit.ChatCleanupHoldTransition;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.LocalCredentials;
import io.opaa.auth.User;
import io.opaa.chat.LockedAccountChatCleanup.PendingRequest;
import io.opaa.chat.LockedAccountChatCleanupStore.DueAccount;
import io.opaa.chat.LockedAccountChatCleanupStore.ExpiredHold;
import io.opaa.chat.LockedAccountChatCleanupStore.Hold;
import io.opaa.chat.LockedAccountChatCleanupStore.HoldRequest;
import io.opaa.common.ConflictException;
import io.opaa.common.DatabaseClock;
import io.opaa.common.NotFoundException;
import io.opaa.common.ValidationException;
import io.opaa.instance.RetiredRetentionVariables.Pause;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Deletes the chats of local accounts locked by the administration once the period after the lock
 * ran out (docs/features/chat-list.md, "Löschung der Chats gesperrter Konten"), and holds that
 * deletion per account: one administrator requests a hold with a purpose of a closed list, a second
 * one approves it, and it ends by itself after the maximum duration of {@link
 * LockedAccountChatCleanupHoldProperties}. A lock for inactivity or failed sign-ins starts nothing;
 * an account held or named by an open Klärung keeps its chats until that ends. One run deletes at
 * most {@link #MAX_CHATS_PER_RUN} chats in batches of their own transaction; the rest follows the
 * next night. The log names totals only, the audit one entry per account and run without a count;
 * each run also names how many accounts become due within {@link #PREVIEW}, so the operation sees a
 * wave after a mass lock in advance.
 */
@Service
public class LockedAccountChatCleanupService {

  private static final Logger log = LoggerFactory.getLogger(LockedAccountChatCleanupService.class);

  static final String SYSTEM_ACTOR = "chat-locked-account-cleanup";
  static final int DELETE_BATCH_SIZE = 200;
  static final int MAX_CHATS_PER_RUN = 5_000;
  static final Duration PREVIEW = Duration.ofDays(30);
  static final Duration REQUEST_VALID_FOR = Duration.ofDays(14);
  static final int MAX_HOLDS_PER_RUN = 1_000;
  static final int MIN_REASON_LENGTH = 10;
  static final int MAX_REASON_LENGTH = 1_000;

  private final LockedAccountChatCleanupStore store;
  private final LockedAccountChatCleanupDates dates;
  private final LockedAccountChatCleanupSettings settings;
  private final ChatRepository chatRepository;
  private final LocalUserService localUsers;
  private final AuditEventRecorder audit;
  private final ChatCleanupHoldCounts holdCounts;
  private final LockedAccountChatCleanupHoldProperties holdProperties;
  private final DatabaseClock databaseClock;
  private final TransactionTemplate transactionTemplate;

  public LockedAccountChatCleanupService(
      LockedAccountChatCleanupStore store,
      LockedAccountChatCleanupDates dates,
      LockedAccountChatCleanupSettings settings,
      ChatRepository chatRepository,
      LocalUserService localUsers,
      AuditEventRecorder audit,
      ChatCleanupHoldCounts holdCounts,
      LockedAccountChatCleanupHoldProperties holdProperties,
      DatabaseClock databaseClock,
      PlatformTransactionManager transactionManager) {
    this.store = store;
    this.dates = dates;
    this.settings = settings;
    this.chatRepository = chatRepository;
    this.localUsers = localUsers;
    this.audit = audit;
    this.holdCounts = holdCounts;
    this.holdProperties = holdProperties;
    this.databaseClock = databaseClock;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
  }

  /** One run at {@code now}, the database clock; the caller holds no transaction. */
  public Result runOnce(Instant now) {
    return runOnce(now, MAX_CHATS_PER_RUN);
  }

  Result runOnce(Instant now, int maxChats) {
    maintainHolds(now);
    LockedAccountChatCleanupSettings.Setting setting = settings.setting();
    LockedAccountChatCleanupPolicy policy = setting.policy();
    List<Pause> pauses = settings.pauses(setting);
    if (!pauses.isEmpty()) {
      pauses.forEach(pause -> log.error(pause.error("Locked-account chat cleanup")));
      return Result.NONE;
    }
    if (!policy.enabled()) {
      store.stopCounting();
      return Result.NONE;
    }
    if (store.startCountingIfStopped()) {
      return Result.NONE;
    }
    Instant cutoff = now.minus(policy.period());
    int deleted = 0;
    int accounts = 0;
    int failed = 0;
    List<DueAccount> due;
    try {
      due = store.findDue(cutoff, now, maxChats);
    } catch (RuntimeException e) {
      log.error("Locked-account chat cleanup could not read the due accounts; nothing deleted", e);
      return new Result(0, 0, 0, true);
    }
    int handled = 0;
    for (DueAccount account : due) {
      if (deleted >= maxChats) {
        break;
      }
      handled++;
      try {
        int ofAccount = deleteAccountChats(account, cutoff, now, maxChats - deleted);
        if (ofAccount > 0) {
          accounts++;
          deleted += ofAccount;
        }
      } catch (RuntimeException e) {
        failed++;
        log.error(
            "Locked-account chat cleanup failed for one account ({}); the next run continues",
            e.getClass().getSimpleName());
      }
    }
    int left = due.size() - handled;
    if (failed > 0) {
      log.error(
          "Locked-account chat cleanup: deleted {} chat(s) of {} account(s), {} account(s) failed",
          deleted,
          accounts,
          failed);
    } else if (deleted > 0) {
      log.info(
          "Locked-account chat cleanup: deleted {} chat(s) of {} account(s){}",
          deleted,
          accounts,
          left > 0 || deleted >= maxChats ? ", the rest follows the next run" : "");
    }
    logUpcoming(cutoff, now);
    return new Result(deleted, accounts, failed, false);
  }

  /**
   * How many accounts are not yet due at {@code now} but will be within {@link #PREVIEW} under the
   * policy in force, without names; 0 while the deletion is paused, switched off or not yet
   * counting.
   */
  int upcomingAccounts(Instant now) {
    LockedAccountChatCleanupSettings.Setting setting = settings.setting();
    LockedAccountChatCleanupPolicy policy = setting.policy();
    if (!settings.pauses(setting).isEmpty()
        || !policy.enabled()
        || store.countingSince().isEmpty()) {
      return 0;
    }
    Instant cutoff = now.minus(policy.period());
    return store.countDueBetween(cutoff, cutoff.plus(PREVIEW), now);
  }

  private void logUpcoming(Instant cutoff, Instant now) {
    try {
      int upcoming = store.countDueBetween(cutoff, cutoff.plus(PREVIEW), now);
      if (upcoming > 0) {
        log.info(
            "Locked-account chat cleanup: {} account(s) become due within the next {} days",
            upcoming,
            PREVIEW.toDays());
      }
    } catch (RuntimeException e) {
      log.warn(
          "Locked-account chat cleanup could not count the accounts due soon ({})",
          e.getClass().getSimpleName());
    }
  }

  /** Deletes up to {@code budget} chats of {@code account}, batch by batch; returns how many. */
  private int deleteAccountChats(DueAccount account, Instant cutoff, Instant now, int budget) {
    int deleted = 0;
    while (deleted < budget) {
      int batch =
          deleteBatch(
              account, cutoff, now, Math.min(DELETE_BATCH_SIZE, budget - deleted), deleted == 0);
      if (batch == 0) {
        break;
      }
      deleted += batch;
    }
    return deleted;
  }

  /**
   * Deletes up to {@code limit} chats of {@code account} in one transaction if it is still due
   * under the lock of its credentials row; the first batch of a run that deletes anything records
   * the account's audit entry in the same transaction.
   */
  int deleteBatch(DueAccount account, Instant cutoff, Instant now, int limit, boolean first) {
    Integer count =
        transactionTemplate.execute(
            status -> {
              if (!store.lockStillDue(account.userId(), cutoff, now)) {
                return 0;
              }
              List<Chat> chats =
                  chatRepository.findAllById(store.lockChatsOf(account.userId(), limit));
              chatRepository.deleteAll(chats);
              chatRepository.flush();
              if (first && !chats.isEmpty()) {
                recordDeletion(account);
              }
              return chats.size();
            });
    return count == null ? 0 : count;
  }

  /** The deletion state of one local account of {@code organizationId}; 404 for any other. */
  @Transactional(readOnly = true)
  public LockedAccountChatCleanup stateOf(UUID organizationId, UUID userId) {
    return stateOf(localUsers.get(organizationId, userId).credentials());
  }

  /**
   * Files a request to hold the deletion for the account, or to extend the running hold, with a
   * purpose of the closed list and a reason. It holds nothing until a second administrator approves
   * it ({@link #approveHold}).
   *
   * @throws ValidationException without purpose or with a reason too short or too long
   * @throws ConflictException while a request for the account is pending
   */
  @Transactional
  public LockedAccountChatCleanup requestHold(
      CurrentUser actor, UUID userId, ChatCleanupHoldPurpose purpose, String reason) {
    User user = localUsers.get(actor.organizationId(), userId).user();
    String text = validReason(purpose, reason);
    store.lockAccount(userId);
    if (!store.insertRequest(userId, user.getOrganizationId(), purpose, text, actor.id())) {
      throw new ConflictException(
          "Für dieses Konto liegt bereits ein Antrag auf Anhaltung der Löschung vor");
    }
    boolean extension = store.findHold(userId).filter(Hold::running).isPresent();
    holdCounts.count(user.getOrganizationId(), purpose, ChatCleanupHoldTransition.REQUESTED);
    Map<String, Object> after = new LinkedHashMap<>();
    after.put("purpose", purpose.name());
    after.put("extension", extension);
    recordAdminAct(
        actor, user, AuditEventType.LOCKED_ACCOUNT_CHAT_CLEANUP_HOLD_REQUESTED, null, after);
    return stateOf(actor.organizationId(), userId);
  }

  /**
   * Approves the pending request by a second administrator: the hold takes effect, or the running
   * hold is extended, until now plus the maximum duration in force.
   *
   * @throws NotFoundException without a pending request
   * @throws ValidationException when {@code actor} filed the request (Selbstfreigabe)
   */
  @Transactional
  public LockedAccountChatCleanup approveHold(CurrentUser actor, UUID userId) {
    User user = localUsers.get(actor.organizationId(), userId).user();
    store.lockAccount(userId);
    HoldRequest request =
        store.findRequest(userId).orElseThrow(LockedAccountChatCleanupService::noRequest);
    if (actor.id().equals(request.requestedBy())) {
      throw new ValidationException(
          "Selbstfreigabe ist nicht zulässig: Eine zweite Person der Systemverwaltung gibt die"
              + " Anhaltung frei");
    }
    Optional<Hold> hold = store.findHold(userId);
    if (hold.isPresent() && !hold.get().running()) {
      endExpired(
          new ExpiredHold(
              userId, user.getOrganizationId(), hold.get().purpose(), hold.get().heldUntil()),
          databaseClock.now());
    }
    boolean extension = hold.filter(Hold::running).isPresent();
    store.deleteRequest(userId);
    Instant heldUntil =
        store.placeHold(
            userId,
            user.getOrganizationId(),
            request.purpose(),
            request.reason(),
            holdProperties.holdMaxDays());
    holdCounts.count(
        user.getOrganizationId(),
        request.purpose(),
        extension ? ChatCleanupHoldTransition.EXTENDED : ChatCleanupHoldTransition.APPROVED);
    Map<String, Object> after = new LinkedHashMap<>();
    after.put("purpose", request.purpose().name());
    after.put("heldUntil", heldUntil.toString());
    recordAdminAct(
        actor,
        user,
        extension
            ? AuditEventType.LOCKED_ACCOUNT_CHAT_CLEANUP_HOLD_EXTENDED
            : AuditEventType.LOCKED_ACCOUNT_CHAT_CLEANUP_HELD,
        null,
        after);
    return stateOf(actor.organizationId(), userId);
  }

  /**
   * Drops the pending request; any administrator may, the requesting one included (then it is
   * withdrawn), since dropping it holds nothing. A running hold stays until its end.
   *
   * @throws NotFoundException without a pending request
   */
  @Transactional
  public LockedAccountChatCleanup rejectHold(CurrentUser actor, UUID userId) {
    User user = localUsers.get(actor.organizationId(), userId).user();
    store.lockAccount(userId);
    HoldRequest request =
        store.findRequest(userId).orElseThrow(LockedAccountChatCleanupService::noRequest);
    store.deleteRequest(userId);
    holdCounts.count(
        user.getOrganizationId(), request.purpose(), ChatCleanupHoldTransition.REJECTED);
    Map<String, Object> after = new LinkedHashMap<>();
    after.put("purpose", request.purpose().name());
    after.put("cause", actor.id().equals(request.requestedBy()) ? "WITHDRAWN" : "REJECTED");
    recordAdminAct(
        actor, user, AuditEventType.LOCKED_ACCOUNT_CHAT_CLEANUP_HOLD_REJECTED, null, after);
    return stateOf(actor.organizationId(), userId);
  }

  /** Lifts the hold before its end; audited only when there was one. */
  @Transactional
  public LockedAccountChatCleanup resume(CurrentUser actor, UUID userId) {
    User user = localUsers.get(actor.organizationId(), userId).user();
    store.lockAccount(userId);
    if (store.resume(userId)) {
      recordAdminAct(actor, user, AuditEventType.LOCKED_ACCOUNT_CHAT_CLEANUP_RESUMED, null, null);
    }
    return stateOf(actor.organizationId(), userId);
  }

  /**
   * The nightly upkeep of the holds, before the deletion: bounds every hold placed before holds
   * ended by themselves, ends every hold whose end ran out and drops every request unanswered for
   * {@link #REQUEST_VALID_FOR}. Each end and each drop in its own transaction with its audit entry;
   * a failure is logged and leaves the rest to the next run.
   */
  void maintainHolds(Instant now) {
    try {
      int bounded = store.bound(holdProperties.holdMaxDays());
      if (bounded > 0) {
        log.info(
            "Locked-account chat cleanup: {} hold(s) without an end now end in {} days",
            bounded,
            holdProperties.holdMaxDays());
      }
      for (ExpiredHold hold : store.findExpired(now, MAX_HOLDS_PER_RUN)) {
        transactionTemplate.executeWithoutResult(
            status -> {
              store.lockAccount(hold.userId());
              endExpired(hold, now);
            });
      }
      Instant before = now.minus(REQUEST_VALID_FOR);
      for (HoldRequest request : store.findStaleRequests(before, MAX_HOLDS_PER_RUN)) {
        transactionTemplate.executeWithoutResult(
            status -> {
              store.lockAccount(request.userId());
              if (store.deleteStaleRequest(request.userId(), before)) {
                holdCounts.count(
                    request.organizationId(),
                    request.purpose(),
                    ChatCleanupHoldTransition.REJECTED);
                Map<String, Object> after = new LinkedHashMap<>();
                after.put("purpose", request.purpose().name());
                after.put("cause", "UNANSWERED");
                recordSystemAct(
                    request.organizationId(),
                    request.userId(),
                    AuditEventType.LOCKED_ACCOUNT_CHAT_CLEANUP_HOLD_REJECTED,
                    null,
                    after);
              }
            });
      }
    } catch (RuntimeException e) {
      log.error(
          "Locked-account chat cleanup could not keep up the holds ({}); the next run continues",
          e.getClass().getSimpleName());
    }
  }

  /** Ends {@code hold} if it still ran out at {@code now}, with its audit entry and count. */
  private void endExpired(ExpiredHold hold, Instant now) {
    if (!store.endExpired(hold.userId(), now)) {
      return;
    }
    Map<String, Object> before = new LinkedHashMap<>();
    if (hold.purpose() != null) {
      holdCounts.count(hold.organizationId(), hold.purpose(), ChatCleanupHoldTransition.EXPIRED);
      before.put("purpose", hold.purpose().name());
    }
    before.put("heldUntil", hold.heldUntil().toString());
    recordSystemAct(
        hold.organizationId(),
        hold.userId(),
        AuditEventType.LOCKED_ACCOUNT_CHAT_CLEANUP_HOLD_EXPIRED,
        before,
        null);
  }

  private static String validReason(ChatCleanupHoldPurpose purpose, String reason) {
    if (purpose == null) {
      throw new ValidationException("Ein Zweck aus der vorgegebenen Liste ist Pflicht");
    }
    String text = reason == null ? "" : reason.strip();
    if (text.length() < MIN_REASON_LENGTH || text.length() > MAX_REASON_LENGTH) {
      throw new ValidationException(
          "Die Begründung umfasst " + MIN_REASON_LENGTH + " bis " + MAX_REASON_LENGTH + " Zeichen");
    }
    return text;
  }

  private static NotFoundException noRequest() {
    return new NotFoundException("Für dieses Konto liegt kein Antrag auf Anhaltung vor");
  }

  private LockedAccountChatCleanup stateOf(LocalCredentials credentials) {
    LockedAccountChatCleanupPolicy policy = settings.current();
    int days = policy.deleteAfterDays();
    int maxDays = holdProperties.holdMaxDays();
    UUID userId = credentials.getUserId();
    LockedAccountChatCleanup.Hold hold =
        store
            .findHold(userId)
            .filter(Hold::running)
            .map(
                h ->
                    new LockedAccountChatCleanup.Hold(
                        h.purpose(), h.reason(), h.heldSince(), h.heldUntil()))
            .orElse(null);
    boolean held = hold != null;
    PendingRequest pending =
        store
            .findRequest(userId)
            .map(
                r ->
                    new PendingRequest(
                        r.purpose(), r.reason(), r.requestedAt(), r.requestedBy(), held))
            .orElse(null);
    ChatCleanupAfterLockStatus status;
    Instant earliest = null;
    if (!policy.enabled()) {
      status = ChatCleanupAfterLockStatus.SWITCHED_OFF;
    } else if (held) {
      status = ChatCleanupAfterLockStatus.HELD;
    } else if (credentials.getLockedReason() != LockReason.ADMIN
        || credentials.getLockedAt() == null) {
      status = ChatCleanupAfterLockStatus.NOT_LOCKED;
    } else {
      status = ChatCleanupAfterLockStatus.SCHEDULED;
      earliest = dates.dueAt(credentials.getLockedAt(), policy);
    }
    return new LockedAccountChatCleanup(status, held, days, maxDays, earliest, hold, pending);
  }

  private void recordDeletion(DueAccount account) {
    Map<String, Object> before = new LinkedHashMap<>();
    before.put("periodStart", account.periodStart().toString());
    before.put("lockReason", LockReason.ADMIN.name());
    audit.recordSystemProcessAction(
        AuditEvent.builder()
            .organizationId(account.organizationId())
            .actorRef(SYSTEM_ACTOR)
            .type(AuditEventType.LOCKED_ACCOUNT_CHATS_DELETED)
            .object(
                AuditObjectType.USER_ACCOUNT,
                audit.pseudonymFor(account.userId(), account.organizationId()),
                null)
            .subject(AuditSubjectKind.USER, account.userId())
            .before(before)
            .outcome(AuditOutcome.SUCCESS)
            .build());
  }

  private void recordAdminAct(
      CurrentUser actor,
      User subject,
      AuditEventType type,
      Map<String, Object> before,
      Map<String, Object> after) {
    audit.recordUserActionOnSubject(
        AuditEvent.builder()
            .organizationId(subject.getOrganizationId())
            .actor(actor.id())
            .type(type)
            .object(
                AuditObjectType.USER_ACCOUNT,
                audit.pseudonymFor(subject.getId(), subject.getOrganizationId()),
                null)
            .subject(AuditSubjectKind.USER, subject.getId())
            .before(before)
            .after(after)
            .outcome(AuditOutcome.SUCCESS)
            .build());
  }

  private void recordSystemAct(
      UUID organizationId,
      UUID userId,
      AuditEventType type,
      Map<String, Object> before,
      Map<String, Object> after) {
    audit.recordSystemProcessAction(
        AuditEvent.builder()
            .organizationId(organizationId)
            .actorRef(SYSTEM_ACTOR)
            .type(type)
            .object(AuditObjectType.USER_ACCOUNT, audit.pseudonymFor(userId, organizationId), null)
            .subject(AuditSubjectKind.USER, userId)
            .before(before)
            .after(after)
            .outcome(AuditOutcome.SUCCESS)
            .build());
  }

  /** What one run did, as totals; {@code aborted} when it could not even read the due accounts. */
  public record Result(int deleted, int accounts, int failed, boolean aborted) {
    static final Result NONE = new Result(0, 0, 0, false);
  }
}
