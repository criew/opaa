package io.opaa.permission;

import io.opaa.api.types.Capability;
import java.util.List;

/**
 * The scopes a scoped capability may be granted in, with their German names (ADR-0036, Nachtrag of
 * 03.10.2026). rights stores a scope and never interprets it; the module that owns the targets
 * implements this port.
 */
public interface CapabilityScopeCatalog {

  /** Every scope of {@code capability} known now, in display order; empty for an unscoped one. */
  List<CapabilityScope> scopes(Capability capability);

  /** One scope and its German name ("Quellart Confluence", "Zugang Nextcloud intern"). */
  record CapabilityScope(String value, String label) {}
}
