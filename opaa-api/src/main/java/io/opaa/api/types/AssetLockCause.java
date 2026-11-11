package io.opaa.api.types;

/** Why an agent or a document template is locked; either way it is kept, never deleted. */
public enum AssetLockCause {
  /** The system administration locked it, naming a reason. */
  MANUAL,
  /** Its responsible unit was missing for longer than the period the administration set. */
  NO_RESPONSIBLE
}
