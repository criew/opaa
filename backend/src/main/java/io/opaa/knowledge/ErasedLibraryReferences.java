package io.opaa.knowledge;

import java.util.Collection;
import java.util.UUID;

/**
 * A holder of references to a library or its documents outside the bestand - a chat source, a space
 * association - that a private library's erasure removes in its own transaction, before the library
 * row goes. Implemented above knowledge; each answers for itself whether anything is left.
 */
public interface ErasedLibraryReferences {

  /** The neutral key the erasure proof counts this holder under, such as "chatSourcesRedacted". */
  String countKey();

  /**
   * Removes or neutralizes every reference to {@code erased}, so that none names it any more.
   *
   * @return how many references changed
   */
  int remove(ErasedLibrary erased);

  /** How many references to {@code erased} are left. */
  long remaining(ErasedLibrary erased);

  /** The library being erased, with the documents it held when the erasure began. */
  record ErasedLibrary(UUID organizationId, UUID libraryId, Collection<UUID> documentIds) {}
}
