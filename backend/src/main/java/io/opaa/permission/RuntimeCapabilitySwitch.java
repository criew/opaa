package io.opaa.permission;

import io.opaa.api.types.Capability;

/**
 * A capability that hangs on a switch the system administration turns at run time, stored in the
 * database rather than in the start-up configuration. While {@link #offered()} answers {@code
 * false}, the capability is neither held nor listed, exactly as for a switched-off feature
 * (ADR-0044); its grants stay stored. The business package owning the switch contributes the bean.
 */
public interface RuntimeCapabilitySwitch {

  /** The capability this switch governs. */
  Capability capability();

  /** Whether the installation offers the capability right now; asked per check, never cached. */
  boolean offered();
}
