package io.opaa.space;

import java.util.List;

/**
 * A space's associated assets, carrying the count-free state flags independently of the
 * possibly-filtered {@code items} - see {@link SpaceAssetAssociationService#listForSpace}. Domain
 * counterpart of the generated {@code SpaceAssetAssociationListResponse}.
 *
 * @param hasKnowledge at least one associated knowledge library, readable or not - without one a
 *     chat in the space searches nothing
 * @param hasReadableKnowledge at least one associated knowledge library the caller may read
 */
public record SpaceAssetLinks(
    boolean hasAssociations,
    boolean hasKnowledge,
    boolean hasReadableKnowledge,
    List<SpaceAssetLink> items) {}
