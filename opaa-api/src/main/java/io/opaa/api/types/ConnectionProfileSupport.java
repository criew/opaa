package io.opaa.api.types;

/**
 * How a connector stands to connection profiles ("Profilangabe", ADR-0038 Nachtrag vom 03.10.2026):
 * reported in its descriptor, never configured.
 */
public enum ConnectionProfileSupport {
  /** No remote target, so no profile: upload and filesystem. */
  FORBIDDEN,
  /** A library chooses a profile or carries its own address. */
  OPTIONAL,
  /** Only with a profile. */
  REQUIRED
}
