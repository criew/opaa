package io.opaa.indexing.source;

import java.util.Objects;

/**
 * The port's refusal to hand out target and secret of a library (ADR-0041, Entscheidung 3): a
 * category and a German message that names who can resolve it and what happens to the content. A
 * run ends with the message before its body starts.
 */
public class SourceConnectionBlockedException extends RuntimeException {

  /** Why the connection is blocked. */
  public enum Category {
    /** The library's profile was deleted. */
    ACCESS_REMOVED,
    /** The profile asks for a secret the connection does not hold (yet or any more). */
    NOT_CONNECTED,
    /** The library's address does not lie under the server address of its profile. */
    TARGET_OUTSIDE_PROFILE,
    /** The system administration locked the library's connector type or profile. */
    LOCKED
  }

  private final Category category;

  public SourceConnectionBlockedException(Category category, String message) {
    super(message);
    this.category = Objects.requireNonNull(category, "category");
  }

  public Category category() {
    return category;
  }
}
