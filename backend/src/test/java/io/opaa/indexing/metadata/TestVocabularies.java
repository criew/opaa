package io.opaa.indexing.metadata;

import java.util.List;
import java.util.Set;

/**
 * The delivered Dokumentart vocabulary of migration 018, as an in-memory snapshot for unit tests.
 */
final class TestVocabularies {

  private TestVocabularies() {}

  static DocumentTypeVocabulary delivered() {
    return DocumentTypeVocabulary.of(
        List.of(
            entry("SATZUNG_ORDNUNG", "Satzung/Ordnung", 10, "satzung", "gebuehrenordnung"),
            entry("DIENSTANWEISUNG", "Dienstanweisung", 20, "dienstanweisung", "dienstanordnung"),
            entry("VERMERK", "Vermerk", 30, "vermerk", "aktenvermerk"),
            entry("PROTOKOLL", "Protokoll", 40, "protokoll", "niederschrift"),
            entry("BESCHEID_VORLAGE", "Bescheid-Vorlage", 50, "bescheid", "bescheidvorlage"),
            entry("FORMULAR", "Formular", 60, "formular", "formblatt"),
            entry("GEBUEHRENVERZEICHNIS", "Gebührenverzeichnis", 70, "gebuehrenverzeichnis"),
            entry("PRAESENTATION", "Präsentation", 80, "praesentation", "vortrag"),
            entry("SONSTIGES", "Sonstiges", 90, "sonstiges")));
  }

  private static DocumentTypeVocabularyEntry entry(
      String code, String label, int sortOrder, String... synonyms) {
    return new DocumentTypeVocabularyEntry(code, label, sortOrder, Set.of(synonyms));
  }
}
