package io.opaa.connection.log;

import io.opaa.api.types.ConnectionLogOwnerKind;
import java.util.Objects;
import java.util.UUID;

/**
 * Whose connection an entry is about, mirroring {@code chk_connection_log_owner}: a person (written
 * as pseudonym, never with a library or account name), a library with the optional address of its
 * service account, or the profile itself. The connection of an owner-only (private) library is
 * always a {@link Person}'s, never a {@link Library}'s: otherwise the entry would carry the
 * person's library and account name.
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

  /**
   * A shared library's source connection. {@code accountLabel} may be null; one longer than {@link
   * #MAX_ACCOUNT_LABEL_LENGTH} is refused by {@code ConnectionLog#record}, never cut.
   */
  record Library(UUID libraryId, String accountLabel) implements ConnectionLogOwner {

    /** Matches {@code connection_log.account_label varchar(500)}. */
    public static final int MAX_ACCOUNT_LABEL_LENGTH = 500;

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
