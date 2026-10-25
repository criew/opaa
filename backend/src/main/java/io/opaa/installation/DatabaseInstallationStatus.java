package io.opaa.installation;

import io.opaa.common.InstallationStatus;
import org.springframework.stereotype.Component;

/**
 * {@link InstallationStatus} from {@code installation_state} (ADR-0053: "Datenbank"): read on every
 * question while pending, so a completion elsewhere counts with the next request, then latched for
 * the process. The way back is a change in the database and a restart.
 */
@Component
public class DatabaseInstallationStatus implements InstallationStatus {

  private final InstallationStateRepository states;
  private volatile boolean completed;

  public DatabaseInstallationStatus(InstallationStateRepository states) {
    this.states = states;
  }

  @Override
  public boolean isPending() {
    if (completed) {
      return false;
    }
    completed = states.isCompleted();
    return !completed;
  }

  /** Forgets the latch and reads the row again; the start-up calls it after its decision. */
  void reload() {
    completed = states.isCompleted();
  }
}
