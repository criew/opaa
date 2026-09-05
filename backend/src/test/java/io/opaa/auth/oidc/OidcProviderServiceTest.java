package io.opaa.auth.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.UserRepository;
import io.opaa.common.ConflictException;
import io.opaa.common.NotFoundException;
import io.opaa.common.ValidationException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/**
 * {@link OidcProviderService} against mocked collaborators (#1329, ADR-0025): the invariants a
 * provider row must satisfy before it is written (http(s) issuer, no duplicate issuer, the one
 * default provider is always enabled), the automatic default for the very first provider, the audit
 * event and the change event every write leaves behind, and the SSRF policy every operator-entered
 * address passes through.
 */
class OidcProviderServiceTest {

  private static final UUID ORGANIZATION_ID = UUID.randomUUID();
  private static final UUID ACTOR_ID = UUID.randomUUID();

  private final OidcProviderRepository repository = mock(OidcProviderRepository.class);
  private final UserRepository userRepository = mock(UserRepository.class);
  private final OidcAddressPolicy addressPolicy = mock(OidcAddressPolicy.class);
  private final OidcProviderRegistry registry = mock(OidcProviderRegistry.class);
  private final AuditEventRecorder auditEventRecorder = mock(AuditEventRecorder.class);
  private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

  private OidcProviderService service;

  @BeforeEach
  void setUp() {
    service =
        new OidcProviderService(
            repository,
            userRepository,
            addressPolicy,
            registry,
            auditEventRecorder,
            eventPublisher);
    when(repository.save(any(OidcProvider.class))).thenAnswer(inv -> inv.getArgument(0));
    when(repository.saveAndFlush(any(OidcProvider.class))).thenAnswer(inv -> inv.getArgument(0));
    when(repository.findByNormalizedIssuerUri(anyString())).thenReturn(Optional.empty());
    when(registry.healthOf(any())).thenReturn(new OidcProviderRegistry.Health(true, null));
    when(repository.findByDefaultProviderTrue()).thenReturn(Optional.empty());
    when(repository.count()).thenReturn(0L);
  }

  private static OidcProviderDraft draft(String name, String issuer) {
    return new OidcProviderDraft(
        name, issuer, "opaa-frontend", null, OidcClaimMapping.keycloakDefaults());
  }

  @Test
  void theFirstProviderBecomesTheEnabledDefault() {
    OidcProvider created =
        service.createProvider(
            ORGANIZATION_ID, ACTOR_ID, draft("Verzeichnisdienst", "https://idp.example/realms/a"));

    assertThat(created.isDefaultProvider()).isTrue();
    assertThat(created.isEnabled()).isTrue();
    assertThat(created.getIssuerUri()).isEqualTo("https://idp.example/realms/a");
    assertThat(created.getClaimMapping().emailClaim()).isEqualTo("email");
    verify(addressPolicy).requireAllowed("https://idp.example/realms/a", "Issuer-URI");
    verify(eventPublisher).publishEvent(any(OidcProvidersChangedEvent.class));
    ArgumentCaptor<AuditEvent> audit = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEventRecorder).recordUserAction(audit.capture());
    assertThat(audit.getValue().eventType()).isEqualTo(AuditEventType.OIDC_PROVIDER_CREATED);
    assertThat(audit.getValue().objectType()).isEqualTo(AuditObjectType.SYSTEM_SETTING);
    assertThat(audit.getValue().objectId()).isEqualTo(created.getId());
    assertThat(audit.getValue().after()).containsEntry("issuerUri", "https://idp.example/realms/a");
  }

  @Test
  void aSecondProviderIsNotTheDefault() {
    when(repository.count()).thenReturn(1L);

    OidcProvider created =
        service.createProvider(ORGANIZATION_ID, ACTOR_ID, draft("Partner", "https://b.example"));

    assertThat(created.isDefaultProvider()).isFalse();
    assertThat(created.isEnabled()).isTrue();
  }

  @Test
  void issuerAndJwkSetAddressesMustPassTheAddressPolicy() {
    doThrow(new ValidationException("Die Zieladresse liegt in einem gesperrten Bereich."))
        .when(addressPolicy)
        .requireAllowed("https://10.0.0.5/realms/a", "Issuer-URI");

    assertThatThrownBy(
            () ->
                service.createProvider(
                    ORGANIZATION_ID, ACTOR_ID, draft("Intern", "https://10.0.0.5/realms/a")))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("gesperrten Bereich");
    verify(repository, never()).save(any());
    verify(eventPublisher, never()).publishEvent(any());
  }

  @Test
  void theJwkSetOverrideIsValidatedToo() {
    OidcProviderDraft withOverride =
        new OidcProviderDraft(
            "Verzeichnisdienst",
            "https://idp.example/realms/a",
            "opaa-frontend",
            "http://keycloak:8180/realms/a/protocol/openid-connect/certs",
            OidcClaimMapping.keycloakDefaults());

    service.createProvider(ORGANIZATION_ID, ACTOR_ID, withOverride);

    verify(addressPolicy)
        .requireAllowed(
            "http://keycloak:8180/realms/a/protocol/openid-connect/certs", "JWK-Set-URI");
  }

  @Test
  void anIssuerThatIsNotAnHttpUriIsRejected() {
    assertThatThrownBy(
            () ->
                service.createProvider(
                    ORGANIZATION_ID, ACTOR_ID, draft("Kaputt", "idp.example/realms/a")))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("http");
  }

  @Test
  void aDuplicateIssuerIsAConflict() {
    OidcProvider existing = provider("Beschäftigte", "https://idp.example/realms/a", true, true);
    when(repository.findByNormalizedIssuerUri("https://idp.example/realms/a"))
        .thenReturn(Optional.of(existing));

    assertThatThrownBy(
            () ->
                service.createProvider(
                    ORGANIZATION_ID, ACTOR_ID, draft("Doppelt", "https://idp.example/realms/a")))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("Issuer");
    verify(repository, never()).save(any());
  }

  @Test
  void aTrailingSlashDoesNotMakeTheSameIssuerLookDifferent() {
    OidcProvider existing = provider("Beschäftigte", "https://idp.example/realms/a", true, true);
    when(repository.findByNormalizedIssuerUri("https://idp.example/realms/a"))
        .thenReturn(Optional.of(existing));

    assertThatThrownBy(
            () ->
                service.createProvider(
                    ORGANIZATION_ID, ACTOR_ID, draft("Doppelt", "https://idp.example/realms/a/")))
        .isInstanceOf(ConflictException.class);
  }

  @Test
  void updatingKeepsTheIssuerUniqueAmongTheOtherProviders() {
    OidcProvider self = provider("Beschäftigte", "https://idp.example/realms/a", true, true);
    OidcProvider other = provider("Partner", "https://idp.example/realms/b", true, false);
    when(repository.findById(self.getId())).thenReturn(Optional.of(self));
    when(repository.findByNormalizedIssuerUri("https://idp.example/realms/b"))
        .thenReturn(Optional.of(other));

    assertThatThrownBy(
            () ->
                service.updateProvider(
                    ORGANIZATION_ID,
                    ACTOR_ID,
                    self.getId(),
                    draft("Beschäftigte", "https://idp.example/realms/b")))
        .isInstanceOf(ConflictException.class);

    // the row's own issuer is not a conflict with itself
    when(repository.findByNormalizedIssuerUri("https://idp.example/realms/a"))
        .thenReturn(Optional.of(self));
    OidcProvider updated =
        service.updateProvider(
            ORGANIZATION_ID,
            ACTOR_ID,
            self.getId(),
            draft("Beschäftigte (neu)", "https://idp.example/realms/a"));
    assertThat(updated.getDisplayName()).isEqualTo("Beschäftigte (neu)");
    ArgumentCaptor<AuditEvent> audit = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEventRecorder).recordUserAction(audit.capture());
    assertThat(audit.getValue().eventType()).isEqualTo(AuditEventType.OIDC_PROVIDER_CHANGED);
    assertThat(audit.getValue().before()).containsEntry("displayName", "Beschäftigte");
    assertThat(audit.getValue().after()).containsEntry("displayName", "Beschäftigte (neu)");
  }

  @Test
  void changingTheIssuerOfAProviderWithAccountsIsRefusedWithTheirCount() {
    OidcProvider self = provider("Beschäftigte", "https://idp.example/realms/a", true, true);
    when(repository.findById(self.getId())).thenReturn(Optional.of(self));
    when(userRepository.countByIssuer("https://idp.example/realms/a")).thenReturn(17L);

    assertThatThrownBy(
            () ->
                service.updateProvider(
                    ORGANIZATION_ID,
                    ACTOR_ID,
                    self.getId(),
                    draft("Beschäftigte", "https://idp.example/realms/neu")))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("17 Konten");
    assertThat(self.getIssuerUri()).isEqualTo("https://idp.example/realms/a");
    verify(eventPublisher, never()).publishEvent(any());

    // without accounts the issuer may still be corrected - the mistyped-issuer case
    when(userRepository.countByIssuer("https://idp.example/realms/a")).thenReturn(0L);
    OidcProvider updated =
        service.updateProvider(
            ORGANIZATION_ID,
            ACTOR_ID,
            self.getId(),
            draft("Beschäftigte", "https://idp.example/realms/neu"));
    assertThat(updated.getIssuerUri()).isEqualTo("https://idp.example/realms/neu");
  }

  /**
   * The stored issuer is what the token's {@code iss} is compared with byte for byte, so it is kept
   * exactly as entered; consequently even a slash-only change is a change of identity for the
   * accounts minted under it.
   */
  @Test
  void theIssuerIsStoredAsEnteredAndASlashOnlyChangeCountsAsAnIssuerChange() {
    OidcProvider created =
        service.createProvider(
            ORGANIZATION_ID, ACTOR_ID, draft("Auth0", " https://tenant.eu.auth0.com/ "));
    assertThat(created.getIssuerUri()).isEqualTo("https://tenant.eu.auth0.com/");

    when(repository.findById(created.getId())).thenReturn(Optional.of(created));
    when(userRepository.countByIssuer("https://tenant.eu.auth0.com/")).thenReturn(3L);
    assertThatThrownBy(
            () ->
                service.updateProvider(
                    ORGANIZATION_ID,
                    ACTOR_ID,
                    created.getId(),
                    draft("Auth0", "https://tenant.eu.auth0.com")))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("3 Konten");
  }

  @Test
  void theDefaultProviderCanNeitherBeDisabledNorDeleted() {
    OidcProvider standard = provider("Beschäftigte", "https://idp.example/realms/a", true, true);
    when(repository.findById(standard.getId())).thenReturn(Optional.of(standard));

    assertThatThrownBy(() -> service.setEnabled(ORGANIZATION_ID, ACTOR_ID, standard.getId(), false))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("Standardanbieter");
    assertThatThrownBy(() -> service.deleteProvider(ORGANIZATION_ID, ACTOR_ID, standard.getId()))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("Standardanbieter");
    assertThat(standard.isEnabled()).isTrue();
    verify(repository, never()).delete(any());
    verify(eventPublisher, never()).publishEvent(any());
  }

  @Test
  void disablingANonDefaultProviderIsAuditedAndPublished() {
    OidcProvider partner = provider("Partner", "https://idp.example/realms/b", true, false);
    when(repository.findById(partner.getId())).thenReturn(Optional.of(partner));

    OidcProvider disabled = service.setEnabled(ORGANIZATION_ID, ACTOR_ID, partner.getId(), false);

    assertThat(disabled.isEnabled()).isFalse();
    ArgumentCaptor<AuditEvent> audit = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEventRecorder).recordUserAction(audit.capture());
    assertThat(audit.getValue().eventType()).isEqualTo(AuditEventType.OIDC_PROVIDER_DISABLED);
    verify(eventPublisher).publishEvent(any(OidcProvidersChangedEvent.class));

    service.setEnabled(ORGANIZATION_ID, ACTOR_ID, partner.getId(), true);
    verify(auditEventRecorder, times(2)).recordUserAction(audit.capture());
    assertThat(audit.getValue().eventType()).isEqualTo(AuditEventType.OIDC_PROVIDER_ENABLED);
  }

  @Test
  void makingAProviderTheDefaultMovesTheFlagFromThePreviousOne() {
    OidcProvider previous = provider("Beschäftigte", "https://idp.example/realms/a", true, true);
    OidcProvider next = provider("Partner", "https://idp.example/realms/b", true, false);
    when(repository.findById(next.getId())).thenReturn(Optional.of(next));
    when(repository.findByDefaultProviderTrue()).thenReturn(Optional.of(previous));

    OidcProvider made = service.makeDefault(ORGANIZATION_ID, ACTOR_ID, next.getId());

    assertThat(made.isDefaultProvider()).isTrue();
    assertThat(previous.isDefaultProvider()).isFalse();
    // the previous default is flushed before the new one is written - the partial unique index
    // ux_oidc_providers_single_default would otherwise see two defaults mid-transaction
    verify(repository).saveAndFlush(previous);
    verify(eventPublisher).publishEvent(any(OidcProvidersChangedEvent.class));
  }

  /**
   * "Es gibt keinen Zustand ohne anmeldefähigen Anbieter" (ADR-0025, Entscheidung 3): a provider
   * whose decoder could not be built must not become the one provider that can neither be disabled
   * nor deleted.
   */
  @Test
  void aProviderWhoseKeysAreNotReachableCannotBecomeTheDefault() {
    OidcProvider broken = provider("Partner", "https://idp.example/realms/b", true, false);
    when(repository.findById(broken.getId())).thenReturn(Optional.of(broken));
    when(registry.healthOf(broken.getId()))
        .thenReturn(
            new OidcProviderRegistry.Health(false, "Discovery-Dokument: Antwort mit HTTP 503."));

    assertThatThrownBy(() -> service.makeDefault(ORGANIZATION_ID, ACTOR_ID, broken.getId()))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("HTTP 503");
    assertThat(broken.isDefaultProvider()).isFalse();
    verify(eventPublisher, never()).publishEvent(any());
  }

  @Test
  void aDisabledProviderCannotBecomeTheDefault() {
    OidcProvider disabled = provider("Alt", "https://idp.example/realms/alt", false, false);
    when(repository.findById(disabled.getId())).thenReturn(Optional.of(disabled));

    assertThatThrownBy(() -> service.makeDefault(ORGANIZATION_ID, ACTOR_ID, disabled.getId()))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("deaktiviert");
  }

  @Test
  void reorderingAssignsSortOrderInTheGivenSequence() {
    OidcProvider a = provider("A", "https://idp.example/realms/a", true, true);
    OidcProvider b = provider("B", "https://idp.example/realms/b", true, false);
    OidcProvider c = provider("C", "https://idp.example/realms/c", true, false);
    when(repository.findAllByOrderBySortOrderAscDisplayNameAsc()).thenReturn(List.of(a, b, c));

    service.reorder(ORGANIZATION_ID, ACTOR_ID, List.of(c.getId(), a.getId(), b.getId()));

    assertThat(c.getSortOrder()).isZero();
    assertThat(a.getSortOrder()).isEqualTo(1);
    assertThat(b.getSortOrder()).isEqualTo(2);
    verify(eventPublisher).publishEvent(any(OidcProvidersChangedEvent.class));
  }

  @Test
  void reorderingMustNameEveryProviderExactlyOnce() {
    OidcProvider a = provider("A", "https://idp.example/realms/a", true, true);
    OidcProvider b = provider("B", "https://idp.example/realms/b", true, false);
    when(repository.findAllByOrderBySortOrderAscDisplayNameAsc()).thenReturn(List.of(a, b));

    assertThatThrownBy(() -> service.reorder(ORGANIZATION_ID, ACTOR_ID, List.of(a.getId())))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(
            () ->
                service.reorder(
                    ORGANIZATION_ID, ACTOR_ID, List.of(a.getId(), b.getId(), UUID.randomUUID())))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void anUnknownProviderIs404() {
    UUID unknown = UUID.randomUUID();
    when(repository.findById(unknown)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.getProvider(unknown)).isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> service.deleteProvider(ORGANIZATION_ID, ACTOR_ID, unknown))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void deletingANonDefaultProviderIsAuditedWithItsLastState() {
    OidcProvider partner = provider("Partner", "https://idp.example/realms/b", true, false);
    when(repository.findById(partner.getId())).thenReturn(Optional.of(partner));

    service.deleteProvider(ORGANIZATION_ID, ACTOR_ID, partner.getId());

    verify(repository).delete(partner);
    ArgumentCaptor<AuditEvent> audit = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEventRecorder).recordUserAction(audit.capture());
    assertThat(audit.getValue().eventType()).isEqualTo(AuditEventType.OIDC_PROVIDER_DELETED);
    assertThat(audit.getValue().before())
        .containsEntry("issuerUri", "https://idp.example/realms/b");
    verify(eventPublisher).publishEvent(any(OidcProvidersChangedEvent.class));
  }

  static OidcProvider provider(String name, String issuer, boolean enabled, boolean isDefault) {
    OidcProvider provider =
        new OidcProvider(name, issuer, "opaa-frontend", null, OidcClaimMapping.keycloakDefaults());
    if (!enabled) {
      provider.disable();
    }
    if (isDefault) {
      provider.markDefault();
    }
    return provider;
  }
}
