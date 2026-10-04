package io.opaa.connection.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.indexing.source.Secret;
import io.opaa.indexing.source.SecretKind;
import io.opaa.indexing.source.SourceBlock;
import io.opaa.indexing.source.SourceConnectionBlockedException;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.test.SourceTypes;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The secret a library holds: handed out with its kind, its absence a reason, its discard. */
class ConnectionSecretsTest {

  private final LibraryConnectionRepository connections = mock(LibraryConnectionRepository.class);
  private final Map<UUID, KnowledgeLibrary> rows = new HashMap<>();
  private final KnowledgeLibraryRepository libraries = LibraryRows.over(rows);
  private final ConnectionSecrets secrets = new ConnectionSecrets(connections, libraries);

  @Test
  void aHeldSecretIsHandedOutAsAPersonalSecret() {
    SecretOwner owner = SecretOwner.of(null, library("nutzer:geheim"));

    assertThat(secrets.current(owner, "https://quelle.example.org", "Quelle"))
        .isEqualTo(new Secret(SecretKind.PERSONAL_SECRET, "nutzer:geheim"));
    assertThat(secrets.stateOf(owner)).isEmpty();
    assertThat(secrets.stored(owner)).isEqualTo("nutzer:geheim");
    assertThat(secrets.holds(owner)).isTrue();
  }

  @Test
  void aMissingSecretIsNotConnected() {
    SecretOwner owner = SecretOwner.of(null, library(null));

    assertThat(secrets.stateOf(owner)).contains(SourceBlock.Reason.NOT_CONNECTED);
    assertThat(secrets.stored(owner)).isNull();
    assertThat(secrets.holds(owner)).isFalse();
    assertThatThrownBy(() -> secrets.current(owner, "https://quelle.example.org", "Quelle"))
        .isInstanceOf(SourceConnectionBlockedException.class)
        .hasMessageStartingWith("Verbindung getrennt: Für den Zugang \"Quelle\"")
        .satisfies(
            e ->
                assertThat(((SourceConnectionBlockedException) e).block().reason())
                    .isEqualTo(SourceBlock.Reason.NOT_CONNECTED));
  }

  @Test
  void discardClearsTheAttributeAndErasesTheColumn() {
    KnowledgeLibrary library = library("nutzer:geheim");

    secrets.discard(SecretOwner.of(null, library));

    assertThat(library.getSourceCredentials()).isNull();
    verify(libraries).eraseSourceCredentials(library.getId());
  }

  @Test
  void discardAllUnderErasesTheSecretOfEveryConnectedLibrary() {
    UUID profileId = UUID.randomUUID();
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    when(connections.findByProfileId(profileId))
        .thenReturn(
            List.of(
                new LibraryConnection(first, profileId, Instant.EPOCH),
                new LibraryConnection(second, profileId, Instant.EPOCH)));

    assertThat(secrets.discardAllUnder(profileId, ConnectionSecrets.DiscardCause.EMERGENCY))
        .isEqualTo(2);
    verify(libraries).eraseSourceCredentials(first);
    verify(libraries).eraseSourceCredentials(second);
  }

  private KnowledgeLibrary library(String secret) {
    KnowledgeLibrary library =
        KnowledgeLibrary.ownedByUser(
            UUID.randomUUID(),
            "Akten",
            null,
            UUID.randomUUID(),
            SourceTypes.RSS_FEED,
            null,
            "https://quelle.example.org/feed",
            null,
            secret,
            false);
    rows.put(library.getId(), library);
    return library;
  }
}
