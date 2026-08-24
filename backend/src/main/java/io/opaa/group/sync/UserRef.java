package io.opaa.group.sync;

import java.util.UUID;

/**
 * A user resolved from the directory or from an existing membership, carrying the display name a
 * sync report needs alongside the id. Domain counterpart of the generated {@code
 * DirectorySyncUserRef}, mapped by {@code io.opaa.api.DirectorySyncResponseMapper}.
 */
public record UserRef(UUID id, String displayName) {}
