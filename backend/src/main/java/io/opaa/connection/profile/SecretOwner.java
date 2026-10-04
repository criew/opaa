package io.opaa.connection.profile;

import io.opaa.knowledge.KnowledgeLibrary;
import java.util.Objects;
import java.util.UUID;

/**
 * Whose secret reaches a library's source, by identity only. {@link #of} is the one place that
 * chooses; a further owner, such as a person's connected account, is a further permitted type, a
 * further branch there and a compiler error at every {@code switch} in {@link ConnectionSecrets}.
 */
public sealed interface SecretOwner permits SecretOwner.LibraryOwned {

  /**
   * The owner of the secret {@code library} is reached with through the profile {@code profileId},
   * {@code null} for its own address.
   */
  static SecretOwner of(UUID profileId, KnowledgeLibrary library) {
    return new LibraryOwned(library.getId());
  }

  /** The library holds its own secret ({@code source_credentials}). */
  record LibraryOwned(UUID libraryId) implements SecretOwner {

    public LibraryOwned {
      Objects.requireNonNull(libraryId, "libraryId");
    }
  }
}
