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
 * @param hasKnowledge at least one associated knowledge library, readable or not - without one a
 *     chat in the space searches nothing
 * @param hasReadableKnowledge at least one associated knowledge library the caller may read
 */
public record SpaceAssetLinks(
    boolean hasAssociations,
    boolean hasUnreadableAssociations,
    boolean hasKnowledge,
    boolean hasReadableKnowledge,
    List<SpaceAssetLink> items) {}
