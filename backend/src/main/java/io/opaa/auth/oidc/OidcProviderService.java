package io.opaa.auth.oidc;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.UserRepository;
import io.opaa.common.ConflictException;
import io.opaa.common.NotFoundException;
import io.opaa.common.ValidationException;
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
 * exactly one provider is the default while any exist ({@code ux_oidc_providers_single_default}),
 * and the default is always enabled and its decoder ready - it is the provider {@code
 * opaa.auth.initial-admin-email} applies to and the one the directory synchronisation is bound to,
 * so it can be neither disabled nor deleted before another provider took its place, and a provider
 * whose keys cannot be fetched cannot take that place (there must never be a state without a
 * sign-in-capable provider). The very first provider becomes the default automatically.
 *
 * <p>Every operator-entered address passes {@link OidcAddressPolicy} before the row is written.
 * Deleting a provider deletes no account: {@code users(subject, issuer)} keeps every row, only the
 * sign-in through that issuer stops (ADR-0025, Entscheidung 2).
 */
@Service
public class OidcProviderService {

  private static final String OBJECT_LABEL_PREFIX = "Identitätsanbieter";
  private static final String ISSUER_LABEL = "Issuer-URI";
  private static final String JWK_SET_LABEL = "JWK-Set-URI";

  private final OidcProviderRepository repository;
  private final UserRepository userRepository;
  private final OidcAddressPolicy addressPolicy;
  private final OidcProviderRegistry registry;
  private final AuditEventRecorder auditEventRecorder;
  private final ApplicationEventPublisher eventPublisher;

  public OidcProviderService(
      OidcProviderRepository repository,
      UserRepository userRepository,
      OidcAddressPolicy addressPolicy,
      OidcProviderRegistry registry,
      AuditEventRecorder auditEventRecorder,
      ApplicationEventPublisher eventPublisher) {
    this.repository = repository;
    this.userRepository = userRepository;
    this.addressPolicy = addressPolicy;
    this.registry = registry;
    this.auditEventRecorder = auditEventRecorder;
    this.eventPublisher = eventPublisher;
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
    long existing = repository.count();
    provider.setSortOrder((int) existing);
    if (existing == 0) {
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
   * added or removed is an issuer change too.
   */
  @Transactional
  public OidcProvider updateProvider(
      UUID organizationId, UUID actorUserId, UUID id, OidcProviderDraft draft) {
    OidcProvider provider = repository.findById(id).orElseThrow(() -> notFound(id));
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

  /**
   * Accounts provisioned through the deleted provider stay ({@code users} carries no FK to this
   * table); only their sign-in stops until a provider with the same issuer exists again.
   */
  @Transactional
  public void deleteProvider(UUID organizationId, UUID actorUserId, UUID id) {
    OidcProvider provider = repository.findById(id).orElseThrow(() -> notFound(id));
    if (provider.isDefaultProvider()) {
      throw new ConflictException(
          "Der Standardanbieter kann nicht gelöscht werden. Machen Sie zuerst einen anderen"
              + " Anbieter zum Standard.");
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

  /** A disabled provider's tokens are refused with the registry's next rebuild - after commit. */
  @Transactional
  public OidcProvider setEnabled(UUID organizationId, UUID actorUserId, UUID id, boolean enabled) {
    OidcProvider provider = repository.findById(id).orElseThrow(() -> notFound(id));
    if (provider.isEnabled() == enabled) {
      return provider;
    }
    if (!enabled && provider.isDefaultProvider()) {
      throw new ConflictException(
          "Der Standardanbieter kann nicht deaktiviert werden. Machen Sie zuerst einen anderen"
              + " Anbieter zum Standard.");
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
   * Moves the default flag. The previous default is flushed before the new one is written: {@code
   * ux_oidc_providers_single_default} is a plain partial unique index, and Hibernate's flush order
   * could otherwise write the new {@code true} before the old {@code false} - the same reasoning as
   * {@code LlmModelService#activateModel}.
   */
  @Transactional
  public OidcProvider makeDefault(UUID organizationId, UUID actorUserId, UUID id) {
    OidcProvider provider = repository.findById(id).orElseThrow(() -> notFound(id));
    if (provider.isDefaultProvider()) {
      return provider;
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
              + " weder deaktiviert noch gelöscht werden kann.");
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
    state.put("clientId", provider.getClientId());
    putIfPresent(state, "jwkSetUri", provider.getJwkSetUri());
    state.put("enabled", provider.isEnabled());
    state.put("isDefault", provider.isDefaultProvider());
    state.put("sortOrder", provider.getSortOrder());
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
    return OBJECT_LABEL_PREFIX + ": " + provider.getDisplayName();
  }

  private static NotFoundException notFound(UUID id) {
    return new NotFoundException("Kein Identitätsanbieter mit der ID " + id + " gefunden");
  }
}
