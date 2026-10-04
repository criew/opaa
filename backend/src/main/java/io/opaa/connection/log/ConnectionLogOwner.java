package io.opaa.connection.log;

import io.opaa.api.types.ConnectionLogOwnerKind;
import java.util.Objects;
import java.util.UUID;

/**
 * Whose connection an entry is about, mirroring {@code chk_connection_log_owner}: a person (written
 * as pseudonym, never with a library or account name), a library with the optional address of its
 * service account, or the profile itself.
 */
public sealed interface ConnectionLogOwner {

  ConnectionLogOwnerKind kind();

  static ConnectionLogOwner person(UUID userId) {
    return new Person(userId);
  }

  static ConnectionLogOwner library(UUID libraryId, String accountLabel) {
    return new Library(libraryId, accountLabel);
  }

  static ConnectionLogOwner profile() {
    return Profile.INSTANCE;
  }

  record Person(UUID userId) implements ConnectionLogOwner {
    public Person {
      Objects.requireNonNull(userId, "userId");
    }

    @Override
    public ConnectionLogOwnerKind kind() {
      return ConnectionLogOwnerKind.PERSON;
    }
  }

  /** {@code accountLabel} may be null when the service account has no address to show. */
  record Library(UUID libraryId, String accountLabel) implements ConnectionLogOwner {
    public Library {
      Objects.requireNonNull(libraryId, "libraryId");
    }

    @Override
    public ConnectionLogOwnerKind kind() {
      return ConnectionLogOwnerKind.LIBRARY;
    }
  }

  enum Profile implements ConnectionLogOwner {
    INSTANCE;

    @Override
    public ConnectionLogOwnerKind kind() {
      return ConnectionLogOwnerKind.PROFILE;
    }
  }
}
