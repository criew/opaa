package io.opaa.permission;

import io.opaa.api.types.Capability;

/**
 * An installation switch that offers or withholds one {@link Capability} while the installation
 * runs, without a restart. The business package owning the switch contributes the bean; {@link
 * CapabilityService} asks every one on each evaluation, so the answer must be a cheap read of the
 * current state, never cached across instances (ADR-0053).
 *
 * <p>Withheld, the capability is neither held nor listed, exactly as for a switched-off feature
 * flag; its grants stay stored.
 */
public interface RuntimeCapabilitySwitch {

  /** The capability this switch offers or withholds. */
  Capability capability();

  /** Whether the installation offers the capability right now. */
  boolean offered();
}
