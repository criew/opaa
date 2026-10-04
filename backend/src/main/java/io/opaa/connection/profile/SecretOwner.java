package io.opaa.connection.profile;

import io.opaa.knowledge.KnowledgeLibrary;
import java.util.Objects;

/**
 * Whose secret reaches a library's source. {@link #of} is the one place that chooses; a further
 * owner, such as a person's connected account, is a further permitted type and a further branch
 * there.
 */
public sealed interface SecretOwner permits SecretOwner.LibraryOwned {

  /** The owner of the secret {@code library} is reached with. */
  static SecretOwner of(KnowledgeLibrary library) {
    return new LibraryOwned(library);
  }

  /** The library holds its own secret ({@code source_credentials}). */
  record LibraryOwned(KnowledgeLibrary library) implements SecretOwner {

    public LibraryOwned {
      Objects.requireNonNull(library, "library");
    }
  }
}
