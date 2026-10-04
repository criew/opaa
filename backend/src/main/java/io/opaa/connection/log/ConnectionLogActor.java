package io.opaa.connection.log;

import java.util.Objects;
import java.util.UUID;

/**
 * Who caused a connection event: a person, written as their audit pseudonym, or a system process
 * (expiry, deactivation), written as the fixed label {@link #SYSTEM_LABEL}.
 */
public sealed interface ConnectionLogActor {

  /** The {@code actor_ref} of every event no person caused. */
  String SYSTEM_LABEL = "SYSTEM";

  static ConnectionLogActor person(UUID userId) {
    return new Person(Objects.requireNonNull(userId, "userId"));
  }

  static ConnectionLogActor system() {
    return SystemProcess.INSTANCE;
  }

  record Person(UUID userId) implements ConnectionLogActor {}

  enum SystemProcess implements ConnectionLogActor {
    INSTANCE
  }
}
