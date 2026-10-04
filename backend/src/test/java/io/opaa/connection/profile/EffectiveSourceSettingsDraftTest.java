package io.opaa.connection.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.api.types.ConnectionAuthMethod;
import io.opaa.api.types.ConnectionOwnership;
import io.opaa.common.ValidationException;
import io.opaa.connection.profile.EffectiveSourceSettings.Purpose;
import io.opaa.indexing.source.ConnectorData;
import io.opaa.indexing.source.ServiceAccountTokens;
import io.opaa.indexing.source.SourceConnectorRegistry;
import io.opaa.indexing.source.SourceSettings;
import io.opaa.indexing.source.profileprobe.ProfileProbeSourceConnector;
import io.opaa.indexing.source.upload.UploadSourceConnector;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.UploadedOriginalStore;
import io.opaa.security.TargetAddressValidator;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The draft of a library on a profile: composed like the run, with the profile's defaults, proxy,
 * TLS switch and sign-in; a value the profile sets otherwise is refused, and the library keeps only
 * its own part.
 */
class EffectiveSourceSettingsDraftTest {

  private static final Instant NOW = Instant.parse("2026-10-04T08:00:00Z");
  private static final String SERVER = "https://probe.example.org";
  private static final String PROXY = "proxy.example.org:3128";

  private final Map<UUID, KnowledgeLibrary> rows = new HashMap<>();
  private final Map<UUID, LibraryConnection> connectionRows = new HashMap<>();
  private final Map<UUID, ConnectionProfile> profileRows = new HashMap<>();
  private EffectiveSourceSettings effective;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    LibraryConnectionRepository connections = mock(LibraryConnectionRepository.class);
    ConnectionProfileRepository profiles = mock(ConnectionProfileRepository.class);
    when(connections.findById(any()))
        .thenAnswer(call -> Optional.ofNullable(connectionRows.get(call.<UUID>getArgument(0))));
    when(connections.findAllById(any()))
        .thenAnswer(
            call ->
                connectionRows.values().stream()
                    .filter(row -> contains(call.getArgument(0), row.getLibraryId()))
                    .toList());
    when(profiles.findById(any()))
        .thenAnswer(call -> Optional.ofNullable(profileRows.get(call.<UUID>getArgument(0))));
    when(profiles.findAllById(any()))
        .thenAnswer(
            call ->
                profileRows.values().stream()
                    .filter(row -> contains(call.getArgument(0), row.getId()))
                    .toList());
    ObjectProvider<SourceConnectorRegistry> registry = mock(ObjectProvider.class);
    when(registry.getObject())
        .thenReturn(
            new SourceConnectorRegistry(
                List.of(
                    new ProfileProbeSourceConnector(),
                    new UploadSourceConnector(mock(UploadedOriginalStore.class)))));
    ConnectorTypePolicyRepository policies = mock(ConnectorTypePolicyRepository.class);
    ConnectionSecrets secrets = new ConnectionSecrets(connections, LibraryRows.over(rows));
    effective =
        new EffectiveSourceSettings(
            connections,
            profiles,
            LibraryRows.over(rows),
            new SourceBlocks(
                policies,
                connections,
                profiles,
                new ProfileRequirements(policies, registry),
                secrets,
                registry),
            secrets,
            registry,
            new ServiceAccountTokens(TargetAddressValidator.disabled(), Clock.systemUTC()));
  }

  /** Acceptance criterion: run, change and draft compose the same configuration. */
  @Test
  void runChangeAndDraftComposeTheSame() {
    ConnectionProfile profile =
        profile(ConnectionAuthMethod.PERSONAL_SECRET, "{\"edition\": \"DC\"}", PROXY, true);
    KnowledgeLibrary library =
        library(SERVER + "/ablage", "nutzer:geheim", "{\"topic\": \"Wetter\"}");
    connect(library, profile);

    SourceSettings run = effective.of(library, Purpose.RUN);
    SourceSettings change = effective.of(library, Purpose.CHANGE);
    SourceSettings onItsProfile = effective.ofDraft(draft(null, library, ownOf(library)));
    SourceSettings onNamedProfile =
        effective.ofDraft(draft(profile.getId(), library, ownOf(library)));

    assertThat(run.connectorSettings().asMap())
        .isEqualTo(Map.of("edition", "DC", "topic", "Wetter"));
    assertThat(run.sourceProxy()).isEqualTo(PROXY);
    assertThat(run.sourceInsecureSsl()).isTrue();
    assertThat(run.sourceCredentials()).isEqualTo("nutzer:geheim");
    assertThat(change).isEqualTo(run);
    assertThat(onItsProfile).isEqualTo(run);
    assertThat(onNamedProfile).isEqualTo(run);
    assertThat(effective.storedOf(draft(null, library, ownOf(library))))
        .isEqualTo(run.connectorSettings());
  }

  @Test
  void aDraftNeedNotSendWhatTheProfileSets() {
    ConnectionProfile profile =
        profile(ConnectionAuthMethod.PERSONAL_SECRET, "{\"edition\": \"DC\"}", PROXY, false);

    SourceSettings draft =
        effective.ofDraft(
            draft(
                profile.getId(),
                null,
                new SourceSettings(
                    null, null, null, "a:b", false, settings("{\"topic\": \"x\"}"))));

    assertThat(draft.sourceUrl()).isEqualTo(SERVER);
    assertThat(draft.sourceProxy()).isEqualTo(PROXY);
    assertThat(draft.connectorSettings().asMap()).isEqualTo(Map.of("edition", "DC", "topic", "x"));
    assertThat(draft.sourceCredentials()).isEqualTo("a:b");
  }

  @Test
  void aValueTheProfileSetsOtherwiseIsRefused() {
    UUID profileId =
        profile(ConnectionAuthMethod.NONE, "{\"edition\": \"DC\"}", PROXY, false).getId();

    assertThatThrownBy(
            () ->
                effective.ofDraft(
                    draft(
                        profileId,
                        null,
                        new SourceSettings(
                            null, null, null, null, false, settings("{\"edition\": \"CLOUD\"}")))))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("sourceSettings.edition");
    assertThatThrownBy(
            () ->
                effective.ofDraft(
                    draft(
                        profileId,
                        null,
                        new SourceSettings(
                            null, null, "eigener.example.org:8080", null, false, null))))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("sourceProxy");
    assertThatThrownBy(
            () ->
                effective.ofDraft(
                    draft(profileId, null, new SourceSettings(null, null, null, null, true, null))))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("Zertifikatsprüfung");
    assertThatThrownBy(
            () ->
                effective.ofDraft(
                    draft(
                        profileId,
                        null,
                        new SourceSettings(
                            null, "https://fremd.example.org", null, null, false, null))))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("Server-Adresse des Zugangs");
    SourceSettings repeated =
        effective.ofDraft(
            draft(
                profileId,
                null,
                new SourceSettings(
                    null, null, PROXY, null, false, settings("{\"edition\": \"DC\"}"))));
    assertThat(repeated.connectorSettings().asMap()).isEqualTo(Map.of("edition", "DC"));
  }

  /** Rule 1: the library stores only its own part. */
  @Test
  void theLibraryKeepsOnlyItsOwnPart() {
    UUID profileId =
        profile(ConnectionAuthMethod.NONE, "{\"edition\": \"DC\"}", PROXY, true).getId();
    SourceSettings validated =
        new SourceSettings(
            null,
            SERVER + "/a",
            PROXY,
            null,
            true,
            settings("{\"edition\": \"DC\", \"topic\": \"t\"}"));

    SourceSettings own = effective.ownPart(draft(profileId, null, validated), validated);

    assertThat(own.sourceProxy()).isNull();
    assertThat(own.sourceInsecureSsl()).isFalse();
    assertThat(own.connectorSettings().asMap()).isEqualTo(Map.of("topic", "t"));
    assertThat(own.sourceUrl()).isEqualTo(SERVER + "/a");
  }

  @Test
  void theSignInOfTheProfileDecidesTheSecret() {
    UUID profileId = profile(ConnectionAuthMethod.NONE, null, null, false).getId();

    SourceSettings draft =
        effective.ofDraft(
            draft(profileId, null, new SourceSettings(null, null, null, "a:b", false, null)));

    assertThat(draft.sourceCredentials()).isNull();
  }

  @Test
  void theStoredSecretFollowsADraftOnlyOnItsOrigin() {
    ConnectionProfile current = profile(ConnectionAuthMethod.PERSONAL_SECRET, null, null, false);
    ConnectionProfile sameOrigin =
        profile(ConnectionAuthMethod.PERSONAL_SECRET, null, null, false, SERVER + "/ablage");
    ConnectionProfile otherOrigin =
        profile(ConnectionAuthMethod.PERSONAL_SECRET, null, null, false, "https://neu.example.org");
    KnowledgeLibrary library = library(SERVER + "/ablage/x", "nutzer:geheim", null);
    connect(library, current);

    SourceSettings kept =
        effective.ofDraft(
            draft(
                sameOrigin.getId(),
                library,
                new SourceSettings(null, SERVER + "/ablage/x", null, null, false, null)));
    SourceSettings moved =
        effective.ofDraft(
            draft(
                otherOrigin.getId(),
                library,
                new SourceSettings(null, "https://neu.example.org/x", null, null, false, null)));

    assertThat(kept.sourceCredentials()).isEqualTo("nutzer:geheim");
    assertThat(moved.sourceCredentials()).isNull();
  }

  /** A locked or removed profile of the stored library does not refuse a draft on another one. */
  @Test
  void theStoredBlockDoesNotRefuseADraftOnAnotherProfile() {
    ConnectionProfile locked = profile(ConnectionAuthMethod.NONE, null, null, false);
    locked.lockedSince(NOW, NOW);
    ConnectionProfile replacement = profile(ConnectionAuthMethod.NONE, null, null, false);
    KnowledgeLibrary library = library(SERVER + "/x", null, null);
    connect(library, locked);

    SourceSettings draft =
        effective.ofDraft(
            draft(
                replacement.getId(),
                library,
                new SourceSettings(null, SERVER + "/x", null, null, false, null)));

    assertThat(draft.sourceUrl()).isEqualTo(SERVER + "/x");
  }

  private ConnectionProfile profile(
      ConnectionAuthMethod method, String defaults, String proxy, boolean insecureSsl) {
    return profile(method, defaults, proxy, insecureSsl, SERVER);
  }

  private ConnectionProfile profile(
      ConnectionAuthMethod method,
      String defaults,
      String proxy,
      boolean insecureSsl,
      String server) {
    ConnectionProfile profile = new ConnectionProfile(ProfileProbeSourceConnector.TYPE, NOW);
    profile.replace(
        new ConnectionProfileValues(
            "Zugang " + UUID.randomUUID(),
            server,
            method,
            ConnectionOwnership.LIBRARY,
            null,
            null,
            null,
            null,
            settings(defaults),
            proxy,
            insecureSsl),
        null,
        NOW);
    profileRows.put(profile.getId(), profile);
    return profile;
  }

  private KnowledgeLibrary library(String url, String secret, String settings) {
    KnowledgeLibrary library =
        KnowledgeLibrary.ownedByUser(
            UUID.randomUUID(),
            "Ablage",
            null,
            UUID.randomUUID(),
            ProfileProbeSourceConnector.TYPE,
            null,
            url,
            null,
            secret,
            false);
    if (settings != null) {
      library.updateSourceSettings(settings);
    }
    rows.put(library.getId(), library);
    return library;
  }

  private void connect(KnowledgeLibrary library, ConnectionProfile profile) {
    connectionRows.put(
        library.getId(), new LibraryConnection(library.getId(), profile.getId(), NOW));
  }

  private static SourceDraft draft(
      UUID profileId, KnowledgeLibrary library, SourceSettings fields) {
    return SourceDraft.ofLibrary(
        ProfileProbeSourceConnector.TYPE,
        profileId,
        library == null ? null : library.getId(),
        fields);
  }

  /** The library's own fields as a draft sends them back, without its secret. */
  private static SourceSettings ownOf(KnowledgeLibrary library) {
    return new SourceSettings(
        library.getSourcePath(),
        library.getSourceUrl(),
        library.getSourceProxy(),
        null,
        library.isSourceInsecureSsl(),
        ConnectorData.storedIn(library));
  }

  private static ConnectorData settings(String json) {
    return json == null ? null : ConnectorData.fromJson(json);
  }

  private static boolean contains(Iterable<UUID> ids, UUID id) {
    for (UUID candidate : ids) {
      if (candidate.equals(id)) {
        return true;
      }
    }
    return false;
  }
}
