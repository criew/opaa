package io.opaa.permission;

import io.opaa.instance.ScheduledRunLocks;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Records every minute the local accounts whose expiry date has passed without any write - the one
 * change of an account's usability no trigger sees (ADR-0036, Entscheidung 8). Until it runs the
 * history still holds such an account usable: the Stichtagsauskunft may name it about a minute past
 * its expiry, but never cuts an access short. One instance at a time (ADR-0053); a second run finds
 * the expiry recorded already, because each account is re-evaluated under its own lock.
 */
@Component
public class AccountStateExpirySweep {

  /** Accounts recorded per run; the rest follow a minute later. */
  static final int MAX_ACCOUNTS_PER_RUN = 500;

  private static final Logger log = LoggerFactory.getLogger(AccountStateExpirySweep.class);

  private final AccountStateHistoryService history;

  AccountStateExpirySweep(AccountStateHistoryService history) {
    this.history = history;
  }

  @Scheduled(cron = "30 * * * * *")
  @SchedulerLock(
      name = "account-state-expiry-sweep",
      lockAtLeastFor = ScheduledRunLocks.AT_LEAST_FOR_MINUTE,
      lockAtMostFor = ScheduledRunLocks.AT_MOST_FOR_SHORT)
  public void recordReachedExpiries() {
    int recorded = history.recordReachedExpiries(MAX_ACCOUNTS_PER_RUN);
    if (recorded > 0) {
      log.info("Account state: recorded the reached expiry of {} local account(s)", recorded);
    }
  }
}
