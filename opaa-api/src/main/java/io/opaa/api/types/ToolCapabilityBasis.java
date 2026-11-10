package io.opaa.api.types;

/**
 * How a {@link ToolCapability} other than {@link ToolCapability#UNVERIFIED} was established: by a
 * measurement run, or set by hand. It names how the statement was checked, never who checked it.
 */
public enum ToolCapabilityBasis {
  MEASURED,
  MANUAL
}
