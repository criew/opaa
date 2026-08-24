package io.opaa.space;

/**
 * One space's view of one associated library (#203/#706), enriched with whether the caller may
 * themselves read it and, if so, the library's name - both resolved outside the association entity
 * itself. Domain counterpart of the generated {@code SpaceLibraryAssociationResponse}, mapped by
 * {@code io.opaa.api.SpaceLibraryAssociationResponseMapper}.
 */
public record SpaceLibraryLink(
    SpaceAssetAssociation association,
    boolean readableByCaller,
    String libraryName,
    String createdByDisplayName) {}
