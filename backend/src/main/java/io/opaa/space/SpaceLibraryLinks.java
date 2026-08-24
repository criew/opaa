package io.opaa.space;

import java.util.List;

/**
 * A space's associated libraries (#706 review), carrying {@code hasAssociations} independently of
 * the possibly-filtered {@code items} - see {@link SpaceAssetAssociationService#listForSpace} for
 * why the two must stay independent. Domain counterpart of the generated {@code
 * SpaceLibraryAssociationListResponse}, mapped by {@code
 * io.opaa.api.SpaceLibraryAssociationResponseMapper} (ADR-0006/#860).
 */
public record SpaceLibraryLinks(boolean hasAssociations, List<SpaceLibraryLink> items) {}
