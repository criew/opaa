package io.opaa.knowledge;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Whose working files a store writes under a directory several instances may share (ADR-0053,
 * Entscheidung 8): each instance writes below a subdirectory named after its identity, and a sweep
 * removes only the subdirectories of instances that are dead.
 *
 * @param instance the identity of this instance, asked when a working file is created
 * @param alive whether the instance of a subdirectory is alive
 */
public record WorkingDirectoryOwner(Supplier<UUID> instance, Predicate<UUID> alive) {

  public WorkingDirectoryOwner {
    Objects.requireNonNull(instance, "instance");
    Objects.requireNonNull(alive, "alive");
  }

  /** A store used on its own: one identity of its own, every other one counts as dead. */
  public static WorkingDirectoryOwner standalone() {
    UUID own = UUID.randomUUID();
    return new WorkingDirectoryOwner(() -> own, own::equals);
  }
}
