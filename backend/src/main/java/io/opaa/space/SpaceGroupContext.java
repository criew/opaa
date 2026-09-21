package io.opaa.space;

import java.util.Set;
import java.util.UUID;

/**
 * What a Rechteprofil-Lauf in einem Space braucht (#1835, ADR-0036 Entscheidung 7): the libraries
 * associated with the space, and how many active accounts of the profile group reach that space on
 * <em>any</em> path. Counted against "reaches the space at all" rather than "is in it through this
 * group": a group that is itself no space member would otherwise count zero, and the protection
 * would be structurally unreachable.
 */
public record SpaceGroupContext(
    UUID spaceId, String spaceName, Set<UUID> libraryIds, int activeMembersWithSpaceAccess) {}
