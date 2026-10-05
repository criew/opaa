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
    permits SecretOwner.LibraryOwned,
        SecretOwner.PersonOwned,
        SecretOwner.ProfileOwned,
        SecretOwner.SourceConsent,
        SecretOwner.PendingConsent {

  /**
   * The owner of the secret {@code library} is reached with through the profile {@code profileId}
   * ({@code null} for its own address) signing in by {@code method}: the owner's connected account
   * for an owner-only (private) library whatever the method - it never gets a profile's token -,
   * else the profile itself for its own sign-in (client credentials, service account key), the
   * library's own consent in the store for OAuth, else the library itself; {@code NONE} names whose
   * secret is absent.
   */
  static SecretOwner of(UUID profileId, ConnectionAuthMethod method, KnowledgeLibrary library) {
    if (profileId == null || method == null) {
      return new LibraryOwned(library.getId());
    }
    if (library.isOwnerOnly()) {
      return new PersonOwned(profileId, library.getOwnerUserId());
    }
    return switch (method) {
      case CLIENT_CREDENTIALS, SERVICE_ACCOUNT_KEY -> new ProfileOwned(profileId);
      case OAUTH -> new SourceConsent(profileId, library.getId());
      case NONE, PERSONAL_SECRET -> new LibraryOwned(library.getId());
    };
  }

  /**
   * Whether the secret belongs to one library alone - its column or its own consent - so that a
   * move or a release of that library may discard it; a person's or a profile's never does.
   */
  default boolean ownedByOneLibrary() {
    return this instanceof LibraryOwned || this instanceof SourceConsent;
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

  /**
   * A library's own OAuth consent ("Quelle verbinden") through the profile {@code profileId}, held
   * in the store with the library as owner; it outlasts the person who gave it.
   */
  record SourceConsent(UUID profileId, UUID libraryId) implements SecretOwner {

    public SourceConsent {
      Objects.requireNonNull(profileId, "profileId");
      Objects.requireNonNull(libraryId, "libraryId");
    }
  }

  /**
   * A consent {@code userId} gave for a library that does not exist yet, held in the store row
   * {@code tokenId} for that person alone until the library takes it over or it expires.
   */
  record PendingConsent(UUID tokenId, UUID userId) implements SecretOwner {

    public PendingConsent {
      Objects.requireNonNull(tokenId, "tokenId");
      Objects.requireNonNull(userId, "userId");
    }
  }
}
