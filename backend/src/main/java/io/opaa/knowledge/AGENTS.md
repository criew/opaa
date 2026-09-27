# Modul knowledge

Pakete (`io.opaa.*`): knowledge, llm, indexing. Die Konnektoren darunter bilden das Modul
connectors. Ergänzt `backend/AGENTS.md`; für den Konnektorvertrag in `indexing.source` gilt zusätzlich `indexing/source/AGENTS.md`.

## Zweck und Grenze

Der Bestand einer Wissensbibliothek — Bibliothek, Ordner, Dokumente, Zugriffsprüfung, Ablage der
Originale (`knowledge`) —, der Weg vom Dokument in den Index (`indexing`) und die verwalteten
Chat-Modelle (`llm`). knowledge hängt nur von foundation, format, identity und rights ab.

## Invarianten und Stolpersteine

- **`knowledge` liegt unter `indexing` und `library` und nennt keines von beiden.** Was es von oben
  braucht, deklariert es als Schnittstelle, die das obere Paket implementiert
  (`FolderDocumentDeleter`).
- **Ein Aufnahmeweg:** Jede Quelle und jeder Upload geht durch `DocumentIngestService#ingest`
  (parsen, schneiden, speichern, markieren). Die `Document`-Zeile gehört `knowledge`.
- **`indexing.job` besitzt den Lauf** (Zeile, Lebenszyklus, Protokoll, Zeitpläne) und weiß nichts
  von Parsen, Schneiden oder Chunk-Speichern. `maintenance` hängt von `document` ab, nie umgekehrt.
- **Formate** liegen im Modul format (`io.opaa.format`, siehe `format/AGENTS.md`).
  `IndexingConfiguration` registriert jedes Format als Bean; `DocumentIngestService` lehnt ein
  Format ab, das einen Schemaschlüssel als Passthrough-Schlüssel deklariert.
- **`IndexingConfiguration` verdrahtet den Kern und kennt keinen Konnektor**; die Unterpakete
  kennen voneinander nichts über das Wurzelpaket `indexing`.
- **Zyklen zwischen den Unterpaketen von `indexing`** sind in
  `ModularArchitecture.KNOWN_SUBPACKAGE_CYCLE_EDGES` eingefroren. Jede weitere Kante auf einem
  Zyklus lässt `ModularArchitectureTest` fehlschlagen.
- **Chat-Modelle:** `LlmModelService` ist der einzige Einstieg. Der optionale API-Schlüssel wird
  über `SettingsEncryptor` verschlüsselt, bevor die Datenbank ihn sieht; jede Änderung wird
  protokolliert. Genau ein Modell ist systemweit aktiv.
- **Web-Schicht:** `llm.web` (Modellverwaltung), `indexing.web` (Indexierungsverwaltung,
  Dokument- und Bibliotheksmetadaten, der Metadatenfilter aller Such- und Chat-Anfragen samt
  strengem `MetadataFilterDeserializer`). Die Endpunkte der Bibliothek liegen in `library.web`.

## Verweise

- ADRs (`docs/decisions/`): 0017, 0018, 0020, 0022, 0024, 0026, 0028, 0030
- Handbuch: `docs/handbuch/indexierung.md`, `docs/handbuch/metadaten.md`
- Strukturtests: `ModularArchitectureTest`

## Tests bei Änderungen

```bash
./gradlew test -PtestShard=indexing      # io.opaa.indexing.*, io.opaa.format.*, io.opaa.llm.*
./gradlew test --tests 'io.opaa.knowledge.*' --tests 'io.opaa.architecture.*'
```

Bei Schemaänderungen: neue Datei unter `db/changelog/knowledge/` mit eigenem Delta-Test
(`MasterChangelog.filesExcept(...)`), Regeln in `backend/AGENTS.md`, „Liquibase: Changelog je
Modul“; dazu `ChangelogLayoutTest`, `ChangelogModuleBoundaryTest`, `ChangelogOrderTest`.
Die beiden `vector_store`-Ausdrucksindexe der Baseline bleiben eigenständige, precondition-geschützte
Changesets. Ändert sich Schnitt, Einbettung oder Metadaten, misst `./gradlew evaluateRetrieval`
die Suchqualität (braucht Docker, nicht Teil von `build`; siehe `eval/README.md`).
