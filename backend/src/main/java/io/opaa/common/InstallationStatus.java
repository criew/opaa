package io.opaa.common;

/**
 * Whether the installation of this OPAA instance is still pending (ADR-0060, Entscheidung 4). The
 * answer comes from the database, so it is the same on every instance; once the installation is
 * completed it stays completed for the life of the process.
 */
public interface InstallationStatus {

  /** True until the installation is completed. */
  boolean isPending();
}
