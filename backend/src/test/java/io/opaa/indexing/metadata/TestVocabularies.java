package io.opaa.indexing.metadata;

import java.util.List;
import java.util.Set;

/**
 * The delivered Dokumentart vocabulary of migrations 018 and 020, as an in-memory snapshot for unit
 * tests - synonyms, Kompositum endings and their exclusions exactly as seeded.
 */
final class TestVocabularies {

  private TestVocabularies() {}

  static DocumentTypeVocabulary delivered() {
    return DocumentTypeVocabulary.of(
        List.of(
            new DocumentTypeVocabularyEntry(
                "SATZUNG_ORDNUNG",
                "Satzung/Ordnung",
                10,
                Set.of("satzung", "gebuehrenordnung"),
                Set.of(suffix("satzung"), suffix("ordnung")),
                Set.of(
                    "anordnung",
                    "zuordnung",
                    "neuzuordnung",
                    "einordnung",
                    "neuordnung",
                    "umordnung",
                    "unterordnung",
                    "rangordnung",
                    "sitzordnung",
                    "tagesordnung",
                    "groessenordnung")),
            new DocumentTypeVocabularyEntry(
                "DIENSTANWEISUNG",
                "Dienstanweisung",
                20,
                Set.of("dienstanweisung", "dienstanordnung"),
                Set.of(suffix("dienstanweisung")),
                Set.of()),
            new DocumentTypeVocabularyEntry(
                "VERMERK",
                "Vermerk",
                30,
                Set.of("vermerk", "aktenvermerk"),
                Set.of(suffix("vermerk")),
                Set.of("sperrvermerk", "sichtvermerk", "eingangsvermerk")),
            entry("PROTOKOLL", "Protokoll", 40, Set.of("protokoll", "niederschrift"), "protokoll"),
            entry(
                "BESCHEID_VORLAGE",
                "Bescheid-Vorlage",
                50,
                Set.of("bescheid", "bescheidvorlage"),
                null),
            entry("FORMULAR", "Formular", 60, Set.of("formular", "formblatt"), "formular"),
            entry(
                "GEBUEHRENVERZEICHNIS",
                "Gebührenverzeichnis",
                70,
                Set.of("gebuehrenverzeichnis"),
                "gebuehrenverzeichnis"),
            entry("PRAESENTATION", "Präsentation", 80, Set.of("praesentation", "vortrag"), null),
            entry("SONSTIGES", "Sonstiges", 90, Set.of("sonstiges"), null)));
  }

  private static DocumentTypeVocabularyEntry entry(
      String code, String label, int sortOrder, Set<String> synonyms, String suffix) {
    return new DocumentTypeVocabularyEntry(
        code,
        label,
        sortOrder,
        synonyms,
        suffix == null ? Set.of() : Set.of(suffix(suffix)),
        Set.of());
  }

  /** Every seeded ending uses the delivered minimum prefix length of migration 020. */
  private static DocumentTypeSuffix suffix(String suffix) {
    return new DocumentTypeSuffix(suffix, 3);
  }
}
