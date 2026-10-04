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
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The secret a library holds: handed out with its kind, its absence a reason, its discard. */
class ConnectionSecretsTest {

  private final LibraryConnectionRepository connections = mock(LibraryConnectionRepository.class);
  private final KnowledgeLibraryRepository libraries = mock(KnowledgeLibraryRepository.class);
  private final ConnectionSecrets secrets = new ConnectionSecrets(connections, libraries);

  @Test
  void aHeldSecretIsHandedOutAsAPersonalSecret() {
    SecretOwner owner = SecretOwner.of(library("nutzer:geheim"));

    assertThat(secrets.current(owner, "https://quelle.example.org"))
        .isEqualTo(new Secret(SecretKind.PERSONAL_SECRET, "nutzer:geheim"));
    assertThat(secrets.stateOf(owner)).isEmpty();
  }

  @Test
  void aMissingSecretIsNotConnected() {
    SecretOwner owner = SecretOwner.of(library(null));

    assertThat(secrets.stateOf(owner)).contains(SourceBlock.Reason.NOT_CONNECTED);
    assertThatThrownBy(() -> secrets.current(owner, "https://quelle.example.org"))
        .isInstanceOf(SourceConnectionBlockedException.class)
        .satisfies(
            e ->
                assertThat(((SourceConnectionBlockedException) e).block().reason())
                    .isEqualTo(SourceBlock.Reason.NOT_CONNECTED));
  }

  @Test
  void discardClearsTheAttributeAndErasesTheColumn() {
    KnowledgeLibrary library = library("nutzer:geheim");

    secrets.discard(SecretOwner.of(library));

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

  private static KnowledgeLibrary library(String secret) {
    return KnowledgeLibrary.ownedByUser(
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
  }
}
