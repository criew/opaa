package io.opaa.connection.consent;

import io.opaa.connection.token.ConnectionSecrets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Discards every source consent given for a library that was never created, once its waiting time
 * ({@link SourceConsentService#PENDING_LIFETIME}) is over, and revokes it at the provider after the
 * deletion committed. A failed run is caught up by the next one.
 */
@Component
public class PendingConsentSweep {

  private static final Logger log = LoggerFactory.getLogger(PendingConsentSweep.class);

  private final ConnectionSecrets secrets;
  private final TransactionTemplate transactions;

  PendingConsentSweep(ConnectionSecrets secrets, PlatformTransactionManager transactionManager) {
    this.secrets = secrets;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  @Scheduled(fixedDelayString = "PT15M", initialDelayString = "PT5M")
  void scheduled() {
    try {
      int discarded = sweep();
      if (discarded > 0) {
        log.info("Discarded {} pending source consents past their waiting time", discarded);
      }
    } catch (RuntimeException e) {
      log.warn("Pending source consents could not be swept; the next run tries again", e);
    }
  }

  /** Discards the expired pending consents now; returns how many went. */
  public int sweep() {
    Integer discarded = transactions.execute(status -> secrets.discardExpiredPending());
    return discarded == null ? 0 : discarded;
  }
}
