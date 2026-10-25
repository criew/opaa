package io.opaa.settings;

import java.util.List;

/**
 * What an import changed ({@code applied}) or would change (the preview): one {@link Change} per
 * key whose value differs, in the order of the sections; empty when the document matches.
 */
public record SettingsImportResult(boolean applied, List<Change> changes) {

  public SettingsImportResult {
    changes = List.copyOf(changes);
  }

  /** One changed key, both values in the form the export writes ({@code null} allowed). */
  public record Change(
      String section, String sectionLabel, String key, Object before, Object after) {}
}
