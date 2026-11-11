package io.opaa.chat;

import io.opaa.common.ByteSizes;
import io.opaa.common.PayloadTooLargeException;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The storage quotas of chat attachments ({@link ChatAttachmentProperties}): per account over all
 * of its attachments, a lower one per account over those not yet bound to a chat, and optionally
 * per organization. Every row counts by its {@code size_bytes} until it is deleted. {@link #admit}
 * checks and stores under a transaction-scoped advisory lock - the organization's while its quota
 * is set, the account's otherwise - so concurrent uploads on any instance never overshoot.
 */
@Component
class ChatAttachmentQuota {

  private final ChatAttachmentRepository repository;
  private final ChatAttachmentProperties properties;
  private final TransactionTemplate transaction;

  ChatAttachmentQuota(
      ChatAttachmentRepository repository,
      ChatAttachmentProperties properties,
      PlatformTransactionManager transactionManager) {
    this.repository = repository;
    this.properties = properties;
    this.transaction = new TransactionTemplate(transactionManager);
    this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
  }

  /**
   * Throws 413 when {@code bytes} more of {@code authorId} in {@code organizationId} would exceed a
   * quota. Without the lock of {@link #admit} this is advisory only: a cheap early rejection.
   */
  void requireRoom(UUID organizationId, UUID authorId, long bytes) {
    long accountQuota = properties.accountQuotaBytes();
    if (accountQuota > 0) {
      long used = repository.sumSizeByAuthorId(authorId);
      if (used + bytes > accountQuota) {
        throw new PayloadTooLargeException(
            "Das Speicherkontingent Ihrer Chat-Anhänge ist erschöpft ("
                + ByteSizes.format(used)
                + " von "
                + ByteSizes.format(accountQuota)
                + " belegt). Bitte nicht mehr benötigte Anhänge oder Chats löschen.");
      }
    }
    long unboundQuota = properties.unboundQuotaBytes();
    if (unboundQuota > 0) {
      long unbound = repository.sumUnboundSizeByAuthorId(authorId);
      if (unbound + bytes > unboundQuota) {
        throw new PayloadTooLargeException(
            "Ihre noch nicht gesendeten Anhänge belegen bereits "
                + ByteSizes.format(unbound)
                + " von "
                + ByteSizes.format(unboundQuota)
                + ". Bitte zuerst eine Frage mit ihnen senden oder nicht benötigte Anhänge"
                + " entfernen.");
      }
    }
    long organizationQuota = properties.organizationQuotaBytes();
    if (organizationQuota > 0
        && repository.sumSizeByOrganizationId(organizationId) + bytes > organizationQuota) {
      throw new PayloadTooLargeException(
          "Der Speicher für Chat-Anhänge Ihrer Organisation ist erschöpft. Bitte nicht mehr"
              + " benötigte Anhänge oder Chats löschen oder die Systemverwaltung informieren.");
    }
  }

  /**
   * Checks {@link #requireRoom} and runs {@code store}, which writes the row of {@code bytes}, in
   * one transaction (joining the caller's, if any) under the intake lock; the row commits with the
   * check, and a rejected intake writes nothing.
   */
  <T> T admit(UUID organizationId, UUID authorId, long bytes, Supplier<T> store) {
    return transaction.execute(
        status -> {
          repository.lockIntake(
              properties.organizationQuotaBytes() > 0 ? organizationId : authorId);
          requireRoom(organizationId, authorId, bytes);
          return store.get();
        });
  }
}
