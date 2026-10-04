package io.opaa.connection.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.types.ConnectionEndCause;
import io.opaa.connection.profile.LibraryRows;
import io.opaa.indexing.source.Secret;
import io.opaa.indexing.source.SecretKind;
import io.opaa.indexing.source.SourceBlock;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.test.SourceTypes;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The secret a library holds: handed out with its kind, its absence a reason, its discard. */
class ConnectionSecretsTest {

  private final LibrariesOnProfile onProfile = mock(LibrariesOnProfile.class);
  private final Map<UUID, KnowledgeLibrary> rows = new HashMap<>();
  private final KnowledgeLibraryRepository libraries = LibraryRows.over(rows);
  private final ConnectionSecrets secrets = TestSecrets.overLibraries(onProfile, libraries);

  @Test
  void aHeldSecretIsHandedOutAsAPersonalSecret() {
    SecretOwner owner = SecretOwner.of(null, null, library("nutzer:geheim"));

    assertThat(secrets.current(owner, "https://quelle.example.org"))
        .isEqualTo(new Secret(SecretKind.PERSONAL_SECRET, "nutzer:geheim"));
    assertThat(secrets.stateOf(owner)).isEmpty();
    assertThat(secrets.stored(owner)).isEqualTo("nutzer:geheim");
    assertThat(secrets.holds(owner)).isTrue();
  }

  @Test
  void aMissingSecretIsNotConnected() {
    SecretOwner owner = SecretOwner.of(null, null, library(null));

    assertThat(secrets.stateOf(owner)).contains(SourceBlock.Reason.NOT_CONNECTED);
    assertThat(secrets.stored(owner)).isNull();
    assertThat(secrets.holds(owner)).isFalse();
    assertThatThrownBy(() -> secrets.current(owner, "https://quelle.example.org"))
        .isInstanceOfSatisfying(
            SecretRefusedException.class,
            e -> assertThat(e.reason()).isEqualTo(SourceBlock.Reason.NOT_CONNECTED));
  }

  @Test
  void aSharedLibraryOnAProfileOwnsItsSecretAndAPrivateOneItsOwners() {
    UUID profileId = UUID.randomUUID();
    KnowledgeLibrary shared = library("nutzer:geheim");
    KnowledgeLibrary own =
        KnowledgeLibrary.ownerOnly(
            UUID.randomUUID(),
            "Meine Ablage",
            null,
            UUID.randomUUID(),
            SourceTypes.RSS_FEED,
            null,
            "https://quelle.example.org/feed",
            null,
            null,
            false);

    assertThat(SecretOwner.of(profileId, null, shared))
        .isEqualTo(new SecretOwner.LibraryOwned(shared.getId()));
    assertThat(SecretOwner.of(profileId, null, own))
        .isEqualTo(new SecretOwner.PersonOwned(profileId, own.getOwnerUserId()));
    assertThat(SecretOwner.of(null, null, own))
        .isEqualTo(new SecretOwner.LibraryOwned(own.getId()));
  }

  @Test
  void aLibrarysOwnSecretIsNeverStoredThroughTheStore() {
    SecretOwner owner = SecretOwner.of(null, null, library(null));

    assertThatThrownBy(() -> secrets.store(owner, NewSecret.personal("x"), "https://q.example"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void discardClearsTheAttributeAndErasesTheColumn() {
    KnowledgeLibrary library = library("nutzer:geheim");

    secrets.discard(SecretOwner.of(null, null, library));

    assertThat(library.getSourceCredentials()).isNull();
    verify(libraries).eraseSourceCredentials(library.getId());
  }

  @Test
  void discardAllUnderErasesTheSecretOfEveryConnectedLibrary() {
    UUID profileId = UUID.randomUUID();
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    when(onProfile.libraryIdsOnProfile(profileId)).thenReturn(List.of(first, second));

    assertThat(secrets.discardAllUnder(profileId, ConnectionEndCause.EMERGENCY).libraries())
        .isEqualTo(2);
    verify(libraries).eraseSourceCredentials(first);
    verify(libraries).eraseSourceCredentials(second);
  }

  @Test
  void neitherANewNorAStoredSecretShowsItsValue() {
    ConnectionToken token =
        ConnectionToken.ofAccount(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "enc:v1:geheim",
            null,
            "https://quelle.example.org",
            java.time.Instant.now());

    assertThat(NewSecret.personal("geheim").toString()).doesNotContain("geheim");
    assertThat(token.toString()).doesNotContain("geheim");
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
