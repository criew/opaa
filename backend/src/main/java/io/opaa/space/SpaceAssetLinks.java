package io.opaa.space;

import java.util.List;

/**
 * A space's associated assets (#706 review), carrying {@code hasAssociations} and {@code
 * narrowsSearch} independently of the possibly-filtered {@code items} - see {@link
 * SpaceAssetAssociationService#listForSpace}. Domain counterpart of the generated {@code
 * SpaceAssetAssociationListResponse}.
 *
 * @param narrowsSearch at least one associated knowledge library, readable or not - the rule the
 *     search applies; an asset of another type narrows nothing
 */
public record SpaceAssetLinks(
    boolean hasAssociations, boolean narrowsSearch, List<SpaceAssetLink> items) {}
