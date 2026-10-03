package io.opaa.permission;

import io.opaa.api.types.Capability;
import java.util.List;

/**
 * One capability with every subject currently holding it - one entry per {@link Capability},
 * including the ones nobody holds, so the administration sees the delivered state rather than
 * discovering it later (ADR-0036, Entscheidung 5). A scoped capability has one entry per scope.
 *
 * @param scope {@code null} for an unscoped capability
 * @param scopeLabel the German name of {@code scope}, {@code null} with it
 */
public record CapabilityOverview(
    Capability capability, String scope, String scopeLabel, List<CapabilityGrantView> grants) {}
