package io.opaa.space;

import java.util.List;

/**
 * A space's associated assets as the caller may see them, carrying count-free state flags
 * independently of the filtered {@code items} - see {@link
 * SpaceAssetAssociationService#listForSpace}. Domain counterpart of the generated {@code
 * SpaceAssetAssociationListResponse}.
 *
 * @param hasUnreadableAssociations at least one association left out of {@code items} because the
 *     caller cannot read it - never how many, never which
 * @param narrowsSearch at least one associated knowledge library, readable or not - the rule the
 *     search applies; an asset of another type narrows nothing
 */
public record SpaceAssetLinks(
    boolean hasAssociations,
    boolean hasUnreadableAssociations,
    boolean narrowsSearch,
    List<SpaceAssetLink> items) {}
