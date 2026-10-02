package io.opaa.asset;

import java.util.Set;
import java.util.UUID;

/**
 * The catalog's personal marks for a set of assets, as seen by one person.
 *
 * @param favorites the assets the person marked - never anybody else's mark.
 * @param fromMyGroups the readable assets granted to or owned by one of the person's groups.
 */
public record AssetCatalogMarks(Set<UUID> favorites, Set<UUID> fromMyGroups) {}
