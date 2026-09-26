package io.opaa.indexing.metadata;

import io.opaa.knowledge.KnowledgeLibrary;
import java.util.ArrayList;
import java.util.List;

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

  /**
   * The core-field segments these settings put into the prefix, in schema order: the Dokumentart
   * label, then the date at its own precision. The title is not among them - it leads the prefix.
   */
  public List<String> coreValues(CoreMetadata core) {
    List<String> values = new ArrayList<>();
    if (documentType && core.documentTypeLabel() != null && !core.documentTypeLabel().isBlank()) {
      values.add(core.documentTypeLabel());
    }
    if (documentDate && core.documentDate() != null) {
      values.add(
          MetadataValueDisplay.displayDate(core.documentDate(), core.documentDatePrecision()));
    }
    return values;
  }
}
