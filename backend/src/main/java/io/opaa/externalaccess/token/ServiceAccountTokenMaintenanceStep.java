package io.opaa.externalaccess.token;

import io.opaa.account.LocalAccountMaintenanceStep;
import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.audit.AuditRetentionSettingsService;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The daily pass over the tokens and consents of service accounts: it records every token that ran
 * out on its own with {@code API_TOKEN_EXPIRED}, and removes dead rows once the audit retention has
 * run out over them - the two jobs {@link ExternalAccessTokenLapseStep} and {@link
 * ExternalAccessTokenRetentionStep} do for personal tokens, for the same reasons. A consent that
 * ran out gets one {@code SERVICE_ACCOUNT_CONSENT_EXPIRED}; one of a deleted library is ended and
 * kept.
 *
 * <p>The expiry is claimed by a conditional update in the transaction that writes the entry, so of
 * two runs exactly one writes it, and a failing token leaves no claim and is tried again the next
 * day. Like the personal steps it runs whatever the feature flag says: the flag holds no
 * Loeschfrist.
 */
@Component
@Order(44)
public class ServiceAccountTokenMaintenanceStep implements LocalAccountMaintenanceStep {

  private static final Logger log =
      LoggerFactory.getLogger(ServiceAccountTokenMaintenanceStep.class);

  private final ServiceAccountTokenRepository tokens;
  private final ServiceAccountLibraryConsentRepository consents;
  private final ServiceAccountRepository accounts;
  private final KnowledgeLibraryRepository libraries;
  private final AuditEventRecorder audit;
  private final AuditRetentionSettingsService retention;
  private final TransactionOperations transactions;

  public ServiceAccountTokenMaintenanceStep(
      ServiceAccountTokenRepository tokens,
      ServiceAccountLibraryConsentRepository consents,
      ServiceAccountRepository accounts,
      KnowledgeLibraryRepository libraries,
      AuditEventRecorder audit,
      AuditRetentionSettingsService retention,
      PlatformTransactionManager transactionManager) {
    this.tokens = tokens;
    this.consents = consents;
    this.accounts = accounts;
    this.libraries = libraries;
    this.audit = audit;
    this.retention = retention;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  @Override
  public String name() {
    return "service-account-token-maintenance";
  }

  @Override
  public void run(Instant now) {
    int recorded = 0;
    for (UUID tokenId : tokens.findLapsedIds(now)) {
      try {
        if (Boolean.TRUE.equals(transactions.execute(status -> recordLapse(tokenId, now)))) {
          recorded++;
        }
      } catch (RuntimeException e) {
        log.error(
            "Recording the lapse of service account token {} failed; it stays pending", tokenId, e);
      }
    }
    int consentsEnded = endConsentsOfDeletedLibraries(now);
    for (ServiceAccountLibraryConsent consent : consents.findRunOutUnrecorded(now)) {
      try {
        if (Boolean.TRUE.equals(transactions.execute(status -> recordExpiry(consent, now)))) {
          recorded++;
        }
      } catch (RuntimeException e) {
        log.error(
            "Recording the expiry of service account consent {} failed; it stays pending",
            consent.getId(),
            e);
      }
    }
    Instant cutoff =
        now.atOffset(ZoneOffset.UTC).minusMonths(retention.currentRetentionMonths()).toInstant();
    Integer removed = transactions.execute(status -> tokens.deleteLapsedBefore(cutoff));
    if (recorded > 0 || consentsEnded > 0 || (removed != null && removed > 0)) {
      log.info(
          "Service accounts: {} expiry entries, {} consent(s) of deleted libraries ended,"
              + " {} token row(s) removed before {}",
          recorded,
          consentsEnded,
          removed,
          cutoff);
    }
  }

  private boolean recordLapse(UUID tokenId, Instant now) {
    ServiceAccount account = tokens.findAccountOfToken(tokenId).orElse(null);
    if (account == null || tokens.recordLapse(tokenId, now) == 0) {
      return false;
    }
    audit.recordSystemProcessAction(
        AuditEvent.builder()
            .organizationId(account.getOrganizationId())
            .actorRef(ExternalAccessTokenLapseService.SYSTEM_ACTOR)
            .type(AuditEventType.API_TOKEN_EXPIRED)
            .object(AuditObjectType.API_TOKEN, tokenId, null)
            .after(
                Map.of(
                    "reason",
                    ExternalAccessTokenRevocationReason.EXPIRED.name(),
                    "serviceAccountId",
                    account.getId().toString()))
            .outcome(AuditOutcome.SUCCESS)
            .build());
    return true;
  }

  private int endConsentsOfDeletedLibraries(Instant now) {
    List<UUID> named = consents.findOpenLibraryIds();
    if (named.isEmpty()) {
      return 0;
    }
    Set<UUID> existing =
        libraries.findAllById(named).stream()
            .map(KnowledgeLibrary::getId)
            .collect(Collectors.toSet());
    int ended = 0;
    for (UUID libraryId : named) {
      if (!existing.contains(libraryId)) {
        Integer count =
            transactions.execute(status -> consents.endForDeletedLibrary(libraryId, now));
        ended += count == null ? 0 : count;
      }
    }
    return ended;
  }

  private boolean recordExpiry(ServiceAccountLibraryConsent consent, Instant now) {
    ServiceAccount account = accounts.findById(consent.getServiceAccountId()).orElse(null);
    KnowledgeLibrary library = libraries.findById(consent.getLibraryId()).orElse(null);
    if (account == null || library == null || consents.claimExpiry(consent.getId(), now) == 0) {
      return false;
    }
    audit.recordSystemProcessAction(
        AuditEvent.builder()
            .organizationId(library.getOrganizationId())
            .actorRef(ExternalAccessTokenLapseService.SYSTEM_ACTOR)
            .type(AuditEventType.SERVICE_ACCOUNT_CONSENT_EXPIRED)
            .object(AuditObjectType.KNOWLEDGE_LIBRARY, library.getId(), library.auditName())
            .after(
                library.auditPayload(
                    Map.of(
                        "serviceAccountId", account.getId().toString(),
                        "expiresAt", consent.getExpiresAt().toString())))
            .outcome(AuditOutcome.SUCCESS)
            .build());
    return true;
  }
}
