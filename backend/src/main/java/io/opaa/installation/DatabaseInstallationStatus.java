package io.opaa.installation;

import io.opaa.common.InstallationStatus;
import org.springframework.stereotype.Component;

/**
 * {@link InstallationStatus} from the {@code installation_state} row (ADR-0053: "Datenbank"). While
 * pending, every question reads the row, so a completion on another instance takes effect with the
 * next request here; once a read finds it completed, the answer is latched for the process, because
 * nothing in the application sets the row back. A way back into the installation mode is an
 * operator's change in the database followed by a restart.
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
