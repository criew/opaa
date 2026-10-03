# Modul knowledge

Pakete (`io.opaa.*`): knowledge, llm, metadata, indexing. Die Konnektoren darunter bilden das Modul
connectors. Ergänzt `backend/AGENTS.md`; für den Konnektorvertrag in `indexing.source` gilt zusätzlich `indexing/source/AGENTS.md`.

## Zweck und Grenze

Der Bestand einer Wissensbibliothek — Bibliothek, Ordner, Dokumente, Zugriffsprüfung, Ablage der
Originale (`knowledge`) —, das Metadatenschema (`metadata`), der Weg vom Dokument in den Index
(`indexing`) und die verwalteten Chat-Modelle (`llm`). knowledge hängt nur von foundation, format,
identity und rights ab.

## Invarianten und Stolpersteine

- **`knowledge` liegt unter `indexing` und `library` und nennt keines von beiden.** Was es von oben
  braucht, deklariert es als Schnittstelle, die das obere Paket implementiert
  (`FolderDocumentDeleter`).
- **`metadata` liegt zwischen `knowledge` und `indexing`** (Schema, Kernfelder, Vokabular,
  Extraktion, Korrektur, Filter, Kontextpräfix) und kennt keine Pipeline-Klasse. Den Chunk-Store und
  den Nachlauf erreicht es über `ChunkMetadataStore` und `ContextPrefixBacklog`. Bestandslauf und
  Nachlauf selbst liegen in `indexing.maintenance`.
- **Ein Aufnahmeweg:** Jede Quelle und jeder Upload geht durch `DocumentIngestService#ingest`
  (parsen, schneiden, speichern, markieren). Die `Document`-Zeile gehört `knowledge`.
- **Richtung im Kern von `indexing`** (`ModularArchitecture.INDEXING_CORE`): `chunk`, `job` (Lauf,
  Protokoll), `attachment` (Übergabe an den Anhangspfad), `document` (Aufnahme samt Anhängen),
  `source` (Vertrag, Laufrahmen, Auslöser), `maintenance`, `filesync` (Datei-Abgleich, ADR-0040,
  kennt keinen Konnektor und keinen Anbieter); darüber Wurzel, `web` und Konnektoren. Rückwege
  sind Ports: `VanishedDocumentReconciler`, Properties als Schnittstellen (`EmbeddingBatching`).
- **Formate** liegen im Modul format (`io.opaa.format`, siehe `format/AGENTS.md`).
  `FormatConfiguration` registriert die Dateiformate als Beans; `DocumentIngestService` lehnt ein
  Format ab, das einen Schemaschlüssel als Passthrough-Schlüssel deklariert.
- **`IndexingConfiguration` verdrahtet den Kern und kennt keinen Konnektor**; kein Unterpaket
  nennt das Wurzelpaket `indexing`.
- **Chat-Modelle:** `LlmModelService` ist der einzige Einstieg. Der optionale API-Schlüssel wird
  über `SettingsEncryptor` verschlüsselt, bevor die Datenbank ihn sieht; jede Änderung wird
  protokolliert. Genau ein Modell ist systemweit aktiv.
- **Web-Schicht:** `llm.web` (Modellverwaltung), `indexing.web` (Indexierungsverwaltung, Bestands-
  und Nachlauf), `metadata.web` (Dokument- und Bibliotheksmetadaten, der Metadatenfilter aller
  Such- und Chat-Anfragen samt strengem `MetadataFilterDeserializer`). Die Endpunkte der Bibliothek
  liegen in `library.web`.

## Verweise

- ADRs (`docs/decisions/`): 0017, 0018, 0020, 0022, 0024, 0026, 0028, 0030, 0040
- Handbuch: `docs/handbuch/indexierung.md`, `docs/handbuch/metadaten.md`
- Strukturtests: `ModularArchitectureTest`

## Tests bei Änderungen

```bash
./gradlew test -PtestShard=indexing      # indexing, format, metadata, llm
./gradlew test --tests 'io.opaa.knowledge.*' --tests 'io.opaa.architecture.*'
```

Bei Schemaänderungen: neue Datei unter `db/changelog/knowledge/`. Regeln und
Tests in `backend/AGENTS.md`, „Liquibase: Changelog je Modul“ — ein eigener Delta-Test ist nur
für rein additive DDL entbehrlich.
Die beiden `vector_store`-Ausdrucksindexe der Baseline bleiben eigenständige, precondition-geschützte
Changesets. Ändert sich Schnitt, Einbettung oder Metadaten, misst `./gradlew evaluateRetrieval`
die Suchqualität (braucht Docker, nicht Teil von `build`; siehe `eval/README.md`).
