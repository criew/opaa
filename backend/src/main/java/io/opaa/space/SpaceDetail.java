package io.opaa.space;

import io.opaa.api.types.SpaceRole;

/**
 * A space plus the two values a detail response shows that the entity does not carry: the caller's
 * effective role - which since #1815 may come from a group membership rather than a row of their
 * own - and the derived state "Nachfolge offen" (ADR-0036, Entscheidung 6). Domain counterpart of
 * the generated {@code SpaceResponse}, mapped by {@code io.opaa.api.SpaceResponseMapper}.
 */
public record SpaceDetail(Space space, SpaceRole userRole, boolean successionOpen) {}
