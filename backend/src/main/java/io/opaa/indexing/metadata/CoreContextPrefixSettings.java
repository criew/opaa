package io.opaa.indexing.metadata;

import io.opaa.library.KnowledgeLibrary;

/**
 * Which core fields belong into a library's Kontextpraefix. {@code title} is always {@code true}:
 * the Kernfeld Titel replaces the file-name humanisation the prefix used before, and a prefix
 * without it would name nothing. The other two are switchable per library, off by default - the
 * Wirkstelle "Kontextpraefix" is a deliberate decision per field, never a default for all of them.
 */
public record CoreContextPrefixSettings(boolean title, boolean documentType, boolean documentDate) {

  public static CoreContextPrefixSettings of(KnowledgeLibrary library) {
    return new CoreContextPrefixSettings(
        true, library.isCoreContextPrefixDocumentType(), library.isCoreContextPrefixDocumentDate());
  }
}
