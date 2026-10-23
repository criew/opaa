package io.opaa.chat;

import io.opaa.account.LocalUserService;
import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.AuditSubjectKind;
import io.opaa.api.types.ChatCleanupAfterLockStatus;
import io.opaa.api.types.LockReason;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.LocalCredentials;
import io.opaa.auth.User;
import io.opaa.chat.LockedAccountChatCleanupStore.DueAccount;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Deletes the chats of local accounts locked by the administration once the period after the lock
 * ran out (docs/features/chat-list.md, "Löschung der Chats gesperrter Konten"), and holds or
 * resumes that deletion per account. A lock for inactivity or failed sign-ins starts nothing; an
 * account held or named by an open Klärung keeps its chats until that ends. One run deletes at most
 * {@link #MAX_CHATS_PER_RUN} chats in batches of their own transaction; the rest follows the next
 * night. The log names totals only, the audit one entry per account and run without a count.
 */
@Service
public class LockedAccountChatCleanupService {

  private static final Logger log = LoggerFactory.getLogger(LockedAccountChatCleanupService.class);

  static final String SYSTEM_ACTOR = "chat-locked-account-cleanup";
  static final int DELETE_BATCH_SIZE = 200;
  static final int MAX_CHATS_PER_RUN = 5_000;

  private final LockedAccountChatCleanupStore store;
  private final LockedAccountChatCleanupDates dates;
  private final LockedAccountChatCleanupProperties properties;
  private final ChatRepository chatRepository;
  private final LocalUserService localUsers;
  private final AuditEventRecorder audit;
  private final TransactionTemplate transactionTemplate;

  public LockedAccountChatCleanupService(
      LockedAccountChatCleanupStore store,
      LockedAccountChatCleanupDates dates,
      LockedAccountChatCleanupProperties properties,
      ChatRepository chatRepository,
      LocalUserService localUsers,
      AuditEventRecorder audit,
      PlatformTransactionManager transactionManager) {
    this.store = store;
    this.dates = dates;
    this.properties = properties;
    this.chatRepository = chatRepository;
    this.localUsers = localUsers;
    this.audit = audit;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
    if (properties.enabled()) {
      log.info(
          "Chats of local accounts locked by the administration are deleted {} days after the"
              + " lock",
          properties.deleteAfterDays());
    } else {
      log.info("Deletion of the chats of locked local accounts is switched off");
    }
  }

  /** One run at {@code now}, the database clock; the caller holds no transaction. */
  public Result runOnce(Instant now) {
    return runOnce(now, MAX_CHATS_PER_RUN);
  }

  Result runOnce(Instant now, int maxChats) {
    if (!properties.enabled()) {
      store.stopCounting();
      return Result.NONE;
    }
    if (store.startCountingIfStopped()) {
      return Result.NONE;
    }
    Instant cutoff = now.minus(properties.period());
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
    return new Result(deleted, accounts, failed, false);
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

  /** Holds the deletion for the account; audited only when the hold is new. */
  @Transactional
  public LockedAccountChatCleanup hold(CurrentUser actor, UUID userId) {
    User user = localUsers.get(actor.organizationId(), userId).user();
    store.lockAccount(userId);
    if (store.hold(userId, user.getOrganizationId())) {
      recordAdminAct(actor, user, AuditEventType.LOCKED_ACCOUNT_CHAT_CLEANUP_HELD);
    }
    return stateOf(actor.organizationId(), userId);
  }

  /** Lifts the hold; audited only when there was one. */
  @Transactional
  public LockedAccountChatCleanup resume(CurrentUser actor, UUID userId) {
    User user = localUsers.get(actor.organizationId(), userId).user();
    store.lockAccount(userId);
    if (store.resume(userId)) {
      recordAdminAct(actor, user, AuditEventType.LOCKED_ACCOUNT_CHAT_CLEANUP_RESUMED);
    }
    return stateOf(actor.organizationId(), userId);
  }

  private LockedAccountChatCleanup stateOf(LocalCredentials credentials) {
    int days = properties.deleteAfterDays();
    boolean held = store.isHeld(credentials.getUserId());
    if (!properties.enabled()) {
      return new LockedAccountChatCleanup(
          ChatCleanupAfterLockStatus.SWITCHED_OFF, held, days, null);
    }
    if (held) {
      return new LockedAccountChatCleanup(ChatCleanupAfterLockStatus.HELD, true, days, null);
    }
    if (credentials.getLockedReason() != LockReason.ADMIN || credentials.getLockedAt() == null) {
      return new LockedAccountChatCleanup(ChatCleanupAfterLockStatus.NOT_LOCKED, false, days, null);
    }
    return new LockedAccountChatCleanup(
        ChatCleanupAfterLockStatus.SCHEDULED, false, days, dates.dueAt(credentials.getLockedAt()));
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

  private void recordAdminAct(CurrentUser actor, User subject, AuditEventType type) {
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
            .outcome(AuditOutcome.SUCCESS)
            .build());
  }

  /** What one run did, as totals; {@code aborted} when it could not even read the due accounts. */
  public record Result(int deleted, int accounts, int failed, boolean aborted) {
    static final Result NONE = new Result(0, 0, 0, false);
  }
}
