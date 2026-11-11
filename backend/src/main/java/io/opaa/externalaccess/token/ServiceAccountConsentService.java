package io.opaa.externalaccess.token;

import io.opaa.api.types.AssetRole;
import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.asset.OwnerOnlyRule;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.CurrentUser;
import io.opaa.common.ConflictException;
import io.opaa.common.NotFoundException;
import io.opaa.common.ValidationException;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.knowledge.LibraryAccessService;
import io.opaa.library.ExternalAccessProperties;
import io.opaa.permission.SuccessionReachGuard;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The library administration's consent to a named service account (ADR-0035, Entscheidung 7) -
 * "Fachverfahren mit Zugriff". The bar is the one of the Fremdzugangsfreigabe ({@code
 * LibraryExternalAccessService}): {@link AssetRole#MANAGER} on the library or the system
 * administration, never for a private library, frozen against widening while the succession is
 * open. The Freigabe itself does not count here: a release for colleagues' tools does not say "may
 * feed a Fachverfahren".
 *
 * <p>Every change is historised: the open row is closed and the next one starts, nothing is
 * deleted. The consent takes effect per call ({@link ExternalAccessTokenScopeService}), so a
 * withdrawn or run-out consent leaves every token of the account with the next request.
 */
@Service
public class ServiceAccountConsentService {

  private final ServiceAccountLibraryConsentRepository consents;
  private final ServiceAccountRepository accounts;
  private final KnowledgeLibraryRepository libraries;
  private final LibraryAccessService access;
  private final OwnerOnlyRule ownerOnlyRule;
  private final SuccessionReachGuard successionGuard;
  private final ExternalAccessProperties properties;
  private final AuditEventRecorder audit;
  private final Clock clock;

  public ServiceAccountConsentService(
      ServiceAccountLibraryConsentRepository consents,
      ServiceAccountRepository accounts,
      KnowledgeLibraryRepository libraries,
      LibraryAccessService access,
      OwnerOnlyRule ownerOnlyRule,
      SuccessionReachGuard successionGuard,
      ExternalAccessProperties properties,
      AuditEventRecorder audit,
      Clock clock) {
    this.consents = consents;
    this.accounts = accounts;
    this.libraries = libraries;
    this.access = access;
    this.ownerOnlyRule = ownerOnlyRule;
    this.successionGuard = successionGuard;
    this.properties = properties;
    this.audit = audit;
    this.clock = clock;
  }

  /** The longest admissible consent in days - the bound of the Fremdzugangsfreigabe. */
  public int maxConsentDays() {
    return properties.maxReleaseDays();
  }

  /** The open consents of a library, in force or run out, by the name of their account. */
  @Transactional(readOnly = true)
  public List<LibraryConsent> list(CurrentUser actor, UUID libraryId) {
    KnowledgeLibrary library = requireManaged(actor, libraryId);
    List<ServiceAccountLibraryConsent> open =
        consents.findByLibraryIdAndEndedAtIsNull(library.getId());
    Map<UUID, ServiceAccount> byId =
        accounts
            .findAllById(
                open.stream().map(ServiceAccountLibraryConsent::getServiceAccountId).toList())
            .stream()
            .collect(Collectors.toMap(ServiceAccount::getId, Function.identity()));
    Instant now = clock.instant();
    return open.stream()
        .filter(consent -> byId.containsKey(consent.getServiceAccountId()))
        .map(
            consent ->
                new LibraryConsent(
                    consent, byId.get(consent.getServiceAccountId()), consent.isInForce(now)))
        .sorted(
            Comparator.comparing(
                    (LibraryConsent row) -> row.account().getName(), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(row -> row.account().getId()))
        .toList();
  }

  /** The active service accounts of the library's organization a consent may name. */
  @Transactional(readOnly = true)
  public List<ServiceAccount> choices(CurrentUser actor, UUID libraryId) {
    KnowledgeLibrary library = requireManaged(actor, libraryId);
    return accounts.findByOrganizationIdAndDeactivatedAtIsNull(library.getOrganizationId()).stream()
        .sorted(
            Comparator.comparing(ServiceAccount::getName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(ServiceAccount::getId))
        .toList();
  }

  /**
   * Consents the library to the account until {@code expiresAt}, or moves the end of a running
   * consent. The same end again changes nothing and writes nothing.
   */
  @Transactional
  public LibraryConsent set(
      CurrentUser actor, UUID libraryId, UUID serviceAccountId, Instant expiresAt) {
    KnowledgeLibrary library = requireManaged(actor, libraryId);
    ownerOnlyRule.requireShareable(library);
    ServiceAccount account = requireAccount(library, serviceAccountId);
    if (!account.isActive()) {
      throw new ConflictException("Einem deaktivierten Dienstkonto lässt sich nicht zustimmen");
    }
    Instant now = clock.instant();
    validateExpiry(expiresAt, now);
    ServiceAccountLibraryConsent open =
        consents
            .findByServiceAccountIdAndLibraryIdAndEndedAtIsNull(account.getId(), library.getId())
            .orElse(null);
    boolean running = open != null && open.isInForce(now);
    if (running && open.getExpiresAt().equals(expiresAt)) {
      return new LibraryConsent(open, account, true);
    }
    if (!running || expiresAt.isAfter(open.getExpiresAt())) {
      // ADR-0036, Entscheidung 6: no widening of reach while the succession is open; taking a
      // consent back and shortening a running one stay possible.
      successionGuard.requireAssetReachNotFrozen(
          KnowledgeLibrary.ASSET_TYPE, library.getId(), "Eine Zustimmung für ein Dienstkonto");
    }
    if (open != null) {
      open.end(ServiceAccountConsentEndReason.REPLACED, now);
      // Flushed before the next row: the open row of a pair is unique, and the insert must not
      // reach the database before the update that closes its predecessor.
      consents.saveAndFlush(open);
    }
    ServiceAccountLibraryConsent saved =
        consents.save(
            new ServiceAccountLibraryConsent(account.getId(), library.getId(), now, expiresAt));
    AuditEvent.Builder entry =
        AuditEvent.builder()
            .organizationId(actor.organizationId())
            .actor(actor.id())
            .type(
                running
                    ? AuditEventType.SERVICE_ACCOUNT_CONSENT_CHANGED
                    : AuditEventType.SERVICE_ACCOUNT_CONSENT_GRANTED)
            .object(AuditObjectType.KNOWLEDGE_LIBRARY, library.getId(), library.auditName())
            .after(
                library.auditPayload(
                    Map.of(
                        "serviceAccountId", account.getId().toString(),
                        "expiresAt", expiresAt.toString())))
            .outcome(AuditOutcome.SUCCESS);
    if (running) {
      entry.before(
          library.auditPayload(
              Map.of(
                  "serviceAccountId", account.getId().toString(),
                  "expiresAt", open.getExpiresAt().toString())));
    }
    audit.recordUserAction(entry.build());
    return new LibraryConsent(saved, account, true);
  }

  /**
   * Takes the consent back; the library leaves every token of the account with the next call.
   * Without an open consent nothing happens; one that had already run out is closed without an
   * entry, because it took nothing away any more.
   */
  @Transactional
  public void revoke(CurrentUser actor, UUID libraryId, UUID serviceAccountId) {
    KnowledgeLibrary library = requireManaged(actor, libraryId);
    accounts.lockByIdAndOrganizationId(serviceAccountId, library.getOrganizationId());
    ServiceAccountLibraryConsent open =
        consents
            .findByServiceAccountIdAndLibraryIdAndEndedAtIsNull(serviceAccountId, library.getId())
            .orElse(null);
    if (open == null) {
      return;
    }
    Instant now = clock.instant();
    boolean running = open.isInForce(now);
    open.end(ServiceAccountConsentEndReason.WITHDRAWN, now);
    consents.save(open);
    if (running) {
      audit.recordUserAction(
          AuditEvent.builder()
              .organizationId(actor.organizationId())
              .actor(actor.id())
              .type(AuditEventType.SERVICE_ACCOUNT_CONSENT_REVOKED)
              .object(AuditObjectType.KNOWLEDGE_LIBRARY, library.getId(), library.auditName())
              .after(library.auditPayload(Map.of("serviceAccountId", serviceAccountId.toString())))
              .outcome(AuditOutcome.SUCCESS)
              .build());
    }
  }

  private KnowledgeLibrary requireManaged(CurrentUser actor, UUID libraryId) {
    KnowledgeLibrary library = libraries.requireInOrganization(libraryId, actor.organizationId());
    access.requireRole(library, actor.id(), actor.isSystemAdmin(), AssetRole.MANAGER);
    return library;
  }

  /**
   * The account, its row locked until the transaction ends: every change and withdrawal of its
   * consents takes this lock first, so each reads the consents the one before it committed.
   */
  private ServiceAccount requireAccount(KnowledgeLibrary library, UUID serviceAccountId) {
    return accounts
        .lockByIdAndOrganizationId(serviceAccountId, library.getOrganizationId())
        .orElseThrow(() -> new NotFoundException("Dienstkonto nicht gefunden"));
  }

  private void validateExpiry(Instant expiresAt, Instant now) {
    if (expiresAt == null) {
      throw new ValidationException("Eine Zustimmung für ein Dienstkonto braucht ein Ablaufdatum");
    }
    if (!expiresAt.isAfter(now)) {
      throw new ValidationException("Das Ablaufdatum der Zustimmung muss in der Zukunft liegen");
    }
    if (expiresAt.isAfter(now.plus(Duration.ofDays(properties.maxReleaseDays())))) {
      throw new ValidationException(
          "Eine Zustimmung für ein Dienstkonto gilt höchstens "
              + properties.maxReleaseDays()
              + " Tage; danach muss sie bewusst erneuert werden");
    }
  }

  /** One open consent of a library with its account and whether it is in force right now. */
  public record LibraryConsent(
      ServiceAccountLibraryConsent consent, ServiceAccount account, boolean inForce) {}
}
