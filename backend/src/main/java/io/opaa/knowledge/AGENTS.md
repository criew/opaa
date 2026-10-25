# Modul knowledge

Pakete (`io.opaa.*`): knowledge, llm, metadata, indexing, health; Konnektorvertrag: `indexing/source/AGENTS.md`.

## Zweck und Grenze

Der Bestand einer Wissensbibliothek — Bibliothek, Ordner, Dokumente, Zugriffsprüfung, Ablage der
Originale (`knowledge`) —, das Metadatenschema (`metadata`), der Weg vom Dokument in den Index
(`indexing`), die verwalteten Chat-Modelle (`llm`) und die Health-Indikatoren für Modelle und Vektorindex (`health`).

## Invarianten und Stolpersteine

- **`knowledge` liegt unter `indexing` und `library` und nennt keines davon.** Ports nach oben: `FolderDocumentDeleter`;
  `ConnectorDataFlows` in `indexing.registry.SourceConnectorRegistry`; in connections `SourceStateLookup` und
  `SourceConnectionResolver` (Ziel, Geheimnis; nur knowledge, library, connections, `theSecretPortStaysWithTheCore`).
- **`metadata` liegt zwischen `knowledge` und `indexing`** (Schema, Kernfelder, Vokabular, Extraktion,
  Korrektur, Filter, Kontextpräfix) und kennt keine Pipeline-Klasse; Chunk-Store und Nachlauf erreicht es über
  `ChunkMetadataStore` und `ContextPrefixBacklog`. Bestandslauf und Nachlauf liegen in `indexing.maintenance`.
- **Ein Aufnahmeweg:** Jede Quelle und jeder Upload geht durch `DocumentIngestService#ingest` (Virenprüfung,
  parsen, schneiden, speichern, markieren; ein Upload kommt geprüft an). Die `Document`-Zeile gehört `knowledge`.
- **Modellwechsel:** Passt der Vektorindex nicht zum Embedding-Modell (`EmbeddingIndexState`), schreibt
  `VectorStoreWriter` in `embedding_next`; `EmbeddingReindexService` bettet neu ein und schaltet um.
- **Richtung im Kern von `indexing`** (`ModularArchitecture.INDEXING_CORE`): `chunk`, `job` (Lauf, Protokoll),
  `attachment` (Übergabe an den Anhangspfad), `document` (Aufnahme samt Anhängen), `spi` (Konnektor-Vertrag),
  `credentials` (Ziel, Geheimnis, Dienstkonto), `registry` (Konnektor je Typ), `run` (Laufrahmen, Push-Eingang),
  `maintenance`, `filesync` (Datei-Abgleich ohne Konnektor und Anbieter), `trigger` (Auslöser, nie von Konnektoren);
  darüber Wurzel, `web`, Konnektoren. Rückwege sind Ports: `VanishedDocumentReconciler`, `EmbeddingBatching`.
- **`FormatConfiguration`** registriert die Dateiformate (Modul format) als Beans; `DocumentIngestService`
  lehnt ein Format ab, das einen Schemaschlüssel als Passthrough-Schlüssel deklariert.
- **Chat-Modelle:** `LlmModelService` ist der einzige Einstieg; der optionale API-Schlüssel wird über
  `SettingsEncryptor` verschlüsselt, jede Änderung protokolliert. Genau ein Modell ist systemweit aktiv.
- **Web-Schicht:** `llm.web` (Modelle, Spracheingabe), `indexing.web` (Indexierung, Bestands- und
  Nachlauf), `metadata.web` (Metadaten, der Filter aller Such- und Chat-Anfragen samt strengem
  `MetadataFilterDeserializer`). Die Endpunkte der Bibliothek liegen in `library.web`.

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

- Handbuch: `arbeitsmittel/wissensbibliotheken/README.md`, `arbeitsmittel/wissensbibliotheken/metadaten.md`

## Tests bei Änderungen

```bash
./gradlew test -PtestShard=indexing      # indexing, format, metadata, llm
./gradlew test --tests 'io.opaa.knowledge.*' --tests 'io.opaa.health.*' --tests 'io.opaa.architecture.*'
```

Ändern sich Schnitt, Einbettung oder Metadaten, misst `./gradlew evaluateRetrieval` die Suchqualität.
