package io.opaa.space;

import io.opaa.api.types.SpaceRole;

/**
 * A space plus the values a detail response shows that the entity does not carry: the caller's
 * effective role - which may come from a group membership rather than a row of their own - the
 * derived state "Nachfolge offen" (ADR-0036, Entscheidung 6) and the installation-wide periods of
 * the automatic chat cleanup, and the instruction to the model the caller may read - none for
 * another person's personal space. Domain counterpart of the generated {@code SpaceResponse},
 * mapped by {@code SpaceResponseMapper}.
 */
public record SpaceDetail(
    Space space,
    SpaceRole userRole,
    boolean successionOpen,
    ChatAutoCleanupPeriods chatAutoCleanup,
    String modelInstruction) {}
