# Modul knowledge

Pakete (`io.opaa.*`): knowledge, llm, metadata, indexing, health. Die Konnektoren unter `indexing`
bilden das Modul connectors. Ergänzt `backend/AGENTS.md`; für den Konnektorvertrag in `indexing.source` gilt zusätzlich `indexing/source/AGENTS.md`.

## Zweck und Grenze

Der Bestand einer Wissensbibliothek — Bibliothek, Ordner, Dokumente, Zugriffsprüfung, Ablage der Originale (`knowledge`) —,
das Metadatenschema (`metadata`), der Weg vom Dokument in den Index (`indexing`), die verwalteten Chat-Modelle (`llm`) und
die Health-Indikatoren für Modelle und Vektorindex (`health`). knowledge hängt nur von foundation, format, identity und rights ab.

## Invarianten und Stolpersteine

- **`knowledge` liegt unter `indexing` und `library` und nennt keines davon.** Ports nach oben: `FolderDocumentDeleter`;
  in connections implementiert `SourceConnectionResolver` (Ziel, Geheimnis; nur knowledge, library, connections, `theSecretPortStaysWithTheCore`) und `SourceStateLookup`.
- **`metadata` liegt zwischen `knowledge` und `indexing`** (Schema, Kernfelder, Vokabular, Extraktion, Korrektur, Filter,
  Kontextpräfix) und kennt keine Pipeline-Klasse. Den Chunk-Store und den Nachlauf erreicht es über `ChunkMetadataStore` und
  `ContextPrefixBacklog`. Bestandslauf und Nachlauf selbst liegen in `indexing.maintenance`.
- **Ein Aufnahmeweg:** Jede Quelle und jeder Upload geht durch `DocumentIngestService#ingest` (parsen, schneiden, speichern, markieren). Die `Document`-Zeile gehört `knowledge`.
- **Modellwechsel:** Passt der Vektorindex nicht zum Embedding-Modell (`EmbeddingIndexState`), schreibt `VectorStoreWriter` in `embedding_next`; `EmbeddingReindexService` bettet neu ein und schaltet um.
- **Richtung im Kern von `indexing`** (`ModularArchitecture.INDEXING_CORE`): `chunk`, `job` (Lauf, Protokoll), `attachment`
  (Übergabe an den Anhangspfad), `document` (Aufnahme samt Anhängen), `source` (Vertrag, Laufrahmen, Auslöser), `maintenance`,
  `filesync` (Datei-Abgleich, ADR-0045, kennt keinen Konnektor und keinen Anbieter); darüber Wurzel, `web` und Konnektoren.
  Rückwege sind Ports: `VanishedDocumentReconciler`, Properties als Schnittstellen (`EmbeddingBatching`).
- **Formate** liegen im Modul format (`io.opaa.format`, siehe `format/AGENTS.md`). `FormatConfiguration` registriert die
  Dateiformate als Beans; `DocumentIngestService` lehnt ein Format ab, das einen Schemaschlüssel als Passthrough-Schlüssel deklariert.
- **`IndexingConfiguration` verdrahtet den Kern und kennt keinen Konnektor**; kein Unterpaket nennt das Wurzelpaket `indexing`.
- **Chat-Modelle:** `LlmModelService` ist der einzige Einstieg. Der optionale API-Schlüssel wird über `SettingsEncryptor`
  verschlüsselt, bevor die Datenbank ihn sieht; jede Änderung wird protokolliert. Genau ein Modell ist systemweit aktiv.
- **Web-Schicht:** `llm.web` (Modellverwaltung, Spracheingabe), `indexing.web` (Indexierungsverwaltung, Bestands-
  und Nachlauf), `metadata.web` (Dokument- und Bibliotheksmetadaten, der Metadatenfilter aller Such- und Chat-Anfragen samt
  strengem `MetadataFilterDeserializer`). Die Endpunkte der Bibliothek liegen in `library.web`.

## Maßgebliche ADRs

- [ADR-0017](../../../../../../../docs/decisions/0017-quellentypmodell-indizierung.md) (Entscheidungen 2, 3, 5): Quellentyp und gemeinsame Kette
- [ADR-0018](../../../../../../../docs/decisions/0018-quellkonfiguration-in-der-bibliothek.md): Bibliothek als Quelle
- [ADR-0020](../../../../../../../docs/decisions/0020-ordner-in-bibliotheken-navigation.md): Ordner als Navigation
- [ADR-0022](../../../../../../../docs/decisions/0022-anhang-als-eigenes-dokument.md): Anhang als eigenes Dokument
- [ADR-0024](../../../../../../../docs/decisions/0024-metadatenschema-kernfelder.md): Metadatenschema und Kernfelder
- [ADR-0026](../../../../../../../docs/decisions/0026-kontextpraefix-abdruck-und-modellgestuetzte-extraktion.md): Kontextpräfix, modellgestützte Extraktion
- [ADR-0028](../../../../../../../docs/decisions/0028-tsv-version-uebergang.md): Übergang von `content_tsv_version`
- [ADR-0030](../../../../../../../docs/decisions/0030-originalablage-der-uploads.md): Originalablage der Uploads
- [ADR-0045](../../../../../../../docs/decisions/0045-gemeinsamer-datei-abgleich.md): gemeinsamer Datei-Abgleich
- [ADR-0049](../../../../../../../docs/decisions/0049-private-bibliotheken-und-kontingente.md) (Entscheidung 5): Kontingent überspringt Elemente

## Verweise

- Handbuch: `docs/handbuch/wissensbibliotheken.md`, `docs/handbuch/metadaten.md`
- Strukturtests: `ModularArchitectureTest`

## Tests bei Änderungen

```bash
./gradlew test -PtestShard=indexing      # indexing, format, metadata, llm
./gradlew test --tests 'io.opaa.knowledge.*' --tests 'io.opaa.health.*' --tests 'io.opaa.architecture.*'
```

Schemaänderungen: neue Datei unter `db/changelog/knowledge/`, Regeln in `backend/AGENTS.md`, „Liquibase“.
Die `vector_store`-Ausdrucksindexe der Baseline bleiben eigenständige Changesets. Ändern sich Schnitt, Einbettung oder Metadaten, misst `./gradlew evaluateRetrieval` die Suchqualität (`eval/README.md`).
