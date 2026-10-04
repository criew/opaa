package io.opaa.connection.profile;

/**
 * What happens to the libraries of a connector type with their own address once the type is usable
 * only through a connection profile.
 */
public enum OwnAddressStock {
  /** They run on; their address changes only by connecting them through a profile. */
  RUNS,
  /** They keep their content but run no more until they are connected through a profile. */
  LOCKED
}
