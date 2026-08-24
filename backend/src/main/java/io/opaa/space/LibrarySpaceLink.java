package io.opaa.space;

/**
 * One library's view of one space it is associated with - the owner-facing list (#203), enriched
 * with the space's name and whether at least one of its members cannot themselves read the library.
 * Domain counterpart of the generated {@code LibrarySpaceAssociationResponse}, mapped by {@code
 * io.opaa.api.SpaceLibraryAssociationResponseMapper}.
 */
public record LibrarySpaceLink(
    SpaceAssetAssociation association,
    String spaceName,
    boolean narrowerReaderCircle,
    String createdByDisplayName) {}
