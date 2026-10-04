package io.opaa.connection.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.types.ConnectionProfileSupport;
import io.opaa.asset.AssetOwnerNames;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.CurrentUser;
import io.opaa.common.ValidationException;
import io.opaa.indexing.source.SourceConnectorRegistry;
import io.opaa.indexing.source.TestSourceConnectors;
import io.opaa.indexing.source.profileprobe.ProfileOAuthProbeSourceConnector;
import io.opaa.indexing.source.profileprobe.ProfileProbeSourceConnector;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.SourceType;
import io.opaa.test.SourceTypes;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The effective profile support of a connector type and which types the requirement can be switched
 * for: only a declared {@code OPTIONAL} becomes {@code REQUIRED}, and only it is switched.
 */
class ProfileRequirementsTest {

  private static final Instant NOW = Instant.parse("2026-10-04T08:00:00Z");
  private static final SourceType REQUIRING = ProfileOAuthProbeSourceConnector.TYPE;

  private final ConnectorTypePolicyRepository policies = mock(ConnectorTypePolicyRepository.class);
  private final ObjectProvider<SourceConnectorRegistry> registry = registry();
  private final ProfileRequirements requirements = new ProfileRequirements(policies, registry);

  @Test
  void onlyADeclaredOptionalTypeBecomesRequiredOnceSwitched() {
    switchOn(ProfileProbeSourceConnector.TYPE);
    switchOn(SourceTypes.RSS_FEED);
    switchOn(SourceTypes.FILESYSTEM);

    assertThat(requirements.effectiveProfileSupport(ProfileProbeSourceConnector.TYPE))
        .isEqualTo(ConnectionProfileSupport.REQUIRED);
    assertThat(requirements.effectiveProfileSupport(SourceTypes.RSS_FEED))
        .isEqualTo(ConnectionProfileSupport.REQUIRED);
    assertThat(requirements.effectiveProfileSupport(SourceTypes.FILESYSTEM))
        .isEqualTo(ConnectionProfileSupport.FORBIDDEN);
    assertThat(requirements.effectiveProfileSupport(REQUIRING))
        .isEqualTo(ConnectionProfileSupport.REQUIRED);
    assertThat(requirements.effectiveProfileSupport(SourceTypes.S3))
        .isEqualTo(ConnectionProfileSupport.OPTIONAL);
    assertThat(requirements.profileRequired(REQUIRING)).isTrue();
  }

  @Test
  void withoutTheSwitchTheDeclarationHolds() {
    when(policies.findById(any())).thenReturn(Optional.empty());

    assertThat(requirements.effectiveProfileSupport(ProfileProbeSourceConnector.TYPE))
        .isEqualTo(ConnectionProfileSupport.OPTIONAL);
    assertThat(requirements.profileRequired(ProfileProbeSourceConnector.TYPE)).isFalse();
    assertThat(requirements.effectiveProfileSupport(SourceType.of("UNKNOWN")))
        .isEqualTo(ConnectionProfileSupport.FORBIDDEN);
  }

  /** Acceptance criterion: a type declared "verboten" or "Pflicht" is not switched (400). */
  @Test
  void aTypeThatDoesNotDeclareOptionalProfilesIsNotSwitched() {
    AuditEventRecorder audit = mock(AuditEventRecorder.class);
    ConnectionProfileRepository profiles = mock(ConnectionProfileRepository.class);
    ConnectorLockService locks =
        new ConnectorLockService(
            policies,
            profiles,
            mock(SourceBlocks.class),
            requirements,
            registry,
            audit,
            Clock.systemUTC());
    ProfileRequirementService service =
        new ProfileRequirementService(
            policies,
            profiles,
            mock(LibraryConnectionRepository.class),
            requirements,
            locks,
            mock(AssetOwnerNames.class),
            registry,
            audit,
            Clock.systemUTC());
    CurrentUser caller = mock(CurrentUser.class);

    assertThatThrownBy(() -> service.require(caller, REQUIRING, true, OwnAddressStock.RUNS))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("ohnehin nur über Zugänge");
    assertThatThrownBy(() -> service.require(caller, SourceTypes.FILESYSTEM, false, null))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("nicht über Zugänge verbunden");
    verify(policies, never()).save(any());
    verify(audit, never()).recordUserAction(any());
  }

  /** A library with its own address keeps it while only a profile is admitted. */
  @Test
  void anOwnAddressIsKeptWhileOnlyAProfileIsAdmitted() {
    switchOn(ProfileProbeSourceConnector.TYPE);
    ProfileRequirementService service =
        new ProfileRequirementService(
            policies,
            mock(ConnectionProfileRepository.class),
            mock(LibraryConnectionRepository.class),
            requirements,
            mock(ConnectorLockService.class),
            mock(AssetOwnerNames.class),
            registry,
            mock(AuditEventRecorder.class),
            Clock.systemUTC());
    KnowledgeLibrary library =
        KnowledgeLibrary.ownedByUser(
            UUID.randomUUID(),
            "Eigen",
            null,
            UUID.randomUUID(),
            ProfileProbeSourceConnector.TYPE,
            null,
            "https://probe.example.org/a",
            null,
            null,
            false);

    service.requireOwnAddressKept(library, "https://probe.example.org/a");
    assertThatThrownBy(() -> service.requireOwnAddressKept(library, "https://probe.example.org/b"))
        .isInstanceOf(ValidationException.class)
        .satisfies(
            e -> assertThat(((ValidationException) e).getCode()).isEqualTo("PROFILE_REQUIRED"));
  }

  private void switchOn(SourceType type) {
    ConnectorTypePolicy policy = new ConnectorTypePolicy(type, NOW);
    policy.requireProfiles(OwnAddressStock.LOCKED, NOW);
    when(policies.findById(type.key())).thenReturn(Optional.of(policy));
  }

  @SuppressWarnings("unchecked")
  private static ObjectProvider<SourceConnectorRegistry> registry() {
    ObjectProvider<SourceConnectorRegistry> provider = mock(ObjectProvider.class);
    when(provider.getObject())
        .thenReturn(
            TestSourceConnectors.connectors()
                .with(new ProfileProbeSourceConnector(), new ProfileOAuthProbeSourceConnector())
                .registry());
    return provider;
  }
}
