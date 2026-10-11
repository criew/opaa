package io.opaa.connection.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.types.ConnectionAuthMethod;
import io.opaa.api.types.ConnectionOwnership;
import io.opaa.indexing.source.profileprobe.ProfileProbeSourceConnector;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.security.CredentialsEncryptionKeyMissingException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The move of own secrets at start: one library that fails keeps its column and does not stop the
 * others or the start; private libraries and profiles without a personal secret are left alone.
 */
class OwnSecretRelocationTest {

  private static final Instant NOW = Instant.parse("2026-10-11T08:00:00Z");

  private final ConnectionProfileRepository profiles = mock(ConnectionProfileRepository.class);
  private final LibraryConnectionRepository connections = mock(LibraryConnectionRepository.class);
  private final Map<UUID, KnowledgeLibrary> rows = new HashMap<>();
  private final KnowledgeLibraryRepository libraries = LibraryRows.over(rows);
  private final EffectiveSourceSettings effective = mock(EffectiveSourceSettings.class);
  private final OwnSecretRelocation relocation =
      new OwnSecretRelocation(
          profiles, connections, libraries, effective, mock(PlatformTransactionManager.class));

  @Test
  void aLibraryThatFailsKeepsItsColumnAndTheOthersMoveAndTheStartGoesOn() {
    ConnectionProfile profile = profile(ConnectionAuthMethod.PERSONAL_SECRET);
    KnowledgeLibrary failing = sharedLibrary();
    KnowledgeLibrary moving = sharedLibrary();
    connect(profile, failing, moving);
    when(profiles.findConnectorsByName()).thenReturn(List.of(profile));
    doThrow(new CredentialsEncryptionKeyMissingException("kein Schlüssel"))
        .when(effective)
        .moveOwnSecretIntoStore(argThat(library -> library == failing), any());

    OwnSecretRelocation.Outcome outcome = relocation.relocate();

    assertThat(outcome).isEqualTo(new OwnSecretRelocation.Outcome(1, 1));
    verify(effective).moveOwnSecretIntoStore(moving, profile);
    assertThatCode(relocation::atStart).doesNotThrowAnyException();
  }

  @Test
  void privateLibrariesAndProfilesWithoutAPersonalSecretAreLeftAlone() {
    ConnectionProfile personal = profile(ConnectionAuthMethod.PERSONAL_SECRET);
    ConnectionProfile none = profile(ConnectionAuthMethod.NONE);
    KnowledgeLibrary own =
        KnowledgeLibrary.ownerOnly(
            UUID.randomUUID(),
            "Meine Ablage",
            null,
            UUID.randomUUID(),
            ProfileProbeSourceConnector.TYPE,
            null,
            "https://probe.example.org/a",
            null,
            "nutzer:geheim",
            false);
    rows.put(own.getId(), own);
    KnowledgeLibrary onNone = sharedLibrary();
    connect(personal, own);
    connect(none, onNone);
    when(profiles.findConnectorsByName()).thenReturn(List.of(personal, none));

    assertThat(relocation.relocate()).isEqualTo(new OwnSecretRelocation.Outcome(0, 0));
    verify(effective, never()).moveOwnSecretIntoStore(any(), any());
    verify(connections, never()).findByProfileId(eq(none.getId()));
  }

  private ConnectionProfile profile(ConnectionAuthMethod method) {
    ConnectionProfile profile = new ConnectionProfile(ProfileProbeSourceConnector.TYPE, NOW);
    ReflectionTestUtils.setField(profile, "name", "Zugang " + method);
    ReflectionTestUtils.setField(profile, "serverUrl", "https://probe.example.org");
    ReflectionTestUtils.setField(profile, "authMethod", method);
    ReflectionTestUtils.setField(profile, "ownership", ConnectionOwnership.BOTH);
    when(profiles.findById(profile.getId())).thenReturn(Optional.of(profile));
    return profile;
  }

  private KnowledgeLibrary sharedLibrary() {
    KnowledgeLibrary library =
        KnowledgeLibrary.ownedByUser(
            UUID.randomUUID(),
            "Ablage",
            null,
            UUID.randomUUID(),
            ProfileProbeSourceConnector.TYPE,
            null,
            "https://probe.example.org/a",
            null,
            "nutzer:geheim",
            false);
    rows.put(library.getId(), library);
    return library;
  }

  private void connect(ConnectionProfile profile, KnowledgeLibrary... connected) {
    List<LibraryConnection> list =
        java.util.Arrays.stream(connected)
            .map(library -> new LibraryConnection(library.getId(), profile.getId(), NOW))
            .toList();
    when(connections.findByProfileId(profile.getId())).thenReturn(list);
    for (LibraryConnection connection : list) {
      when(connections.findById(connection.getLibraryId())).thenReturn(Optional.of(connection));
    }
  }
}
