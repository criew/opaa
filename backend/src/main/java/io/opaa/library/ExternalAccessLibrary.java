package io.opaa.library;

/**
 * One row of the administration's Bestandsliste of released libraries (#1731) - the library and its
 * release side by side, so the list names what is released without the caller resolving each
 * library again.
 */
public record ExternalAccessLibrary(KnowledgeLibrary library, LibraryExternalAccess access) {}
