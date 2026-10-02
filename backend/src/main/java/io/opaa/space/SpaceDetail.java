package io.opaa.space;

import io.opaa.api.types.SpaceRole;

/**
 * A space plus the values a detail response shows that the entity does not carry: the caller's
 * effective role - which since #1815 may come from a group membership rather than a row of their
 * own - the derived state "Nachfolge offen" (ADR-0036, Entscheidung 6) and the installation-wide
 * periods of the automatic chat cleanup. Domain counterpart of the generated {@code SpaceResponse},
 * mapped by {@code SpaceResponseMapper}.
 */
public record SpaceDetail(
    Space space,
    SpaceRole userRole,
    boolean successionOpen,
    ChatAutoCleanupProperties chatAutoCleanup) {}
