package io.opaa.config;

/**
 * A database prerequisite of the schema migration is missing. Carries what is missing and the
 * statement that fixes it, which {@link DatabasePrerequisiteFailureAnalyzer} turns into the startup
 * report.
 */
public class DatabasePrerequisiteException extends RuntimeException {

  private final String action;

  public DatabasePrerequisiteException(String description, String action) {
    super(description);
    this.action = action;
  }

  public String getAction() {
    return action;
  }
}
