package io.opaa.auth.oidc;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.AuditSubjectKind;
import io.opaa.api.types.ProviderType;
import io.opaa.api.types.SystemRole;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.LocalIssuer;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.auth.local.LocalAdminAvailabilityGuard;
import io.opaa.auth.local.LocalRefreshTokenRepository;
import io.opaa.auth.local.LocalTokenRevocationService;
import io.opaa.auth.local.RevocationReason;
import io.opaa.common.ConflictException;
import io.opaa.common.NotFoundException;
import io.opaa.common.ValidationException;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and changes the identity providers (#1329, ADR-0025) - the persistence and audit layer
 * {@code LlmModelService} is modelled after. Every write records one audit event ({@link
 * AuditEventType#OIDC_PROVIDER_CREATED} and siblings) and publishes {@link
 * OidcProvidersChangedEvent}, so {@link OidcProviderRegistry} rebuilds after the commit and a
 * change is effective without a restart.
 *
 * <p><b>Invariants enforced here, backed by the schema:</b> an issuer names exactly one provider
 * ({@code ux_oidc_providers_issuer_uri_normalized} - trailing slashes do not make a second one);
 * exactly one OIDC provider is the default while any exist ({@code
 * ux_oidc_providers_single_default}) - the directory provider the synchronisation is bound to - so
 * it can be neither disabled nor deleted while another OIDC provider exists, and a provider whose
 * keys cannot be fetched cannot take its place. The very first OIDC provider becomes the default
 * automatically.
 *
 * <p><b>ADR-0033, Entscheidung 4:</b> a state without any OIDC provider is allowed - the local
 * bootstrap administrator is the sign-in path that always remains. Disabling or deleting the
 * <em>last enabled</em> OIDC provider therefore needs the caller's {@code acknowledgeLastProvider}
 * and a login-capable local administrator, confirmed by {@link LocalAdminAvailabilityGuard} under
 * its lock. The one {@code LOCAL} row is no identity provider: never the default, not deletable,
 * its issuer fixed, its name the only editable field, no address check - and its {@code enabled} is
 * the switch of the local account management ({@code LOCAL_ACCOUNTS_ENABLED}/{@code _DISABLED});
 * switching off ends the sessions of every regular local account, system administrators excepted.
 *
 * <p>Every operator-entered address of an OIDC provider passes {@link OidcAddressPolicy} before the
 * row is written. Deleting a provider deletes no account: {@code users(subject, issuer)} keeps
 * every row, only the sign-in through that issuer stops (ADR-0025, Entscheidung 2).
 */
@Service
public class OidcProviderService {

  /** The 409 code when the last enabled provider is switched off without acknowledgement. */
  public static final String LAST_PROVIDER_ACKNOWLEDGEMENT_REQUIRED =
      "LAST_PROVIDER_ACKNOWLEDGEMENT_REQUIRED";

  private static final String OBJECT_LABEL_PREFIX = "Identitätsanbieter";
  private static final String ISSUER_LABEL = "Issuer-URI";
  private static final String JWK_SET_LABEL = "JWK-Set-URI";
  private static final String LOCAL_ROW_LABEL = "Zeile der lokalen Konten";

  private final OidcProviderRepository repository;
  private final UserRepository userRepository;
  private final OidcAddressPolicy addressPolicy;
  private final OidcProviderRegistry registry;
  private final LocalAdminAvailabilityGuard adminGuard;
  private final LocalTokenRevocationService revocation;
  private final LocalRefreshTokenRepository refreshTokens;
  private final AuditEventRecorder auditEventRecorder;
  private final ApplicationEventPublisher eventPublisher;
  private final Clock clock;

  public OidcProviderService(
      OidcProviderRepository repository,
      UserRepository userRepository,
      OidcAddressPolicy addressPolicy,
      OidcProviderRegistry registry,
      LocalAdminAvailabilityGuard adminGuard,
      LocalTokenRevocationService revocation,
      LocalRefreshTokenRepository refreshTokens,
      AuditEventRecorder auditEventRecorder,
      ApplicationEventPublisher eventPublisher,
      Clock clock) {
    this.repository = repository;
    this.userRepository = userRepository;
    this.addressPolicy = addressPolicy;
    this.registry = registry;
    this.adminGuard = adminGuard;
    this.revocation = revocation;
    this.refreshTokens = refreshTokens;
    this.auditEventRecorder = auditEventRecorder;
    this.eventPublisher = eventPublisher;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public List<OidcProvider> listProviders() {
    return repository.findAllByOrderBySortOrderAscDisplayNameAsc();
  }

  @Transactional(readOnly = true)
  public OidcProvider getProvider(UUID id) {
    return repository.findById(id).orElseThrow(() -> notFound(id));
  }

  @Transactional
  public OidcProvider createProvider(
      UUID organizationId, UUID actorUserId, OidcProviderDraft draft) {
    validate(draft, null);
    OidcProvider provider =
        new OidcProvider(
            draft.displayName(),
            draft.issuerUri(),
            draft.clientId(),
            draft.jwkSetUri(),
            draft.claimMapping());
    provider.setSortOrder((int) repository.count());
    if (repository.countByProviderType(ProviderType.OIDC) == 0) {
      provider.markDefault();
    }
    repository.save(provider);
    recordChange(
        organizationId,
        actorUserId,
        AuditEventType.OIDC_PROVIDER_CREATED,
        provider,
        null,
        auditState(provider));
    eventPublisher.publishEvent(new OidcProvidersChangedEvent());
    return provider;
  }

  /**
   * The issuer of a provider that already provisioned accounts cannot be changed (ADR-0025,
   * Entscheidung 2): the identity is {@code (issuer, subject)} and there is no merging, so every
   * account of the old issuer would silently become a new, empty account on its next sign-in. The
   * comparison is byte for byte, like the token check and {@code users.issuer}: a trailing slash
   * added or removed is an issuer change too. For the LOCAL row only the name is editable.
   */
  @Transactional
  public OidcProvider updateProvider(
      UUID organizationId, UUID actorUserId, UUID id, OidcProviderDraft draft) {
    OidcProvider provider = repository.findById(id).orElseThrow(() -> notFound(id));
    if (provider.isLocal()) {
      return renameLocalRow(organizationId, actorUserId, provider, draft);
    }
    validate(draft, provider.getId());
    if (!draft.issuerUri().trim().equals(provider.getIssuerUri())) {
      long accounts = userRepository.countByIssuer(provider.getIssuerUri());
      if (accounts > 0) {
        throw new ConflictException(
            "Die Issuer-URI kann nicht geändert werden: Über diesen Anbieter wurden bereits "
                + accounts
                + " Konten angelegt, die ihre Identität verlieren würden. Legen Sie für den neuen"
                + " Issuer einen weiteren Anbieter an.");
      }
    }
    Map<String, Object> before = auditState(provider);
    provider.replaceDetails(
        draft.displayName(),
        draft.issuerUri(),
        draft.clientId(),
        draft.jwkSetUri(),
        draft.claimMapping());
    repository.save(provider);
    recordChange(
        organizationId,
        actorUserId,
        AuditEventType.OIDC_PROVIDER_CHANGED,
        provider,
        before,
        auditState(provider));
    eventPublisher.publishEvent(new OidcProvidersChangedEvent());
    return provider;
  }

  private OidcProvider renameLocalRow(
      UUID organizationId, UUID actorUserId, OidcProvider local, OidcProviderDraft draft) {
    if (draft.issuerUri() == null || !LocalIssuer.URN.equals(draft.issuerUri().trim())) {
      throw new ConflictException(
          "Die Issuer-URI der lokalen Konten ist fest ("
              + LocalIssuer.URN
              + ") und nicht änderbar.");
    }
    if (draft.displayName() == null || draft.displayName().isBlank()) {
      throw new ValidationException("Der Anzeigename darf nicht leer sein.");
    }
    if (draft.displayName().trim().equals(local.getDisplayName())) {
      return local;
    }
    Map<String, Object> before = Map.of("displayName", local.getDisplayName());
    local.rename(draft.displayName());
    repository.save(local);
    recordChange(
        organizationId,
        actorUserId,
        AuditEventType.OIDC_PROVIDER_CHANGED,
        local,
        before,
        Map.of("displayName", local.getDisplayName()));
    eventPublisher.publishEvent(new OidcProvidersChangedEvent());
    return local;
  }

  /** {@link #deleteProvider(UUID, UUID, UUID, boolean)} without the acknowledgement. */
  @Transactional
  public void deleteProvider(UUID organizationId, UUID actorUserId, UUID id) {
    deleteProvider(organizationId, actorUserId, id, false);
  }

  /**
   * Accounts provisioned through the deleted provider stay ({@code users} carries no FK to this
   * table); only their sign-in stops until a provider with the same issuer exists again. The LOCAL
   * row is never deleted; the last enabled OIDC provider only with {@code acknowledgeLastProvider}
   * and a login-capable local administrator.
   */
  @Transactional
  public void deleteProvider(
      UUID organizationId, UUID actorUserId, UUID id, boolean acknowledgeLastProvider) {
    OidcProvider provider = repository.findById(id).orElseThrow(() -> notFound(id));
    if (provider.isLocal()) {
      throw new ConflictException(
          "Die Zeile der lokalen Konten kann nicht gelöscht werden. Ihr Schalter ist"
              + " Aktivieren/Deaktivieren.");
    }
    if (provider.isDefaultProvider()
        && repository.existsByProviderTypeAndIdNot(ProviderType.OIDC, provider.getId())) {
      throw new ConflictException(
          "Der Standardanbieter kann nicht gelöscht werden. Machen Sie zuerst einen anderen"
              + " Anbieter zum Standard.");
    }
    if (provider.isEnabled() && isLastEnabledOidcProvider(provider)) {
      requireLastProviderAcknowledged(acknowledgeLastProvider, "gelöscht");
      adminGuard.requireLoginCapableAdminWithoutProvider(organizationId, provider.getId());
    }
    Map<String, Object> before = auditState(provider);
    repository.delete(provider);
    auditEventRecorder.recordUserAction(
        AuditEvent.builder()
            .organizationId(organizationId)
            .actor(actorUserId)
            .type(AuditEventType.OIDC_PROVIDER_DELETED)
            .object(AuditObjectType.SYSTEM_SETTING, provider.getId(), objectLabel(provider))
            .before(before)
            .outcome(AuditOutcome.SUCCESS)
            .build());
    eventPublisher.publishEvent(new OidcProvidersChangedEvent());
  }

  /** {@link #setEnabled(UUID, UUID, UUID, boolean, boolean)} without the acknowledgement. */
  @Transactional
  public OidcProvider setEnabled(UUID organizationId, UUID actorUserId, UUID id, boolean enabled) {
    return setEnabled(organizationId, actorUserId, id, enabled, false);
  }

  /**
   * A disabled provider's tokens are refused with the registry's next rebuild - after commit. For
   * the LOCAL row this is the switch of the local account management; for the last enabled OIDC
   * provider the switch-off needs {@code acknowledgeLastProvider} and a login-capable local
   * administrator.
   */
  @Transactional
  public OidcProvider setEnabled(
      UUID organizationId,
      UUID actorUserId,
      UUID id,
      boolean enabled,
      boolean acknowledgeLastProvider) {
    OidcProvider provider = repository.findById(id).orElseThrow(() -> notFound(id));
    if (provider.isEnabled() == enabled) {
      return provider;
    }
    if (provider.isLocal()) {
      return switchLocalAccounts(organizationId, actorUserId, provider, enabled);
    }
    if (!enabled) {
      if (provider.isDefaultProvider()
          && repository.existsByProviderTypeAndEnabledTrueAndIdNot(
              ProviderType.OIDC, provider.getId())) {
        throw new ConflictException(
            "Der Standardanbieter kann nicht deaktiviert werden. Machen Sie zuerst einen anderen"
                + " Anbieter zum Standard.");
      }
      if (isLastEnabledOidcProvider(provider)) {
        requireLastProviderAcknowledged(acknowledgeLastProvider, "deaktiviert");
        adminGuard.requireLoginCapableAdminWithoutProvider(organizationId, provider.getId());
      }
    }
    if (enabled) {
      provider.enable();
    } else {
      provider.disable();
    }
    repository.save(provider);
    recordChange(
        organizationId,
        actorUserId,
        enabled ? AuditEventType.OIDC_PROVIDER_ENABLED : AuditEventType.OIDC_PROVIDER_DISABLED,
        provider,
        Map.of("enabled", !enabled),
        Map.of("enabled", enabled));
    eventPublisher.publishEvent(new OidcProvidersChangedEvent());
    return provider;
  }

  /**
   * The management switch (ADR-0033, Entscheidung 4). Switching off ends every session of every
   * regular local account at once - access tokens through {@code password_invalidated_before},
   * refresh families through {@link RevocationReason#ADMIN} - and audits each as a foreign-caused
   * {@code LOCAL_SESSION_REVOKED}; local {@code SYSTEM_ADMIN} accounts keep signing in.
   */
  private OidcProvider switchLocalAccounts(
      UUID organizationId, UUID actorUserId, OidcProvider local, boolean enabled) {
    Map<String, Object> after = new HashMap<>();
    after.put("enabled", enabled);
    if (enabled) {
      local.enable();
    } else {
      local.disable();
      after.put("revokedAccounts", revokeRegularLocalSessions(organizationId, actorUserId));
    }
    repository.save(local);
    recordChange(
        organizationId,
        actorUserId,
        enabled ? AuditEventType.LOCAL_ACCOUNTS_ENABLED : AuditEventType.LOCAL_ACCOUNTS_DISABLED,
        local,
        Map.of("enabled", !enabled),
        after);
    eventPublisher.publishEvent(new OidcProvidersChangedEvent());
    return local;
  }

  private int revokeRegularLocalSessions(UUID organizationId, UUID actorUserId) {
    Instant now = clock.instant();
    List<User> regular =
        userRepository.findByIssuerAndSystemRoleNot(LocalIssuer.URN, SystemRole.SYSTEM_ADMIN);
    for (User user : regular) {
      revocation.invalidateSessionsIssuedBefore(user.getId());
      refreshTokens.revokeAllForUser(user.getId(), RevocationReason.ADMIN, now);
      UUID pseudonym = auditEventRecorder.pseudonymFor(user.getId(), user.getOrganizationId());
      auditEventRecorder.recordUserActionOnSubject(
          AuditEvent.builder()
              .organizationId(organizationId)
              .actor(actorUserId)
              .type(AuditEventType.LOCAL_SESSION_REVOKED)
              .object(AuditObjectType.USER_ACCOUNT, pseudonym, null)
              .subject(AuditSubjectKind.USER, user.getId())
              .after(Map.of("reason", AuditEventType.LOCAL_ACCOUNTS_DISABLED.name()))
              .outcome(AuditOutcome.SUCCESS)
              .build());
    }
    return regular.size();
  }

  private boolean isLastEnabledOidcProvider(OidcProvider provider) {
    return !repository.existsByProviderTypeAndEnabledTrueAndIdNot(
        ProviderType.OIDC, provider.getId());
  }

  private static void requireLastProviderAcknowledged(boolean acknowledged, String verb) {
    if (!acknowledged) {
      throw new ConflictException(
          "Dies ist der letzte aktivierte Identitätsanbieter. Wird er "
              + verb
              + ", können sich nur noch lokale Konten anmelden. Bestätigen Sie das ausdrücklich"
              + " (acknowledgeLastProvider) - vorausgesetzt, ein lokales Systemverwalterkonto mit"
              + " Passwort ist eingerichtet.",
          LAST_PROVIDER_ACKNOWLEDGEMENT_REQUIRED);
    }
  }

  /**
   * Moves the default flag. The previous default is flushed before the new one is written: {@code
   * ux_oidc_providers_single_default} is a plain partial unique index, and Hibernate's flush order
   * could otherwise write the new {@code true} before the old {@code false} - the same reasoning as
   * {@code LlmModelService#activateModel}. The LOCAL row is never the default.
   */
  @Transactional
  public OidcProvider makeDefault(UUID organizationId, UUID actorUserId, UUID id) {
    OidcProvider provider = repository.findById(id).orElseThrow(() -> notFound(id));
    if (provider.isDefaultProvider()) {
      return provider;
    }
    if (provider.isLocal()) {
      throw new ConflictException(
          "Die Zeile der lokalen Konten kann nicht Standardanbieter werden; der Standard ist der"
              + " Verzeichnis-Anbieter.");
    }
    if (!provider.isEnabled()) {
      throw new ConflictException(
          "Ein deaktivierter Anbieter kann nicht Standardanbieter werden. Aktivieren Sie ihn"
              + " zuerst.");
    }
    OidcProviderRegistry.Health health = registry.healthOf(provider.getId());
    if (!health.ready()) {
      throw new ConflictException(
          "Ein Anbieter, dessen Schlüssel nicht abrufbar sind, kann nicht Standardanbieter werden"
              + (health.message() == null ? "" : ": " + health.message())
              + ". Beheben Sie die Verbindung zuerst - der Standardanbieter ist der einzige, der"
              + " weder deaktiviert noch gelöscht werden kann, solange ein weiterer Anbieter"
              + " existiert.");
    }
    Optional<OidcProvider> previous = repository.findByDefaultProviderTrue();
    if (previous.isPresent()) {
      OidcProvider old = previous.get();
      old.clearDefault();
      repository.saveAndFlush(old);
      recordChange(
          organizationId,
          actorUserId,
          AuditEventType.OIDC_PROVIDER_CHANGED,
          old,
          Map.of("isDefault", true),
          Map.of("isDefault", false));
    }
    provider.markDefault();
    repository.save(provider);
    recordChange(
        organizationId,
        actorUserId,
        AuditEventType.OIDC_PROVIDER_CHANGED,
        provider,
        Map.of("isDefault", false),
        Map.of("isDefault", true));
    eventPublisher.publishEvent(new OidcProvidersChangedEvent());
    return provider;
  }

  /**
   * Assigns {@code sortOrder} 0..n-1 in the order of {@code orderedIds}, which must name every
   * provider exactly once - a partial order would leave the sign-in page with an undefined gap.
   */
  @Transactional
  public List<OidcProvider> reorder(UUID organizationId, UUID actorUserId, List<UUID> orderedIds) {
    List<OidcProvider> all = repository.findAllByOrderBySortOrderAscDisplayNameAsc();
    Set<UUID> known = new HashSet<>();
    all.forEach(provider -> known.add(provider.getId()));
    if (orderedIds == null
        || orderedIds.size() != known.size()
        || !new HashSet<>(orderedIds).equals(known)) {
      throw new ValidationException("Die Reihenfolge muss jeden Anbieter genau einmal nennen.");
    }
    Map<UUID, OidcProvider> byId = new HashMap<>();
    all.forEach(provider -> byId.put(provider.getId(), provider));
    boolean changed = false;
    for (int position = 0; position < orderedIds.size(); position++) {
      OidcProvider provider = byId.get(orderedIds.get(position));
      if (provider.getSortOrder() != position) {
        Map<String, Object> before = Map.of("sortOrder", provider.getSortOrder());
        provider.setSortOrder(position);
        repository.save(provider);
        recordChange(
            organizationId,
            actorUserId,
            AuditEventType.OIDC_PROVIDER_CHANGED,
            provider,
            before,
            Map.of("sortOrder", position));
        changed = true;
      }
    }
    if (changed) {
      eventPublisher.publishEvent(new OidcProvidersChangedEvent());
    }
    return repository.findAllByOrderBySortOrderAscDisplayNameAsc();
  }

  /**
   * Shape, SSRF policy and uniqueness of the issuer - {@code selfId} excludes the row being updated
   * from the uniqueness check, which ignores trailing slashes like the index behind it.
   */
  private void validate(OidcProviderDraft draft, UUID selfId) {
    if (draft.displayName() == null || draft.displayName().isBlank()) {
      throw new ValidationException("Der Anzeigename darf nicht leer sein.");
    }
    if (draft.clientId() == null || draft.clientId().isBlank()) {
      throw new ValidationException("Die Client-ID darf nicht leer sein.");
    }
    String issuer = OidcIssuerUris.normalize(draft.issuerUri());
    // the shape check is the policy's too, but the service must not depend on it for the
    // invariant that only http(s) issuers ever reach the row
    OidcIssuerUris.requireHttpUri(issuer, ISSUER_LABEL);
    addressPolicy.requireAllowed(issuer, ISSUER_LABEL);
    if (issuer.isEmpty()) {
      throw new ValidationException(ISSUER_LABEL + " darf nicht leer sein.");
    }
    if (draft.jwkSetUri() != null && !draft.jwkSetUri().isBlank()) {
      addressPolicy.requireAllowed(draft.jwkSetUri().trim(), JWK_SET_LABEL);
    }
    repository
        .findByNormalizedIssuerUri(issuer)
        .filter(other -> !other.getId().equals(selfId))
        .ifPresent(
            other -> {
              throw new ConflictException(
                  "Für diesen Issuer existiert bereits der Anbieter „"
                      + other.getDisplayName()
                      + "“. Ein Issuer kann nur einem Anbieter zugeordnet sein.");
            });
  }

  private void recordChange(
      UUID organizationId,
      UUID actorUserId,
      AuditEventType eventType,
      OidcProvider provider,
      Map<String, Object> before,
      Map<String, Object> after) {
    auditEventRecorder.recordUserAction(
        AuditEvent.builder()
            .organizationId(organizationId)
            .actor(actorUserId)
            .type(eventType)
            .object(AuditObjectType.SYSTEM_SETTING, provider.getId(), objectLabel(provider))
            .before(before)
            .after(after)
            .outcome(AuditOutcome.SUCCESS)
            .build());
  }

  /**
   * No secret to hide here - every field of a public client may appear in the log. An unset
   * optional field is left out rather than written as {@code null}, which {@link AuditEvent}'s
   * immutable maps refuse.
   */
  private static Map<String, Object> auditState(OidcProvider provider) {
    Map<String, Object> state = new HashMap<>();
    state.put("displayName", provider.getDisplayName());
    state.put("issuerUri", provider.getIssuerUri());
    putIfPresent(state, "clientId", provider.getClientId());
    putIfPresent(state, "jwkSetUri", provider.getJwkSetUri());
    state.put("enabled", provider.isEnabled());
    state.put("isDefault", provider.isDefaultProvider());
    state.put("sortOrder", provider.getSortOrder());
    state.put("providerType", provider.getProviderType().name());
    OidcClaimMapping mapping = provider.getClaimMapping();
    state.put("emailClaim", mapping.emailClaim());
    state.put("displayNameClaim", mapping.displayNameClaim());
    putIfPresent(state, "rolesClaim", mapping.rolesClaim());
    putIfPresent(state, "systemAdminRole", mapping.systemAdminRole());
    putIfPresent(state, "auditorRole", mapping.auditorRole());
    putIfPresent(state, "groupsClaim", mapping.groupsClaim());
    return state;
  }

  private static void putIfPresent(Map<String, Object> state, String key, String value) {
    if (value != null) {
      state.put(key, value);
    }
  }

  private static String objectLabel(OidcProvider provider) {
    return (provider.isLocal() ? LOCAL_ROW_LABEL : OBJECT_LABEL_PREFIX)
        + ": "
        + provider.getDisplayName();
  }

  private static NotFoundException notFound(UUID id) {
    return new NotFoundException("Kein Identitätsanbieter mit der ID " + id + " gefunden");
  }
}
