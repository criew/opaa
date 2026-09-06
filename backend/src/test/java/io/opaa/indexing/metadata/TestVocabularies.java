package io.opaa.indexing.metadata;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The delivered Dokumentart vocabulary of migrations 018 and 020 as an in-memory snapshot for tests
 * that run without a database - codes, labels, synonyms, Kompositum endings and their exclusions
 * exactly as seeded. {@code DocumentTypeVocabularySeedReconciliationTest} fails as soon as this
 * snapshot and the Liquibase seed differ in a single value.
 */
public final class TestVocabularies {

  /** Minimum prefix length every seeded ending of migration 020 carries. */
  private static final int MIN_PREFIX_LENGTH = 3;

  private TestVocabularies() {}

  public static DocumentTypeVocabulary delivered() {
    return DocumentTypeVocabulary.of(deliveredEntries());
  }

  /** The seeded entries in {@code sort_order}, the order the repository snapshot reads them in. */
  public static List<DocumentTypeVocabularyEntry> deliveredEntries() {
    return List.of(
        entry(
            "SATZUNG_ORDNUNG",
            "Satzung/Ordnung",
            10,
            Set.of(
                "satzung",
                "satzungen",
                "ordnung",
                "hauptsatzung",
                "gebuehrensatzung",
                "gebuehrenordnung",
                "benutzungsordnung"),
            Set.of("satzung", "ordnung"),
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
        entry(
            "DIENSTANWEISUNG",
            "Dienstanweisung",
            20,
            Set.of("dienstanweisung", "dienstanweisungen", "dienstanordnung"),
            Set.of("dienstanweisung"),
            Set.of()),
        entry(
            "VERMERK",
            "Vermerk",
            30,
            Set.of("vermerk", "aktenvermerk"),
            Set.of("vermerk"),
            Set.of("sperrvermerk", "sichtvermerk", "eingangsvermerk")),
        entry(
            "PROTOKOLL",
            "Protokoll",
            40,
            Set.of("protokoll", "niederschrift", "sitzungsprotokoll", "ergebnisprotokoll"),
            Set.of("protokoll"),
            Set.of()),
        entry(
            "BESCHEID_VORLAGE",
            "Bescheid-Vorlage",
            50,
            Set.of("bescheid", "bescheidvorlage", "bescheidmuster", "musterbescheid"),
            Set.of(),
            Set.of()),
        entry(
            "FORMULAR",
            "Formular",
            60,
            Set.of("formular", "formulare", "antragsformular", "formblatt", "vordruck"),
            Set.of("formular"),
            Set.of()),
        entry(
            "GEBUEHRENVERZEICHNIS",
            "Gebührenverzeichnis",
            70,
            Set.of(
                "gebuehrenverzeichnis", "gebuehrentabelle", "gebuehrentarif", "kostenverzeichnis"),
            Set.of("gebuehrenverzeichnis"),
            Set.of()),
        entry(
            "PRAESENTATION",
            "Präsentation",
            80,
            Set.of("praesentation", "vortrag", "folien"),
            Set.of(),
            Set.of()),
        entry("SONSTIGES", "Sonstiges", 90, Set.of("sonstiges", "sonstige"), Set.of(), Set.of()));
  }

  private static DocumentTypeVocabularyEntry entry(
      String code,
      String label,
      int sortOrder,
      Set<String> synonyms,
      Set<String> suffixes,
      Set<String> suffixExclusions) {
    return new DocumentTypeVocabularyEntry(
        code,
        label,
        sortOrder,
        synonyms,
        suffixes.stream()
            .map(suffix -> new DocumentTypeSuffix(suffix, MIN_PREFIX_LENGTH))
            .collect(Collectors.toUnmodifiableSet()),
        suffixExclusions);
  }
}
