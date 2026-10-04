package io.opaa.connection.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.types.ConnectionAuthMethod;
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

    assertThat(SecretOwner.of(profileId, ConnectionAuthMethod.PERSONAL_SECRET, shared))
        .isEqualTo(new SecretOwner.LibraryOwned(shared.getId()));
    assertThat(SecretOwner.of(profileId, ConnectionAuthMethod.PERSONAL_SECRET, own))
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

  /**
   * Client credentials and a service account key are the profile's: its token comes from the
   * issuer, the library holds and stores nothing, and discarding under the profile drops the token
   * held in the process.
   */
  @Test
  void aProfilesOwnSignInIsTheProfilesAndItsTokenComesFromTheIssuer() {
    UUID profileId = UUID.randomUUID();
    KnowledgeLibrary library = library(null);
    SecretIssuer issuer = mock(SecretIssuer.class);
    Secret token = new Secret(SecretKind.ACCESS_TOKEN, "token-des-zugangs");
    when(issuer.mint(profileId)).thenReturn(token);
    ConnectionSecrets withIssuer = TestSecrets.overLibraries(onProfile, libraries, issuer);
    SecretOwner owner =
        SecretOwner.of(profileId, ConnectionAuthMethod.SERVICE_ACCOUNT_KEY, library);

    assertThat(owner).isEqualTo(new SecretOwner.ProfileOwned(profileId));
    assertThat(SecretOwner.of(profileId, ConnectionAuthMethod.CLIENT_CREDENTIALS, library))
        .isEqualTo(owner);
    assertThat(withIssuer.current(owner, null)).isEqualTo(token);
    assertThat(withIssuer.afterRejection(owner, null)).isEqualTo(token);
    assertThat(withIssuer.stateOf(owner)).isEmpty();
    assertThat(withIssuer.stored(owner)).isNull();
    assertThat(withIssuer.holds(owner)).isFalse();
    assertThatThrownBy(() -> withIssuer.store(owner, NewSecret.personal("x"), null))
        .isInstanceOf(IllegalStateException.class);

    withIssuer.discardAllUnder(profileId, ConnectionEndCause.EMERGENCY);
    withIssuer.rejected(owner);

    verify(issuer, times(3)).forgetMinted(profileId);
  }

  /**
   * A private library is its owner's, whatever the profile signs in with: never a profile token.
   */
  @Test
  void aPrivateLibraryNeverGetsTheProfilesOwnSignIn() {
    UUID profileId = UUID.randomUUID();
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

    for (ConnectionAuthMethod method :
        List.of(
            ConnectionAuthMethod.SERVICE_ACCOUNT_KEY, ConnectionAuthMethod.CLIENT_CREDENTIALS)) {
      assertThat(SecretOwner.of(profileId, method, own))
          .isEqualTo(new SecretOwner.PersonOwned(profileId, own.getOwnerUserId()));
    }
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
