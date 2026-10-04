package io.opaa.library;

import java.util.UUID;

/**
 * A private library was erased with its whole content; published in the erasing transaction, so a
 * cache holding anything of it listens after the commit.
 */
public record PrivateLibraryErased(UUID organizationId, UUID libraryId, UUID ownerUserId) {}
