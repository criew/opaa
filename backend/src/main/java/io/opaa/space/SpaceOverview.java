package io.opaa.space;

import io.opaa.api.types.SpaceRole;
import io.opaa.permission.SuccessionFinding;

/**
 * A space enriched with the overview card's figures (#682) - how many libraries and how many of the
 * caller's own chats it holds - and with the two derived values the card shows but the entity does
 * not carry: the caller's effective role (which since #1815 may come from a group) and the derived
 * state "Nachfolge offen" (ADR-0036, Entscheidung 6). Domain counterpart of the generated {@code
 * SpaceListResponse}, mapped by {@code io.opaa.api.SpaceResponseMapper}.
 */
public record SpaceOverview(
    Space space,
    int libraryCount,
    int chatCount,
    SpaceRole userRole,
    boolean successionOpen,
    SuccessionFinding succession) {}
