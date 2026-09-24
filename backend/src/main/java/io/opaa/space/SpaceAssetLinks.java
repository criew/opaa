package io.opaa.space;

import java.util.List;

/**
 * A space's associated assets (#706 review), carrying {@code hasAssociations} independently of the
 * possibly-filtered {@code items} - see {@link SpaceAssetAssociationService#listForSpace}. Domain
 * counterpart of the generated {@code SpaceAssetAssociationListResponse}.
 */
public record SpaceAssetLinks(boolean hasAssociations, List<SpaceAssetLink> items) {}
