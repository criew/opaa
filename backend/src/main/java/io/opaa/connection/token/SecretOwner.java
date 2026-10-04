package io.opaa.connection.token;

import io.opaa.api.types.ConnectionAuthMethod;
import io.opaa.knowledge.KnowledgeLibrary;
import java.util.Objects;
import java.util.UUID;

/**
 * Whose secret reaches a library's source, by identity only. {@link #of} is the one place that
 * chooses; a further owner is a further permitted type, a further branch there and a compiler error
 * at every {@code switch} in {@link ConnectionSecrets}.
 */
public sealed interface SecretOwner
    permits SecretOwner.LibraryOwned, SecretOwner.PersonOwned, SecretOwner.ProfileOwned {

  /**
   * The owner of the secret {@code library} is reached with through the profile {@code profileId}
   * ({@code null} for its own address) signing in by {@code method}: the profile itself for its own
   * sign-in (client credentials, service account key), the owner's connected account for an
   * owner-only (private) library, else the library itself; {@code NONE} names whose secret is
   * absent.
   */
  static SecretOwner of(UUID profileId, ConnectionAuthMethod method, KnowledgeLibrary library) {
    if (profileId == null || method == null) {
      return new LibraryOwned(library.getId());
    }
    return switch (method) {
      case CLIENT_CREDENTIALS, SERVICE_ACCOUNT_KEY -> new ProfileOwned(profileId);
      case NONE, PERSONAL_SECRET, OAUTH ->
          library.isOwnerOnly()
              ? new PersonOwned(profileId, library.getOwnerUserId())
              : new LibraryOwned(library.getId());
    };
  }

  /** The library holds its own secret ({@code source_credentials}). */
  record LibraryOwned(UUID libraryId) implements SecretOwner {

    public LibraryOwned {
      Objects.requireNonNull(libraryId, "libraryId");
    }
  }

  /** A person's connected account on a profile holds the secret. */
  record PersonOwned(UUID profileId, UUID userId) implements SecretOwner {

    public PersonOwned {
      Objects.requireNonNull(profileId, "profileId");
      Objects.requireNonNull(userId, "userId");
    }
  }

  /**
   * The profile signs in itself with its registration; no row of the store holds anything, the
   * access token is obtained through {@link SecretIssuer#mint}.
   */
  record ProfileOwned(UUID profileId) implements SecretOwner {

    public ProfileOwned {
      Objects.requireNonNull(profileId, "profileId");
    }
  }
}
