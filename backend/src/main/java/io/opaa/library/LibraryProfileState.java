package io.opaa.library;

import java.util.UUID;

/**
 * The connection profile of a library as its detail shows it; {@code removed} once the profile was
 * deleted, with id and name then {@code null} ("Zugang entfernt").
 */
public record LibraryProfileState(UUID id, String name, boolean removed) {}
