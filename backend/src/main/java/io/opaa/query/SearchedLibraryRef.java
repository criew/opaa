package io.opaa.query;

import java.util.UUID;

/** One library the retrieval actually searched for a turn, by id and name. */
public record SearchedLibraryRef(UUID id, String name) {}
