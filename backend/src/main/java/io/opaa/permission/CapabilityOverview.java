package io.opaa.permission;

import io.opaa.api.types.Capability;
import java.util.List;

/**
 * One capability with every subject currently holding it - one entry per {@link Capability},
 * including the ones nobody holds, so the administration sees the delivered state rather than
 * discovering it later (ADR-0036, Entscheidung 5).
 */
public record CapabilityOverview(Capability capability, List<CapabilityGrantView> grants) {}
