package io.opaa.group.sync;

import java.util.UUID;

/**
 * The one provider a synchronisation run is bound to (ADR-0036, Entscheidung 2, #1816). The run
 * reads its issuer from here and resolves the directory's subjects among the accounts of that
 * issuer only, so a provider group never contains an account of another provider; the groups it
 * creates carry {@link #providerId()} as their origin.
 *
 * <p>There is no target without a provider row: the {@code dev} mode has none, and the
 * synchronisation therefore does not run there at all - deliberately, rather than over a synthetic
 * provider row (#1816 decides this open point of ADR-0036, Entscheidung 11).
 */
public record SyncTarget(UUID organizationId, UUID providerId, String issuer, String displayName) {}
